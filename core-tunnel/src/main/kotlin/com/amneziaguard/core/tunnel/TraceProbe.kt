package com.amneziaguard.core.tunnel

import java.net.InetSocketAddress
import java.net.Socket

/** Readiness check for the local SOCKS5 that amneziawg-go exposes. */
object TraceProbe {

    /**
     * Blocks (up to ~5s) until the SOCKS5 listener accepts connections.
     *
     * amneziawg-go returns from start() before its listener is bound, so relaying
     * the first flow into it without waiting loses that flow to a refused connect.
     */
    fun awaitPort(port: Int) {
        repeat(20) {
            val ok = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
                true
            }.getOrDefault(false)
            if (ok) return
            Thread.sleep(250)
        }
    }
}
