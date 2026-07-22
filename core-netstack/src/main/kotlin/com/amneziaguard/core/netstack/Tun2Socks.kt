package com.amneziaguard.core.netstack

import android.util.Log
import com.amneziaguard.core.netstack.dns.DnsOverTcpRelay
import com.amneziaguard.core.netstack.packet.FlowKey
import com.amneziaguard.core.netstack.packet.IpPacket
import com.amneziaguard.core.netstack.packet.IpProtocol
import com.amneziaguard.core.netstack.packet.PacketBuilder
import com.amneziaguard.core.netstack.packet.TcpFlags
import com.amneziaguard.core.netstack.socks.Socks5Client
import com.amneziaguard.core.netstack.tcp.TcpConnection
import com.amneziaguard.core.netstack.udp.Socks5UdpRelay
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Userspace tun → SOCKS5 relay. Reads IP packets from the VpnService tun, asks
 * [policy] what to do with each new flow (by [FlowKey]), and relays RELAY flows
 * through the local SOCKS5 (amneziawg-go). DROP flows are refused (TCP RST).
 *
 * First cut: IPv4 TCP. IPv6 and UDP/DNS are handled in a later milestone; for
 * now they are counted and dropped.
 *
 * Everything is traced to logcat under [TAG] — the datapath can only be
 * debugged from a device, so silence here is expensive.
 */
