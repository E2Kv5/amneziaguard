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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    /** One line every [STATS_INTERVAL_MS]; separate from [log] so per-flow noise can stay off. */
    private val stats: (String) -> Unit = {},
) {
    private val connections = ConcurrentHashMap<FlowKey, TcpConnection>()
    private val dnsRelay = DnsOverTcpRelay(socksPort, credentials, ::writeToTun)
    private val udpRelay = Socks5UdpRelay(socksPort, credentials, mtu, ::writeToTun)
    @Volatile private var running = false
    private var reader: Thread? = null
    private var statsReporter: Thread? = null

    // Summed across every writing thread, so dividing by wall time gives the
    // average number of threads inside a tun write at once — the datapath's
    // write concurrency. Below 1 means the writes are not the ceiling.
    private val writeNanos = AtomicLong()

    private val packetsRead = AtomicInteger()
    private val packetsWritten = AtomicInteger()
    private val droppedOther = AtomicInteger()

    // Counted at the tun boundary, so "download" is what we hand to the apps and
    // "upload" is what they hand us — which is what a speed readout should show.
    private val downloaded = AtomicLong()
    private val uploaded = AtomicLong()

    val downloadedBytes: Long get() = downloaded.get()
    val uploadedBytes: Long get() = uploaded.get()

    fun start() {
        running = true
        reader = thread(name = "tun2socks", isDaemon = true) { readLoop() }
        statsReporter = thread(name = "tun2socks-stats", isDaemon = true) { statsLoop() }
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
        statsReporter?.interrupt()
        Log.i(TAG, "engine stopped (read=${packetsRead.get()} wrote=${packetsWritten.get()} otherDropped=${droppedOther.get()})")
        log("Engine stopped: read ${packetsRead.get()} pkt, wrote ${packetsWritten.get()} pkt, non-TCP dropped ${droppedOther.get()}")
    }

    /**
     * Writes one packet to the tun, from whichever thread produced it.
     *
     * There is deliberately no lock. A write to a tun fd is atomic per packet —
     * the driver takes the whole packet or fails — and FileOutputStream issues
     * exactly one write syscall for it, so there is no interleaving to prevent.
     * Serialising here bought nothing and cost a great deal: with a connection
     * per Ookla stream the engine measured twenty threads queued on this monitor
     * at once, and the handoffs (each a futex wake and a context switch, often
     * preempting the lock holder mid-write) dominated the download path.
     */
    private fun writeToTun(packet: ByteArray) {
        val started = System.nanoTime()
        try {
            tunOut.write(packet)
            packetsWritten.incrementAndGet()
            downloaded.addAndGet(packet.size.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "tun write failed (${packet.size}B)", e)
            log("tun write failed: ${e.message}")
        }
        writeNanos.addAndGet(System.nanoTime() - started)
    }

    /**
     * Reports what the datapath actually did over the last interval.
     *
     * `tun write` is time summed over all writing threads divided by wall time,
     * so it reads as concurrency rather than utilisation: 300% means three
     * threads were writing on average. `us/wr` is the mean cost of one write,
     * which is the number to watch — if it stays near a syscall's cost the
     * writes are healthy, and if it inflates the threads are fighting for CPU.
     */
    private fun statsLoop() {
        var atNanos = System.nanoTime()
        var down = downloaded.get()
        var up = uploaded.get()
        var wrote = packetsWritten.get().toLong()
        var read = packetsRead.get().toLong()
        var busy = writeNanos.get()

        while (running) {
            try {
                Thread.sleep(STATS_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            val now = System.nanoTime()
            val elapsed = now - atNanos
            if (elapsed <= 0) continue
            val nowDown = downloaded.get()
            val nowUp = uploaded.get()
            val nowWrote = packetsWritten.get().toLong()
            val nowRead = packetsRead.get().toLong()
            val nowBusy = writeNanos.get()

            val secs = elapsed / 1e9
            val writes = nowWrote - wrote
            val perWrite = if (writes > 0) (nowBusy - busy) / writes / 1_000 else 0
            val line = "↓${mbit(nowDown - down, secs)} ↑${mbit(nowUp - up, secs)} Mbit/s | " +
                "pkt ↓${perSec(writes, secs)} ↑${perSec(nowRead - read, secs)} /s | " +
                "tun write ${share(nowBusy - busy, elapsed)}, ${perWrite}us/wr | " +
                "conns ${connections.size}"
            Log.i(TAG, line)
            stats(line)

            atNanos = now
            down = nowDown; up = nowUp
            wrote = nowWrote; read = nowRead
            busy = nowBusy
        }
    }

    private fun mbit(bytes: Long, secs: Double): String =
        String.format(Locale.US, "%.1f", bytes * 8 / secs / 1e6)

    private fun perSec(count: Long, secs: Double): String = (count / secs).toInt().toString()

    private fun share(part: Long, whole: Long): String = "${part * 100 / whole}%"

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
            uploaded.addAndGet(n.toLong())
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
        private const val STATS_INTERVAL_MS = 2_000L
        private const val DNS_PORT = 53
        private const val UDP_HEADER = 8
        private val EMPTY = ByteArray(0)

        fun ip(addr: ByteArray): String =
            if (addr.size == 4) addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
            else addr.joinToString("") { "%02x".format(it) }
    }
}
