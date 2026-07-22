package com.amneziaguard.core.netstack.dns

import android.util.Log
import com.amneziaguard.core.netstack.Tun2Socks
import com.amneziaguard.core.netstack.packet.FlowKey
import com.amneziaguard.core.netstack.packet.PacketBuilder
import com.amneziaguard.core.netstack.socks.Socks5Client
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Carries the apps' plain UDP DNS through the tunnel.
 *
 * The SOCKS5 exposed by amneziawg-go only offers CONNECT, so a UDP query can't
 * be forwarded as-is. Instead each query is re-sent as DNS-over-TCP (RFC 7766:
 * a two-byte length prefix in front of the message) to the very resolver the app
 * addressed, and the reply is handed back as a UDP datagram synthesized onto the
 * tun. Public resolvers all speak DNS-over-TCP, so this is transparent to apps.
 *
 * Non-DNS UDP (QUIC, games) still isn't carried — those apps fall back to TCP.
 */
class DnsOverTcpRelay(
    private val socksPort: Int,
    private val credentials: Socks5Client.Credentials,
    private val writeToTun: (ByteArray) -> Unit,
) {
    private val executor = Executors.newFixedThreadPool(POOL_SIZE) { runnable ->
        Thread(runnable, "dns-relay").apply { isDaemon = true }
    }

    /** [key] is the app→resolver flow; [query] is the raw DNS message. */
    fun resolve(key: FlowKey, query: ByteArray) {
        executor.execute {
            val started = System.currentTimeMillis()
            try {
                val response = askOverTcp(key, query)
                writeToTun(
                    PacketBuilder.ipv4Udp(
                        sourceIp = key.destIp, destIp = key.sourceIp,
                        sourcePort = key.destPort, destPort = key.sourcePort,
                        payload = response,
                    ),
                )
                Log.d(TAG, "dns ${Tun2Socks.ip(key.destIp)} answered ${response.size}B " +
                    "in ${System.currentTimeMillis() - started}ms")
            } catch (e: Exception) {
                // Dropping is the honest failure here: the app retries or falls
                // back to another resolver, whereas a forged reply would poison it.
                Log.w(TAG, "dns query to ${Tun2Socks.ip(key.destIp)} failed " +
                    "after ${System.currentTimeMillis() - started}ms: ${e.message}")
            }
        }
    }

    private fun askOverTcp(key: FlowKey, query: ByteArray): ByteArray =
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), CONNECT_TIMEOUT_MS)
            socket.soTimeout = QUERY_TIMEOUT_MS
            Socks5Client.connect(
                socket.getInputStream(), socket.getOutputStream(),
                key.destIp, key.destPort, credentials,
            )
            socket.getOutputStream().apply {
                write(byteArrayOf((query.size shr 8).toByte(), query.size.toByte()))
                write(query)
                flush()
            }
            val input = socket.getInputStream()
            val header = readExactly(input, 2)
            val length = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
            require(length in 1..MAX_RESPONSE) { "bogus DNS length $length" }
            readExactly(input, length)
        }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) throw java.io.EOFException("stream closed after $read/$n bytes")
            read += r
        }
        return buf
    }

    fun stop() {
        executor.shutdownNow()
        runCatching { executor.awaitTermination(1, TimeUnit.SECONDS) }
    }

    private companion object {
        const val TAG = Tun2Socks.TAG
        const val POOL_SIZE = 4
        const val CONNECT_TIMEOUT_MS = 5_000
        const val QUERY_TIMEOUT_MS = 8_000
        const val MAX_RESPONSE = 65_535
    }
}
