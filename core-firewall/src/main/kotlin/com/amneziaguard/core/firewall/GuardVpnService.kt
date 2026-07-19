package com.amneziaguard.core.firewall

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import kotlin.concurrent.thread

/**
 * A blackhole VpnService used for the non-root "no internet" enforcement while
 * the AmneziaWG tunnel is *not* up. It establishes a VPN that captures the
 * BLOCK-listed apps and discards every packet, so those apps have no network.
 *
 * Only one VPN can be established at a time, so this must never run alongside
 * the AmneziaWG tunnel — the orchestrator guarantees that.
 */
class GuardVpnService : VpnService() {

    @Volatile private var pfd: ParcelFileDescriptor? = null
    private var drainThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val allowed = intent.getStringArrayListExtra(EXTRA_ALLOWED).orEmpty()
                val exempt = intent.getStringArrayListExtra(EXTRA_EXEMPT).orEmpty()
                establish(allowed, exempt)
            }
            ACTION_STOP -> stopBlackhole()
        }
        return START_STICKY
    }

    /**
     * Establishes a blackhole VPN. In BLOCK mode [allowed] apps are captured
     * (everyone else is untouched). In kill-switch mode [exempt] apps are the
     * only ones left with network (everyone else is captured). Exactly one list
     * is non-empty — the VpnService.Builder cannot mix allow and disallow.
     */
    private fun establish(allowed: List<String>, exempt: List<String>) {
        if (allowed.isEmpty() && exempt.isEmpty()) {
            stopBlackhole()
            return
        }
        stopBlackhole()
        val builder = Builder()
            .setSession("AmneziaGuard block")
            .addAddress("10.66.66.1", 32)
            .addAddress("fd66:66:66::1", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("10.66.66.2")
            .setBlocking(true)
        allowed.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
        exempt.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
        val descriptor = runCatching { builder.establish() }.getOrNull() ?: return
        pfd = descriptor
        drainThread = thread(name = "guard-drain", isDaemon = true) {
            drain(descriptor)
        }
    }

    private fun drain(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(32_767)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val read = input.read(buffer)
                if (read < 0) break
                // Packets are read and discarded — the app never reaches the network.
            }
        } catch (_: Exception) {
            // Interface torn down.
        }
    }

    private fun stopBlackhole() {
        drainThread?.interrupt()
        drainThread = null
        runCatching { pfd?.close() }
        pfd = null
    }

    override fun onRevoke() {
        stopBlackhole()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopBlackhole()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.amneziaguard.guard.START"
        const val ACTION_STOP = "com.amneziaguard.guard.STOP"
        const val EXTRA_ALLOWED = "allowed"
        const val EXTRA_EXEMPT = "exempt"
    }
}
