package com.amneziaguard.background

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.amneziaguard.core.netstack.RelayPolicy
import com.amneziaguard.core.netstack.Tun2Socks
import com.amneziaguard.core.netstack.socks.Socks5Client
import com.amneziaguard.core.tunnel.AmneziaProxyController
import com.amneziaguard.core.tunnel.ServerConfigAssembler
import com.amneziaguard.core.tunnel.TraceProbe
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.amnezia.awg.backend.SocketProtector
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * Milestone 1 of the userspace datapath: our own VpnService that captures this
 * app only, starts amneziawg-go with bypass=1 and a real [protect]-backed
 * socket protector, and confirms the SOCKS5 exit IP works *under an active VPN*.
 *
 * This isolates the one thing the plain spike could not test (the plain spike
 * used bypass=0). Once green, the full TCP/UDP relay slots into the same tun.
 */
@AndroidEntryPoint
class FilteringVpnService : VpnService() {

    @Inject lateinit var proxyController: AmneziaProxyController
    @Inject lateinit var configAssembler: ServerConfigAssembler
    @Inject lateinit var diagnostics: FilteringDiagnostics

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var tun: ParcelFileDescriptor? = null
    private var drain: Thread? = null
    @Volatile private var engine: Tun2Socks? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
            }
            ACTION_RELAY_TEST -> scope.launch { runRelayTest() }
            else -> scope.launch { runProtectedProbe() }
        }
        return START_NOT_STICKY
    }

    private suspend fun runProtectedProbe() {
        val log: (String) -> Unit = { diagnostics.append(it) }
        diagnostics.reset()

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
     * Milestone 2: exercise the tun2socks TCP relay. Capture this app, start
     * amneziawg-go (bypass=1 + protect), run the engine, then open a *real*
     * socket to 1.1.1.1:443 — it flows tun → engine → SOCKS5 → tunnel. A
     * VPN-server exit IP proves the relay carries a real TCP+TLS connection.
     */
    private suspend fun runRelayTest() {
        val log: (String) -> Unit = { diagnostics.append(it) }
        diagnostics.reset()

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

        log("Probing exit IP through the relay…")
        val ip = runCatching { TraceProbe.fetchExitIpDirect(log) }
            .getOrElse { log("Probe error: ${it.message}"); null }

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
    private fun establishTun(): ParcelFileDescriptor? {
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
        runCatching { builder.addAllowedApplication(packageName) }
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
        runCatching { engine?.stop() }
        engine = null
        drain?.interrupt()
        drain = null
        runCatching { tun?.close() }
        tun = null
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.amneziaguard.filter.START"
        const val ACTION_STOP = "com.amneziaguard.filter.STOP"
        const val ACTION_RELAY_TEST = "com.amneziaguard.filter.RELAY_TEST"
        private const val MTU = 1280
    }
}
