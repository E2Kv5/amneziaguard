package com.amneziaguard.core.netstack.tcp

import android.util.Log
import com.amneziaguard.core.netstack.Tun2Socks
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
 * mod 2^32.
 */
class TcpConnection(
    private val key: FlowKey, // app → server perspective
    private val socksPort: Int,
    private val credentials: Socks5Client.Credentials,
    private val mtu: Int,
    private val writeToTun: (ByteArray) -> Unit,
    private val onClosed: (FlowKey) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val appIp = key.sourceIp
    private val appPort = key.sourcePort
    private val dstIp = key.destIp
    private val dstPort = key.destPort
    private val tag = "${Tun2Socks.ip(dstIp)}:$dstPort/$appPort"

    // Advertised in the SYN-ACK. Without it the app falls back to RFC 1122's
    // 536-byte default and sends us less than half an MTU per packet, which the
    // datapath pays for in packet rate on every uploaded byte.
    private val advertisedMss = (mtu - 40).coerceAtLeast(536)

    private val lock = Any()
    private var ourSeq = 1L // server → app sequence
    private var theirSeq = 0L // next byte expected from app
    private var duplicateAcks = 0 // sent for the current hole; reset once it closes
    @Volatile private var established = false
    @Volatile private var closed = false
    private var socks: Socket? = null

    /** SYN from the app: open the upstream, then reply SYN-ACK. */
    fun onSyn(synSeq: Long) {
        theirSeq = mask(synSeq + 1) // SYN consumes one sequence number
        thread(name = "tcp-$appPort", isDaemon = true) {
            val started = System.currentTimeMillis()
            val s = try {
                Socket().apply {
                    // Bounded: a hung handshake must never wedge the flow.
                    connect(InetSocketAddress("127.0.0.1", socksPort), SOCKS_CONNECT_TIMEOUT_MS)
                    soTimeout = SOCKS_HANDSHAKE_TIMEOUT_MS
                    Socks5Client.connect(getInputStream(), getOutputStream(), dstIp, dstPort, credentials)
                    soTimeout = 0 // unbounded once relaying
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$tag] SOCKS5 connect failed after ${System.currentTimeMillis() - started}ms", e)
                log("SOCKS5 connect failed ($tag): ${e.message}")
                sendFlags(PacketBuilder.TcpFlag.RST or PacketBuilder.TcpFlag.ACK, seqOverride = 0)
                onClosed(key)
                return@thread
            }
            Log.i(TAG, "[$tag] SOCKS5 established in ${System.currentTimeMillis() - started}ms; sending SYN-ACK")
            synchronized(lock) {
                socks = s
                established = true
                sendFlags(PacketBuilder.TcpFlag.SYN or PacketBuilder.TcpFlag.ACK, mss = advertisedMss)
                ourSeq = mask(ourSeq + 1) // our SYN consumes one
            }
            pumpUpstreamToTun(s)
        }
    }

    /** The app retransmitted its SYN: repeat the SYN-ACK if we already have one. */
    fun onSynRetransmit() {
        synchronized(lock) {
            if (!established || closed) return
            Log.d(TAG, "[$tag] SYN retransmit → resending SYN-ACK")
            writeToTun(
                PacketBuilder.ipv4Tcp(
                    dstIp, appIp, dstPort, appPort,
                    seq = mask(ourSeq - 1), ack = theirSeq,
                    flags = PacketBuilder.TcpFlag.SYN or PacketBuilder.TcpFlag.ACK,
                    // A retransmitted SYN-ACK must carry the option too: the app
                    // only ever sees one of them, and whichever arrives decides
                    // the segment size for the whole connection.
                    mss = advertisedMss,
                ),
            )
        }
    }

    /** In-order payload from the app → forward to the upstream and ACK. */
    fun onData(seq: Long, payload: ByteArray) {
        if (payload.isEmpty()) return
        val out = synchronized(lock) {
            if (!established || closed) return
            if (seq != theirSeq) {
                // Out of order, and we keep no reassembly buffer, so the segment
                // is dropped and the app has to resend from the hole.
                //
                // Only the first few get a duplicate ACK. Three is what triggers
                // fast retransmit, and beyond that they are pure harm: one lost
                // segment can be followed by a whole window of out-of-order ones,
                // and ACKing each would put hundreds of extra writes through the
                // tun lock exactly when it is already the bottleneck — which
                // causes the drops that caused the hole.
                duplicateAcks++
                if (duplicateAcks <= MAX_DUPLICATE_ACKS) {
                    Log.d(TAG, "[$tag] out-of-order seq=$seq expected=$theirSeq (${payload.size}B)")
                    sendFlags(PacketBuilder.TcpFlag.ACK)
                }
                return
            }
            duplicateAcks = 0
            theirSeq = mask(theirSeq + payload.size)
            socks?.getOutputStream()
        } ?: return
        try {
            out.write(payload)
            out.flush()
            synchronized(lock) { sendFlags(PacketBuilder.TcpFlag.ACK) }
        } catch (e: Exception) {
            Log.e(TAG, "[$tag] upstream write failed", e)
            close()
        }
    }

    /** FIN from the app: ACK it and half-close the upstream. */
    fun onFin(seq: Long) {
        synchronized(lock) {
            if (mask(seq) == theirSeq) theirSeq = mask(theirSeq + 1) // FIN consumes one
            sendFlags(PacketBuilder.TcpFlag.ACK)
        }
        Log.d(TAG, "[$tag] app FIN")
        runCatching { socks?.shutdownOutput() }
    }

    fun onRst() {
        Log.d(TAG, "[$tag] app RST")
        close()
    }

    private fun pumpUpstreamToTun(s: Socket) {
        val buf = ByteArray((mtu - 40).coerceAtLeast(536))
        val input = runCatching { s.getInputStream() }.getOrNull() ?: return
        var total = 0L
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                synchronized(lock) {
                    if (closed) return
                    writeToTun(
                        // buf is handed straight to the builder, which copies the
                        // bytes into the packet it allocates — slicing it here
                        // first would only add a second copy of every MSS.
                        PacketBuilder.ipv4Tcp(
                            dstIp, appIp, dstPort, appPort,
                            seq = ourSeq, ack = theirSeq,
                            flags = PacketBuilder.TcpFlag.PSH or PacketBuilder.TcpFlag.ACK,
                            payload = buf, payloadLength = n,
                        ),
                    )
                    ourSeq = mask(ourSeq + n)
                }
            }
            Log.i(TAG, "[$tag] upstream EOF after ${total}B → FIN")
            synchronized(lock) {
                if (!closed) {
                    sendFlags(PacketBuilder.TcpFlag.FIN or PacketBuilder.TcpFlag.ACK)
                    ourSeq = mask(ourSeq + 1)
                }
            }
        } catch (e: Exception) {
            // close() shuts this socket down, so the read losing it is the normal
            // way a flow ends once the app has gone away — logging a stack trace
            // at ERROR for every closed connection buried the real failures.
            if (closed) {
                Log.d(TAG, "[$tag] upstream closed after ${total}B")
            } else {
                Log.w(TAG, "[$tag] upstream read failed after ${total}B: ${e.message}")
                synchronized(lock) { sendFlags(PacketBuilder.TcpFlag.RST or PacketBuilder.TcpFlag.ACK) }
            }
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
    private fun sendFlags(flags: Int, seqOverride: Long? = null, mss: Int? = null) {
        writeToTun(
            PacketBuilder.ipv4Tcp(
                dstIp, appIp, dstPort, appPort,
                seq = seqOverride ?: ourSeq, ack = theirSeq, flags = flags,
                mss = mss,
            ),
        )
    }

    private fun mask(v: Long): Long = v and 0xFFFFFFFFL

    private companion object {
        const val TAG = Tun2Socks.TAG
        const val SOCKS_CONNECT_TIMEOUT_MS = 8_000
        const val SOCKS_HANDSHAKE_TIMEOUT_MS = 10_000
        const val MAX_DUPLICATE_ACKS = 3
    }
}
