package com.amneziaguard.core.netstack.tcp

import com.amneziaguard.core.netstack.packet.FlowKey
import com.amneziaguard.core.netstack.packet.PacketBuilder
import com.amneziaguard.core.netstack.socks.Socks5Client
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * One relayed TCP flow. Terminates the app's TCP locally and forwards the
 * stream through the SOCKS5 (amneziawg-go) to the real destination.
 *
 * This is a pragmatic tun2socks TCP proxy, not a full stack: it assumes the tun
 * is in-order and lossless (true for a local VpnService tun), advertises a
 * fixed window, and ignores retransmission/SACK. Sequence numbers are tracked
 * mod 2^32. Good enough to carry real connections; edge cases get refined with
 * on-device iteration.
 */
class TcpConnection(
    private val key: FlowKey, // app → server perspective
    private val socksPort: Int,
    private val credentials: Socks5Client.Credentials,
    private val mtu: Int,
    private val writeToTun: (ByteArray) -> Unit,
    private val onClosed: (FlowKey) -> Unit,
) {
    private val appIp = key.sourceIp
    private val appPort = key.sourcePort
    private val dstIp = key.destIp
    private val dstPort = key.destPort

    private val lock = Any()
    private var ourSeq = 1L // server → app sequence
    private var theirSeq = 0L // next byte expected from app
    @Volatile private var established = false
    @Volatile private var closed = false
    private var socks: Socket? = null

    /** SYN from the app: open the upstream, then reply SYN-ACK. */
    fun onSyn(synSeq: Long) {
        theirSeq = mask(synSeq + 1) // SYN consumes one sequence number
        thread(name = "tcp-$appPort", isDaemon = true) {
            val s = runCatching {
                Socket().apply {
                    connect(InetSocketAddress("127.0.0.1", socksPort), 8_000)
                    Socks5Client.connect(getInputStream(), getOutputStream(), dstIp, dstPort, credentials)
                }
            }.getOrNull()
            if (s == null) {
                sendFlags(PacketBuilder.TcpFlag.RST or PacketBuilder.TcpFlag.ACK, seqOverride = 0)
                onClosed(key)
                return@thread
            }
            synchronized(lock) {
                socks = s
                established = true
                sendFlags(PacketBuilder.TcpFlag.SYN or PacketBuilder.TcpFlag.ACK)
                ourSeq = mask(ourSeq + 1) // our SYN consumes one
            }
            pumpUpstreamToTun(s)
        }
    }

    /** In-order payload from the app → forward to the upstream and ACK. */
    fun onData(seq: Long, payload: ByteArray) {
        if (payload.isEmpty()) return
        val out = synchronized(lock) {
            if (!established) return
            if (seq != theirSeq) {
                // Retransmit / out-of-order: re-ACK what we have and drop.
                sendFlags(PacketBuilder.TcpFlag.ACK)
                return
            }
            theirSeq = mask(theirSeq + payload.size)
            socks?.getOutputStream()
        } ?: return
        runCatching {
            out.write(payload)
            out.flush()
            synchronized(lock) { sendFlags(PacketBuilder.TcpFlag.ACK) }
        }.onFailure { close() }
    }

    /** FIN from the app: ACK it and half-close the upstream. */
    fun onFin(seq: Long) {
        synchronized(lock) {
            if (mask(seq) == theirSeq) theirSeq = mask(theirSeq + 1) // FIN consumes one
            sendFlags(PacketBuilder.TcpFlag.ACK)
        }
        runCatching { socks?.shutdownOutput() }
    }

    fun onRst() = close()

    private fun pumpUpstreamToTun(s: Socket) {
        val buf = ByteArray((mtu - 40).coerceAtLeast(536))
        val input = runCatching { s.getInputStream() }.getOrNull() ?: return
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                synchronized(lock) {
                    if (closed) return
                    writeToTun(
                        PacketBuilder.ipv4Tcp(
                            dstIp, appIp, dstPort, appPort,
                            seq = ourSeq, ack = theirSeq,
                            flags = PacketBuilder.TcpFlag.PSH or PacketBuilder.TcpFlag.ACK,
                            payload = buf.copyOf(n),
                        ),
                    )
                    ourSeq = mask(ourSeq + n)
                }
            }
            // Upstream EOF → send FIN to the app.
            synchronized(lock) {
                if (!closed) {
                    sendFlags(PacketBuilder.TcpFlag.FIN or PacketBuilder.TcpFlag.ACK)
                    ourSeq = mask(ourSeq + 1)
                }
            }
        } catch (_: Exception) {
            // Reset the app side on upstream error.
            synchronized(lock) { if (!closed) sendFlags(PacketBuilder.TcpFlag.RST or PacketBuilder.TcpFlag.ACK) }
        } finally {
            close()
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        runCatching { socks?.close() }
        onClosed(key)
    }

    /** Emits a server→app segment with the current seq/ack (no payload). */
    private fun sendFlags(flags: Int, seqOverride: Long? = null) {
        writeToTun(
            PacketBuilder.ipv4Tcp(
                dstIp, appIp, dstPort, appPort,
                seq = seqOverride ?: ourSeq, ack = theirSeq, flags = flags,
            ),
        )
    }

    private fun mask(v: Long): Long = v and 0xFFFFFFFFL
}
