package com.amneziaguard.core.tunnel

import com.amneziaguard.core.netstack.socks.Socks5Client
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Fetches the exit IP through a running local SOCKS5 (amneziawg-go): SOCKS5
 * CONNECT to 1.1.1.1:443, TLS over the tunnelled socket, GET /cdn-cgi/trace.
 * Shared by the plain spike and the VpnService-protected spike.
 */
object TraceProbe {

    /** Blocks (up to ~5s) until the SOCKS5 listener accepts connections. */
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

    fun fetchExitIp(port: Int, log: (String) -> Unit): String? {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 15_000

            log("SOCKS5 CONNECT → 1.1.1.1:443")
            Socks5Client.connect(
                socket.getInputStream(),
                socket.getOutputStream(),
                destIp = byteArrayOf(1, 1, 1, 1),
                destPort = 443,
                credentials = Socks5Client.Credentials(
                    AmneziaProxyController.SOCKS_USER,
                    AmneziaProxyController.SOCKS_PASS,
                ),
            )

            log("Tunnel established; TLS handshake + GET /cdn-cgi/trace")
            val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
            val tls = factory.createSocket(socket, "one.one.one.one", 443, false) as javax.net.ssl.SSLSocket
            tls.soTimeout = 15_000
            tls.startHandshake()
            tls.outputStream.write(
                ("GET /cdn-cgi/trace HTTP/1.1\r\n" +
                    "Host: one.one.one.one\r\n" +
                    "User-Agent: AmneziaGuard-spike\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            tls.outputStream.flush()

            val body = tls.inputStream.readBytes().toString(Charsets.UTF_8)
            val ip = body.lineSequence().firstOrNull { it.startsWith("ip=") }?.removePrefix("ip=")?.trim()
            if (ip == null) log("Response head: ${body.take(200)}")
            return ip
        }
    }
}
