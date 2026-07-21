package com.amneziaguard.core.tunnel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.ProxyGoBackend
import org.amnezia.awg.backend.SocketProtector
import org.amnezia.awg.config.Config
import org.amnezia.awg.config.proxy.Socks5Proxy
import java.net.ServerSocket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs amneziawg-go in proxy mode so it exposes a local SOCKS5 server backed by
 * the obfuscated WireGuard transport. This is the datapath our own tun2socks
 * (with per-app UID filtering) will relay allowed flows into — replacing the
 * stock hev-socks5-tunnel bridge.
 *
 * Uses the public `org.amnezia.awg.ProxyGoBackend` JNI directly. During the
 * spike there is no VpnService, so the socket protector is a no-op; once wired
 * behind our FilteringVpnService it must call VpnService.protect(fd).
 */
@Singleton
class AmneziaProxyController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backendProvider: BackendProvider,
) {
    @Volatile private var handle: Int = -1
    @Volatile private var socksPort: Int = -1

    // amneziawg-go treats a 0 return from bypass() as EACCES (protect failed),
    // so the no-op must return non-zero. With bypass=0 it is never called.
    private var protector: SocketProtector = SocketProtector { 1 }

    val port: Int get() = socksPort
    val isRunning: Boolean get() = handle != -1

    /** Sets the protector used for amneziawg-go's outbound sockets (VpnService.protect). */
    fun setProtector(p: SocketProtector) { protector = p }

    /**
     * Starts the proxy for the given full .conf text (with PrivateKey).
     *
     * [bypass] mirrors amneziawg-go's flag: 0 leaves the WireGuard UDP socket
     * unprotected (correct when no VpnService is up, e.g. the spike); 1 installs
     * the socket protector and requires it to return non-zero (used once this
     * runs behind our FilteringVpnService, where protect() excludes the socket).
     *
     * @return the local SOCKS5 port on success.
     */
    suspend fun start(
        confText: String,
        tunnelName: String = "awgproxy",
        bypass: Int = 0,
    ): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                // GoBackend's constructor loads the shared native library that
                // also backs the ProxyGoBackend static natives.
                backendProvider.backend
                if (handle != -1) return@runCatching socksPort

                val port = freePort()
                val base = Config.parse(confText.byteInputStream())
                val builder = Config.Builder()
                    .setInterface(base.getInterface())
                    .addPeers(base.peers)
                    .addProxy(Socks5Proxy("127.0.0.1:$port", SOCKS_USER, SOCKS_PASS))
                base.dnsSettings?.let { builder.setDnsSettings(it) }
                val config = builder.build()
                val quick = config.toAwgQuickStringResolved(false, true, true, context)

                ProxyGoBackend.awgSetSocketProtector(protector)
                val h = ProxyGoBackend.awgStartProxy(
                    tunnelName.take(15),
                    quick,
                    context.dataDir.absolutePath,
                    bypass,
                )
                check(h >= 0) { "awgStartProxy returned error code $h" }
                handle = h
                socksPort = port
                port
            }
        }

    suspend fun stop() = withContext(Dispatchers.IO) {
        runCatching {
            if (handle != -1) {
                ProxyGoBackend.awgStopProxy()
                ProxyGoBackend.awgResetJNIGlobals()
            }
        }
        handle = -1
        socksPort = -1
    }

    private fun freePort(): Int = ServerSocket(0).use {
        it.reuseAddress = true
        it.localPort
    }

    companion object {
        const val SOCKS_USER = "amneziaguard"
        const val SOCKS_PASS = "amneziaguard"
    }
}
