package com.amneziaguard.core.netstack

import com.amneziaguard.core.netstack.packet.FlowKey
import com.amneziaguard.core.netstack.packet.IpPacket
import com.amneziaguard.core.netstack.packet.IpProtocol
import com.amneziaguard.core.netstack.packet.PacketBuilder
import com.amneziaguard.core.netstack.packet.TcpFlags
import com.amneziaguard.core.netstack.socks.Socks5Client
import com.amneziaguard.core.netstack.tcp.TcpConnection
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Userspace tun → SOCKS5 relay. Reads IP packets from the VpnService tun, asks
 * [policy] what to do with each new flow (by [FlowKey]), and relays RELAY flows
 * through the local SOCKS5 (amneziawg-go). DROP flows are refused (TCP RST).
 *
 * First cut: IPv4 TCP. IPv6 and UDP/DNS are handled in a later milestone; for
 * now they are dropped. The heavy TCP logic lives in [TcpConnection].
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
    private val writeLock = Any()
    @Volatile private var running = false
    private var reader: Thread? = null

    fun start() {
        running = true
        reader = thread(name = "tun2socks", isDaemon = true) { readLoop() }
    }

    fun stop() {
        running = false
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        reader?.interrupt()
    }

    private fun writeToTun(packet: ByteArray) {
        synchronized(writeLock) {
            runCatching {
                tunOut.write(packet)
                tunOut.flush()
            }
        }
    }

    private fun readLoop() {
        val buf = ByteArray(mtu + 200)
        while (running) {
            val n = runCatching { tunIn.read(buf) }.getOrDefault(-1)
            if (n <= 0) break
            val packet = IpPacket.parse(buf, n) ?: continue
            if (packet.version != 4) continue // IPv4 only for now
            when (packet.protocol) {
                IpProtocol.TCP -> handleTcp(packet)
                else -> Unit // UDP/DNS + IPv6 in a later milestone
            }
        }
    }

    private fun handleTcp(packet: IpPacket) {
        val key = packet.flowKey()
        val flags = TcpFlags(packet.data, packet.transportOffset)
        val existing = connections[key]

        if (flags.syn && existing == null) {
            when (policy(key)) {
                RelayPolicy.DROP -> writeToTun(rstFor(key))
                RelayPolicy.RELAY -> {
                    val conn = TcpConnection(
                        key = key,
                        socksPort = socksPort,
                        credentials = credentials,
                        mtu = mtu,
                        writeToTun = ::writeToTun,
                        onClosed = { connections.remove(it) },
                    )
                    connections[key] = conn
                    conn.onSyn(flags.sequenceNumber)
                }
            }
            return
        }

        val conn = existing ?: return
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

    /** RST+ACK for a refused SYN (server→app), acking the SYN. */
    private fun rstFor(key: FlowKey): ByteArray =
        PacketBuilder.ipv4Tcp(
            sourceIp = key.destIp, destIp = key.sourceIp,
            sourcePort = key.destPort, destPort = key.sourcePort,
            seq = 0, ack = 1, flags = PacketBuilder.TcpFlag.RST or PacketBuilder.TcpFlag.ACK,
        )

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
