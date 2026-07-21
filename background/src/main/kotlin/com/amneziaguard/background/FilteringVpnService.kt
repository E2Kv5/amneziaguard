package com.amneziaguard.background

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch { runProtectedProbe() }
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

    private fun establishTun(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("AmneziaGuard filter")
            .addAddress("10.111.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("10.111.0.3")
            .setMtu(1280)
        // Capture only our own package so amneziawg-go's UDP socket must be
        // protect()'d to escape, and no other app is affected during the test.
        runCatching { builder.addAllowedApplication(packageName) }
        return runCatching { builder.establish() }.getOrNull()
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
    }
}
