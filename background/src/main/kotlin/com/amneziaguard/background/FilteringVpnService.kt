package com.amneziaguard.background

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import android.content.pm.ServiceInfo
import android.os.Build
import com.amneziaguard.core.data.model.AppMode
import com.amneziaguard.core.data.repo.RuleRepository
import com.amneziaguard.core.data.repo.ServerRepository
import com.amneziaguard.core.data.settings.SettingsRepository
import com.amneziaguard.core.firewall.FirewallPolicyCompiler
import com.amneziaguard.core.firewall.UidPolicyResolver
import com.amneziaguard.core.netstack.RelayPolicy
import com.amneziaguard.core.netstack.Tun2Socks
import com.amneziaguard.core.netstack.socks.Socks5Client
import com.amneziaguard.core.tunnel.AmneziaProxyController
import com.amneziaguard.core.tunnel.ServerConfigAssembler
import com.amneziaguard.core.tunnel.TraceProbe
import com.amneziaguard.core.tunnel.TunnelState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.amnezia.awg.backend.SocketProtector
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * The userspace datapath: our own VpnService carrying app traffic through
 * amneziawg-go's SOCKS5 with per-app policy applied in between — which is what
 * finally makes the BLOCK mode work without root while the tunnel is up.
 *
 * It also hosts the two diagnostic probes that got it here (a protected spike
 * and a one-shot relay test), kept because they isolate the proxy plumbing from
 * the relay when something regresses.
 */
@AndroidEntryPoint
class FilteringVpnService : VpnService() {

