package com.amneziaguard.core.netstack.udp

import android.util.Log
import com.amneziaguard.core.netstack.Tun2Socks
import com.amneziaguard.core.netstack.packet.FlowKey
import com.amneziaguard.core.netstack.packet.PacketBuilder
import com.amneziaguard.core.netstack.socks.Socks5Client
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Carries the apps' UDP through the tunnel using SOCKS5 UDP ASSOCIATE.
 *
 * amneziawg-go's SOCKS5 dials associated UDP targets with the WireGuard
 * netstack's dialer, so datagrams really do traverse the tunnel — which is what
 * lets the engine carry QUIC, games and plain DNS instead of only TCP.
 *
 * One association is kept per app UDP socket (source ip:port). That mirrors what
 * the app itself has open, keeps replies unambiguous when several apps talk to
 * the same server, and dies with an idle timeout so sockets don't accumulate.
 */
class Socks5UdpRelay(
    private val socksPort: Int,
    private val credentials: Socks5Client.Credentials,
    private val mtu: Int,
    private val writeToTun: (ByteArray) -> Unit,
) {
    private val associations = ConcurrentHashMap<Long, Association>()
    private val reaper = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "udp-reaper").apply { isDaemon = true }
    }

    @Volatile private var running = true

    init {
        reaper.scheduleWithFixedDelay(::reapIdle, IDLE_TIMEOUT_MS, IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** Sends one datagram from an app; returns false if the association failed. */
    fun send(flow: FlowKey, payload: ByteArray): Boolean {
        if (!running) return false
        // Only IPv4 reaches the relay (the read loop drops v6 before dispatch),
        // so the source address packs into the key; anything else would collide.
        if (flow.sourceIp.size != 4) return false
        val key = associationKey(flow.sourceIp, flow.sourcePort)
        val association = associations.getOrPut(key) {
            Association(flow.sourceIp, flow.sourcePort).also {
                if (!it.open()) {
                    associations.remove(key)
                    return false
                }
            }
        }
        return association.send(flow.destIp, flow.destPort, payload)
    }

    /**
     * Source ip:port packed into a long. This runs per datagram, and formatting
     * a dotted-quad string here cost more than every other per-datagram step in
     * the relay combined.
     */
    private fun associationKey(sourceIp: ByteArray, sourcePort: Int): Long {
        val ip = ((sourceIp[0].toLong() and 0xFF) shl 24) or ((sourceIp[1].toLong() and 0xFF) shl 16) or
            ((sourceIp[2].toLong() and 0xFF) shl 8) or (sourceIp[3].toLong() and 0xFF)
        return (ip shl 16) or sourcePort.toLong()
    }

    fun stop() {
        running = false
        reaper.shutdownNow()
        associations.values.forEach { runCatching { it.close() } }
        associations.clear()
    }

    private fun reapIdle() {
        val deadline = System.currentTimeMillis() - IDLE_TIMEOUT_MS
        associations.entries.removeAll { (_, association) ->
            (association.lastUsed < deadline).also { if (it) runCatching { association.close() } }
        }
    }

    private inner class Association(
        private val appIp: ByteArray,
        private val appPort: Int,
    ) {
        /** Human-readable only, for logs — built once, never per datagram. */
        private val key = "${Tun2Socks.ip(appIp)}:$appPort"

        @Volatile var lastUsed = System.currentTimeMillis()
        private var control: Socket? = null
        private var udp: DatagramSocket? = null
        private var relay: InetSocketAddress? = null
        @Volatile private var closed = false

        fun open(): Boolean = try {
            val socket = Socket().apply {
                connect(InetSocketAddress("127.0.0.1", socksPort), CONNECT_TIMEOUT_MS)
                soTimeout = CONNECT_TIMEOUT_MS
            }
            val (addr, port) = Socks5Client.associate(
                socket.getInputStream(), socket.getOutputStream(), credentials,
            )
            // A 0.0.0.0 bind address means "the host you're already talking to".
            val relayHost = if (addr.all { it.toInt() == 0 }) {
                InetAddress.getByName("127.0.0.1")
            } else {
                InetAddress.getByAddress(addr)
            }
            socket.soTimeout = 0
            control = socket
            relay = InetSocketAddress(relayHost, port)
            udp = DatagramSocket()
            thread(name = "udp-$appPort", isDaemon = true) { pumpReplies() }
            Log.d(TAG, "udp association $key → relay ${relayHost.hostAddress}:$port")
            true
        } catch (e: Exception) {
            Log.w(TAG, "udp associate for $key failed: ${e.message}")
            close()
            false
        }

        fun send(destIp: ByteArray, destPort: Int, payload: ByteArray): Boolean {
            val socket = udp ?: return false
            val target = relay ?: return false
            return try {
                val framed = frame(destIp, destPort, payload)
                socket.send(DatagramPacket(framed, framed.size, target))
                lastUsed = System.currentTimeMillis()
                true
            } catch (e: Exception) {
                Log.w(TAG, "udp send for $key failed: ${e.message}")
                close()
                false
            }
        }

        /**
         * RFC 1928 §7: RSV(2) FRAG(1) ATYP(1) DST.ADDR DST.PORT, then the
         * datagram — written into one buffer, since building the header from
         * concatenated arrays allocated four of them per datagram.
         */
        private fun frame(destIp: ByteArray, destPort: Int, payload: ByteArray): ByteArray {
            val framed = ByteArray(4 + destIp.size + 2 + payload.size)
            framed[3] = (if (destIp.size == 16) ATYP_IPV6 else ATYP_IPV4).toByte()
            destIp.copyInto(framed, 4)
            val port = 4 + destIp.size
            framed[port] = (destPort shr 8).toByte()
            framed[port + 1] = destPort.toByte()
            payload.copyInto(framed, port + 2)
            return framed
        }

        private fun pumpReplies() {
            val socket = udp ?: return
            val buf = ByteArray(MAX_DATAGRAM)
            while (!closed) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: Exception) {
                    if (!closed) Log.d(TAG, "udp receive for $key ended: ${e.message}")
                    return
                }
                lastUsed = System.currentTimeMillis()
                val reply = decode(buf, packet.length) ?: continue
                val (originIp, originPort, data) = reply
                // The synthesized packet has to fit the tun; oversized replies
                // would need IP fragmentation, so drop them loudly instead.
                if (data.size > mtu - IP_UDP_OVERHEAD) {
                    Log.w(TAG, "udp reply ${data.size}B exceeds MTU for $key — dropped")
                    continue
                }
                writeToTun(
                    PacketBuilder.ipv4Udp(
                        sourceIp = originIp, destIp = appIp,
                        sourcePort = originPort, destPort = appPort,
                        payload = data,
                    ),
                )
            }
        }

        private fun decode(buf: ByteArray, length: Int): Triple<ByteArray, Int, ByteArray>? {
            if (length < 10) return null
            if (buf[2].toInt() != 0) return null // fragmented datagrams aren't supported
            var offset = 4
            val addr = when (buf[3].toInt() and 0xFF) {
                ATYP_IPV4 -> buf.copyOfRange(offset, offset + 4).also { offset += 4 }
                ATYP_IPV6 -> buf.copyOfRange(offset, offset + 16).also { offset += 16 }
                else -> return null // a domain origin can't be put back on the tun
            }
            if (offset + 2 > length) return null
            val port = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
            offset += 2
            if (addr.size != 4) return null // the tun path is IPv4 only for now
            return Triple(addr, port, buf.copyOfRange(offset, length))
        }

        fun close() {
            closed = true
            runCatching { udp?.close() }
            runCatching { control?.close() }
            udp = null
            control = null
        }
    }

    private companion object {
        const val TAG = Tun2Socks.TAG
        const val ATYP_IPV4 = 0x01
        const val ATYP_IPV6 = 0x04
        const val CONNECT_TIMEOUT_MS = 5_000
        const val MAX_DATAGRAM = 32_768
        const val IP_UDP_OVERHEAD = 28
        const val IDLE_TIMEOUT_MS = 60_000L
    }
}