class Tun2Socks(
    private val tunIn: InputStream,
    private val tunOut: OutputStream,
    private val socksPort: Int,
    private val credentials: Socks5Client.Credentials,
    private val mtu: Int,
    private val policy: (FlowKey) -> RelayPolicy,
    private val log: (String) -> Unit = {},
) {
    private val connections = ConcurrentHashMap<FlowKey, TcpConnection>()
    private val dnsRelay = DnsOverTcpRelay(socksPort, credentials, ::writeToTun)
    private val udpRelay = Socks5UdpRelay(socksPort, credentials, mtu, ::writeToTun)
    private val writeLock = Any()
    @Volatile private var running = false
    private var reader: Thread? = null

    private val packetsRead = AtomicInteger()
    private val packetsWritten = AtomicInteger()
    private val droppedOther = AtomicInteger()

    fun start() {
        running = true
        reader = thread(name = "tun2socks", isDaemon = true) { readLoop() }
        Log.i(TAG, "engine started (socksPort=$socksPort, mtu=$mtu)")
        log("Engine started (SOCKS5 127.0.0.1:$socksPort)")
    }

    fun stop() {
        running = false
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        dnsRelay.stop()
        udpRelay.stop()
        reader?.interrupt()
        Log.i(TAG, "engine stopped (read=${packetsRead.get()} wrote=${packetsWritten.get()} otherDropped=${droppedOther.get()})")
        log("Engine stopped: read ${packetsRead.get()} pkt, wrote ${packetsWritten.get()} pkt, non-TCP dropped ${droppedOther.get()}")
    }

    private fun writeToTun(packet: ByteArray) {
        synchronized(writeLock) {
            try {
                tunOut.write(packet)
                tunOut.flush()
                packetsWritten.incrementAndGet()
            } catch (e: Exception) {
                Log.e(TAG, "tun write failed (${packet.size}B)", e)
                log("tun write failed: ${e.message}")
            }
        }
    }

    private fun readLoop() {
        val buf = ByteArray(mtu + 200)
        Log.i(TAG, "read loop entered")
        var idleReads = 0
        while (running) {
            val n = try {
                tunIn.read(buf)
            } catch (e: Exception) {
                Log.e(TAG, "tun read failed", e)
                log("tun read failed: ${e.message}")
                break
            }
            if (n == 0) {
                // Zero means "no packet available right now" — the tun fd can be
                // non-blocking. It is NOT end-of-stream; treating it as such kills
                // the datapath after the very first idle moment.
                idleReads++
                try {
                    Thread.sleep(IDLE_BACKOFF_MS)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            if (n < 0) {
                Log.w(TAG, "tun read returned $n — end of stream, leaving read loop")
                log("tun closed — read loop ended")
                break
            }
            idleReads = 0
            val count = packetsRead.incrementAndGet()
            val packet = IpPacket.parse(buf, n)
            if (packet == null) {
                if (count <= LOG_FIRST) Log.w(TAG, "unparseable packet, $n bytes")
                continue
            }
            if (count <= LOG_FIRST) {
                Log.d(TAG, "rx#$count v${packet.version} proto=${packet.protocol} " +
                    "${ip(packet.sourceIp)}:${packet.sourcePort} → ${ip(packet.destIp)}:${packet.destPort} ${n}B")
            }
            if (packet.version != 4) { droppedOther.incrementAndGet(); continue }
            when (packet.protocol) {
                IpProtocol.TCP -> handleTcp(packet)
                IpProtocol.UDP -> handleUdp(packet)
                else -> droppedOther.incrementAndGet()
            }
        }
        Log.i(TAG, "read loop exited (read=${packetsRead.get()}, idleReads=$idleReads, running=$running)")
        if (running) log("WARNING: read loop ended while the engine was still running")
    }

    private fun handleTcp(packet: IpPacket) {
        val key = packet.flowKey()
        val flags = TcpFlags(packet.data, packet.transportOffset)
        val existing = connections[key]

        if (flags.syn && existing == null) {
            val decision = policy(key)
            Log.i(TAG, "SYN ${ip(key.sourceIp)}:${key.sourcePort} → ${ip(key.destIp)}:${key.destPort} → $decision")
            log("SYN → ${ip(key.destIp)}:${key.destPort} ($decision)")
            when (decision) {
                RelayPolicy.DROP -> writeToTun(rstFor(key, flags.sequenceNumber))
                RelayPolicy.RELAY -> {
                    val conn = TcpConnection(
                        key = key,
                        socksPort = socksPort,
                        credentials = credentials,
                        mtu = mtu,
                        writeToTun = ::writeToTun,
                        onClosed = { connections.remove(it) },
                        log = log,
                    )
                    connections[key] = conn
                    conn.onSyn(flags.sequenceNumber)
                }
            }
            return
        }

        val conn = existing
        if (conn == null) {
            // A flow we never saw open — e.g. a socket whose SYN escaped before
            // the VPN's routes took effect, or leftovers after teardown. Refuse
            // it so the app fails fast instead of retransmitting until timeout.
            if (!flags.rst) {
                Log.d(TAG, "unknown flow ${ip(key.sourceIp)}:${key.sourcePort} → " +
                    "${ip(key.destIp)}:${key.destPort} → RST")
                writeToTun(rstForSegment(key, flags))
            }
            return
        }

        if (flags.syn) {
            // Retransmitted SYN — the app didn't see our SYN-ACK yet.
            conn.onSynRetransmit()
            return
        }

        val payloadOffset = packet.transportOffset + flags.dataOffsetBytes
        val payloadLen = packet.totalLength - payloadOffset
        val payload = if (payloadLen > 0) {
            packet.data.copyOfRange(payloadOffset, payloadOffset + payloadLen)
        } else {
            EMPTY
        }

        when {
            flags.rst -> conn.onRst()
            flags.fin -> {
                if (payload.isNotEmpty()) conn.onData(flags.sequenceNumber, payload)
                conn.onFin(flags.sequenceNumber + payload.size)
            }
            payload.isNotEmpty() -> conn.onData(flags.sequenceNumber, payload)
            // bare ACK: nothing to do
        }
    }

    /**
     * UDP goes through a SOCKS5 UDP association, which amneziawg-go dials with
     * the WireGuard netstack — so QUIC, games and plain DNS all traverse the
     * tunnel. If an association can't be set up, DNS still gets through via the
     * DNS-over-TCP fallback rather than leaving the app with no resolver.
     */
    private fun handleUdp(packet: IpPacket) {
        val key = packet.flowKey()
        if (policy(key) == RelayPolicy.DROP) {
            droppedOther.incrementAndGet()
            return
        }
        val payloadOffset = packet.transportOffset + UDP_HEADER
        val payloadLen = packet.totalLength - payloadOffset
        if (payloadLen <= 0) return
        val payload = packet.data.copyOfRange(payloadOffset, payloadOffset + payloadLen)

        if (udpRelay.send(key, payload)) return
        if (key.destPort == DNS_PORT) {
            dnsRelay.resolve(key, payload)
        } else {
            droppedOther.incrementAndGet()
        }
    }

    /** RST+ACK refusing a SYN (server→app), acknowledging the SYN's sequence. */
    private fun rstFor(key: FlowKey, seq: Long): ByteArray =
        PacketBuilder.ipv4Tcp(
            sourceIp = key.destIp, destIp = key.sourceIp,
            sourcePort = key.destPort, destPort = key.sourcePort,
            seq = 0, ack = (seq + 1) and 0xFFFFFFFFL,
            flags = PacketBuilder.TcpFlag.RST or PacketBuilder.TcpFlag.ACK,
        )

    /**
     * RST for a segment of an unknown, already-open flow. RFC 793: when the
     * offending segment carries an ACK the reset takes its sequence number from
     * that ACK — a fixed seq=0 lands outside the peer's window and modern
     * stacks (RFC 5961) simply ignore it, so the flow would hang instead.
     */
    private fun rstForSegment(key: FlowKey, flags: TcpFlags): ByteArray =
        if (flags.ack) {
            PacketBuilder.ipv4Tcp(
                sourceIp = key.destIp, destIp = key.sourceIp,
                sourcePort = key.destPort, destPort = key.sourcePort,
                seq = flags.acknowledgementNumber, ack = 0,
                flags = PacketBuilder.TcpFlag.RST,
            )
        } else {
            rstFor(key, flags.sequenceNumber)
        }

    companion object {
        const val TAG = "AGEngine"
        private const val LOG_FIRST = 40
        private const val IDLE_BACKOFF_MS = 1L
        private const val DNS_PORT = 53
        private const val UDP_HEADER = 8
        private val EMPTY = ByteArray(0)

        fun ip(addr: ByteArray): String =
            if (addr.size == 4) addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
            else addr.joinToString("") { "%02x".format(it) }
    }
}