    @Inject lateinit var proxyController: AmneziaProxyController
    @Inject lateinit var configAssembler: ServerConfigAssembler
    @Inject lateinit var diagnostics: FilteringDiagnostics
    @Inject lateinit var ruleRepository: RuleRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var policyCompiler: FirewallPolicyCompiler
    @Inject lateinit var policyResolver: UidPolicyResolver
    @Inject lateinit var engineState: FilteringEngineState
    @Inject lateinit var serverRepository: ServerRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var tun: ParcelFileDescriptor? = null
    private var drain: Thread? = null
    @Volatile private var engine: Tun2Socks? = null
    @Volatile private var notificationTitle: String = "AmneziaGuard"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(Tun2Socks.TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
            }
            ACTION_START_FIREWALL -> scope.launch { guarded("firewall engine") { runFirewall() } }
            ACTION_RELAY_TEST -> scope.launch { guarded("TCP relay test") { runRelayTest() } }
            else -> scope.launch { guarded("protected spike") { runProtectedProbe() } }
        }
        return START_NOT_STICKY
    }

    /**
     * Runs a probe so a thrown exception can't vanish into the coroutine scope:
     * without this a failure before the first log line looks like "nothing
     * happened", which is exactly the case that is hardest to diagnose remotely.
     */
    private suspend fun guarded(what: String, body: suspend () -> Unit) {
        try {
            body()
        } catch (e: Throwable) {
            Log.e(Tun2Socks.TAG, "$what crashed", e)
            diagnostics.append("$what crashed: ${e::class.java.simpleName}: ${e.message}")
            diagnostics.finish(null)
            runCatching { proxyController.stop() }
            teardown()
            stopSelf()
        }
    }

    private suspend fun runProtectedProbe() {
        val log: (String) -> Unit = { diagnostics.append(it) }
        diagnostics.reset("protected spike (VpnService, bypass=1 + protect)")

        val conf = configAssembler.activeServerConf()
        if (conf == null) {
            log("No active server / config.")
            diagnostics.finish(null); teardown(); stopSelf(); return
        }

        val fd = establishTun()
        if (fd == null) {
            log("establish() returned null (VPN not authorized?).")
            diagnostics.finish(null); teardown(); stopSelf(); return
        }
        tun = fd
        startDrain(fd)
        log("Tun up (this app captured); starting amneziawg-go bypass=1 + protect()…")

        proxyController.setProtector(SocketProtector { socketFd -> if (protect(socketFd)) 1 else 0 })
        val port = proxyController.start(conf, tunnelName = "awgfilter", bypass = 1).getOrElse {
            log("Proxy failed to start: ${it.message}")
            log("Details in logcat (AwgProxy / AmneziaWG).")
            diagnostics.finish(null); teardown(); stopSelf(); return
        }
        log("SOCKS5 up on 127.0.0.1:$port; probing exit IP under active VPN…")

        TraceProbe.awaitPort(port)
        val ip = runCatching { TraceProbe.fetchExitIp(port, log) }
            .getOrElse { log("Probe error: ${it.message}"); null }

        runCatching { proxyController.stop() }
        if (ip != null) {
            log("SUCCESS — protected exit IP $ip (bypass=1 + protect() works under our VpnService).")
        } else {
            log("No exit IP — see log/logcat.")
        }
        diagnostics.finish(ip)
        teardown()
        stopSelf()
    }

    /**
     * The real thing: bring the tunnel up through our own datapath and enforce
     * the per-app rules on it.
     *
     * BYPASS apps are kept out of the tun entirely (that is what the compiled
     * include/exclude sets express, and it is both cheaper and more honest than
     * capturing and re-emitting their traffic). Everything else is captured, and
     * each flow's owning app decides between relay and drop.
     */
    private suspend fun runFirewall() {
        val log: (String) -> Unit = { diagnostics.append(it) }
        diagnostics.reset("no-root firewall engine")
        engineState.set(TunnelState.Connecting)

        val conf = configAssembler.activeServerConf()
        if (conf == null) {
            log("No active server / config — pick one on the Servers screen first.")
            engineState.set(TunnelState.Error("no active server"))
            diagnostics.finish(null); teardown(); stopSelf(); return
        }

        val settings = settingsRepository.settings.first()
        val rules = ruleRepository.rules()
        policyResolver.update(rules, settings.defaultAppMode)
        val appPolicy = policyCompiler.compile(rules, settings.defaultAppMode)
        val blocked = rules.count { it.value == AppMode.BLOCK }
        log("Rules: default=${settings.defaultAppMode}, $blocked blocked, " +
            "${appPolicy.included.size} included, ${appPolicy.excluded.size} bypassed")

        val fd = establishTun(appPolicy.included, appPolicy.excluded)
        if (fd == null) {
            log("establish() returned null (VPN not authorized?).")
            engineState.set(TunnelState.Error("VPN not authorized"))
            diagnostics.finish(null); teardown(); stopSelf(); return
        }
        tun = fd
        notificationTitle = serverRepository.serverName(settings.activeServerId ?: -1L)
            ?.let { "AmneziaGuard · $it" } ?: "AmneziaGuard"
        startForegroundNotice("Connecting…")
        log("Tun up; starting amneziawg-go bypass=1 + protect()…")

        proxyController.setProtector(SocketProtector { socketFd -> if (protect(socketFd)) 1 else 0 })
        val port = proxyController.start(conf, tunnelName = "awgfw", bypass = 1).getOrElse {
            log("Proxy failed to start: ${it.message}")
            engineState.set(TunnelState.Error(it.message ?: "proxy failed"))
            diagnostics.finish(null); teardown(); stopSelf(); return
        }
        TraceProbe.awaitPort(port)
        log("SOCKS5 up on 127.0.0.1:$port; engine running — traffic is now filtered.")

        Tun2Socks(
            tunIn = FileInputStream(fd.fileDescriptor),
            tunOut = FileOutputStream(fd.fileDescriptor),
            socksPort = port,
            credentials = Socks5Client.Credentials(
                AmneziaProxyController.SOCKS_USER,
                AmneziaProxyController.SOCKS_PASS,
            ),
            mtu = MTU,
            policy = { flow ->
                if (policyResolver.modeFor(flow) == AppMode.BLOCK) RelayPolicy.DROP else RelayPolicy.RELAY
            },
            log = { /* per-flow noise stays in logcat only */ },
        ).also { engine = it }.start()

        engine?.let { running ->
            engineState.setTrafficSource {
                FilteringEngineState.Traffic(running.downloadedBytes, running.uploadedBytes)
            }
        }
        startNotificationUpdates()

        engineState.set(
            TunnelState.Up(
                serverId = settings.activeServerId ?: -1L,
                sinceEpochMs = System.currentTimeMillis(),
            ),
        )

        // Rule edits take effect without restarting the tunnel; only a change to
        // the include/exclude split would need a new tun, which is a restart.
        scope.launch {
            ruleRepository.observeRules().collect { updated ->
                policyResolver.update(updated, settingsRepository.settings.first().defaultAppMode)
            }
        }
    }

    private fun startForegroundNotice(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                TunnelNotifications.ENGINE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(TunnelNotifications.ENGINE_NOTIFICATION_ID, notification)
        }
    }

    private fun notification(text: String) = TunnelNotifications.build(
        context = this,
        title = notificationTitle,
        text = text,
        disconnect = TunnelNotifications.disconnectEngine(this),
    )

    /**
     * Keeps the ongoing notification honest: server name, that filtering is on,
     * and the current speed — the same information the fast path shows, since
     * from the user's side it is the same connection.
     */
    private fun startNotificationUpdates() {
        scope.launch {
            var lastDown = 0L
            var lastUp = 0L
            while (isActive) {
                val traffic = engineState.traffic()
                val text = if (traffic != null) {
                    val down = (traffic.downloadedBytes - lastDown).coerceAtLeast(0) / SAMPLE_SECONDS
                    val up = (traffic.uploadedBytes - lastUp).coerceAtLeast(0) / SAMPLE_SECONDS
                    lastDown = traffic.downloadedBytes
                    lastUp = traffic.uploadedBytes
                    "Filtering · ↓ ${TunnelNotifications.formatRate(down)} " +
                        "↑ ${TunnelNotifications.formatRate(up)}"
                } else {
                    "Filtering active"
                }
                TunnelNotifications.update(
                    this@FilteringVpnService,
                    TunnelNotifications.ENGINE_NOTIFICATION_ID,
                    notification(text),
                )
                delay(SAMPLE_SECONDS * 1_000)
            }
        }
    }

    /**
     * Milestone 2: exercise the tun2socks TCP relay. Capture this app, start
     * amneziawg-go (bypass=1 + protect), run the engine, then open a *real*
     * socket to 1.1.1.1:443 — it flows tun → engine → SOCKS5 → tunnel. A
     * VPN-server exit IP proves the relay carries a real TCP+TLS connection.
     */
    private suspend fun runRelayTest() {
        val log: (String) -> Unit = { diagnostics.append(it) }
        diagnostics.reset("TCP relay test (tun2socks)")

        val conf = configAssembler.activeServerConf()
        if (conf == null) {
            log("No active server / config."); diagnostics.finish(null); teardown(); stopSelf(); return
        }
        val fd = establishTun()
        if (fd == null) {
            log("establish() returned null (VPN not authorized?)."); diagnostics.finish(null); teardown(); stopSelf(); return
        }
        tun = fd
        log("Tun up (this app captured); starting amneziawg-go bypass=1 + protect()…")

        proxyController.setProtector(SocketProtector { socketFd -> if (protect(socketFd)) 1 else 0 })
        val port = proxyController.start(conf, tunnelName = "awgrelay", bypass = 1).getOrElse {
            log("Proxy failed to start: ${it.message}"); diagnostics.finish(null); teardown(); stopSelf(); return
        }
        TraceProbe.awaitPort(port)
        log("SOCKS5 up on 127.0.0.1:$port; starting tun2socks relay…")

        val relay = Tun2Socks(
            tunIn = FileInputStream(fd.fileDescriptor),
            tunOut = FileOutputStream(fd.fileDescriptor),
            socksPort = port,
            credentials = Socks5Client.Credentials(
                AmneziaProxyController.SOCKS_USER,
                AmneziaProxyController.SOCKS_PASS,
            ),
            mtu = MTU,
            policy = { RelayPolicy.RELAY }, // relay everything for this test
            log = log,
        ).also { engine = it }
        relay.start()

        // The VPN's per-UID routing rules land a moment after establish(); a
        // socket opened too early sends its SYN over the underlying network and
        // only its later packets get captured — a half-open flow the engine can
        // never serve. Let routing settle, and retry once if we still race it.
        log("Letting VPN routing settle…")
        delay(ROUTE_SETTLE_MS)

        log("Probing exit IP through the relay…")
        var ip = runCatching { TraceProbe.fetchExitIpDirect(log) }
            .getOrElse { log("Probe error: ${it.message}"); null }
        if (ip == null) {
            log("Retrying the probe with a fresh socket…")
            delay(ROUTE_SETTLE_MS)
            ip = runCatching { TraceProbe.fetchExitIpDirect(log) }
                .getOrElse { log("Retry error: ${it.message}"); null }
        }

        runCatching { relay.stop() }
        runCatching { proxyController.stop() }
        if (ip != null) {
            log("SUCCESS — TCP relay works, exit IP $ip.")
        } else {
            log("No exit IP — relay likely incomplete; see logcat.")
        }
        diagnostics.finish(ip)
        teardown()
        stopSelf()
    }

    /**
     * IPv4-only tun capturing just this app: amneziawg-go's UDP socket then has
     * to be protect()'d to escape, and no other app is affected by the test.
     * No ::/0 route — the engine has no IPv6 path yet, and capturing v6 without
     * an address only creates a blackhole that muddies the test.
     */
    private fun establishTun(
        included: Set<String> = setOf(packageName),
        excluded: Set<String> = emptySet(),
    ): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("AmneziaGuard filter")
            .addAddress("10.111.0.2", 32)
            .addRoute("0.0.0.0", 0)
            // A real resolver: Android probes DNS-over-TLS (TCP:853) on the
            // advertised server, which the TCP relay can actually carry. A
            // made-up address would just dead-end every DNS attempt.
            .addDnsServer("1.1.1.1")
            .setMtu(MTU)
            // Ask for blocking reads; otherwise read() returns 0 when idle.
            .setBlocking(true)
        // Builder rejects mixing the two lists; FirewallPolicyCompiler already
        // guarantees at most one of them is non-empty.
        included.forEach { runCatching { builder.addAllowedApplication(it) } }
        excluded.forEach { runCatching { builder.addDisallowedApplication(it) } }
        return runCatching { builder.establish() }
            .onFailure { diagnostics.append("establish() threw: ${it.message}") }
            .getOrNull()
    }

    /** Reads and discards captured packets so the tun fd can't back up. */
    private fun startDrain(fd: ParcelFileDescriptor) {
        drain = thread(name = "filter-drain", isDaemon = true) {
            val input = FileInputStream(fd.fileDescriptor)
            val buf = ByteArray(32_767)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    if (input.read(buf) < 0) break
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun teardown() {
        engineState.setTrafficSource(null)
        runCatching { engine?.stop() }
        engine = null
        runCatching { scope.launch { proxyController.stop() } }
        drain?.interrupt()
        drain = null
        runCatching { tun?.close() }
        tun = null
    }

    /** Another VPN took over: the datapath is gone, so say so rather than lying. */
    override fun onRevoke() {
        Log.i(Tun2Socks.TAG, "VPN revoked — tearing the engine down")
        engineState.set(TunnelState.Down)
        teardown()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        engineState.set(TunnelState.Down)
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.amneziaguard.filter.START"
        const val ACTION_STOP = "com.amneziaguard.filter.STOP"
        const val ACTION_RELAY_TEST = "com.amneziaguard.filter.RELAY_TEST"
        const val ACTION_START_FIREWALL = "com.amneziaguard.filter.START_FIREWALL"
        private const val MTU = 1280
        private const val ROUTE_SETTLE_MS = 1_500L
        private const val SAMPLE_SECONDS = 2L
    }
}
