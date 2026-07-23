package com.amneziaguard.core.netstack.packet

/**
 * Constructs IPv4 TCP/UDP packets for the tun return path (server → app). The
 * tun2socks engine uses this to synthesize SYN-ACK / ACK / data / FIN segments
 * and UDP replies with correct IP and transport checksums.
 *
 * IPv4 + 20-byte headers only (no IP or TCP options), which is all the engine
 * emits. Addresses are 4-byte big-endian.
 */
object PacketBuilder {

    object TcpFlag {
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10
    }

    fun ipv4Tcp(
        sourceIp: ByteArray,
        destIp: ByteArray,
        sourcePort: Int,
        destPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int = 65535,
        payload: ByteArray = EMPTY,
        // The relay hands us a slice of its read buffer; copying it out first
        // would allocate a second full-MSS array per downstream packet.
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size - payloadOffset,
        /**
         * Maximum segment size to advertise. Only meaningful on a SYN, and
         * omitting it is not neutral: RFC 1122 §4.2.2.6 makes a peer that never
         * saw an MSS option fall back to 536 bytes, so the app would send us
         * less than half an MTU per packet and we would pay for the difference
         * in packet rate on every byte it uploads.
         */
        mss: Int? = null,
    ): ByteArray {
        val optionBytes = if (mss != null) 4 else 0
        val headerLen = 20 + optionBytes
        val tcpLen = headerLen + payloadLength
        val packet = ByteArray(20 + tcpLen)
        writeIpv4Header(packet, sourceIp, destIp, IpProtocol.TCP, tcpLen)

        val t = 20
        putShort(packet, t, sourcePort)
        putShort(packet, t + 2, destPort)
        putInt(packet, t + 4, seq)
        putInt(packet, t + 8, ack)
        packet[t + 12] = ((headerLen / 4) shl 4).toByte()
        packet[t + 13] = flags.toByte()
        putShort(packet, t + 14, window)
        // checksum (t+16..17) left zero for computation
        putShort(packet, t + 18, 0) // urgent pointer
        if (mss != null) {
            packet[t + 20] = MSS_KIND
            packet[t + 21] = MSS_LENGTH
            putShort(packet, t + 22, mss)
        }
        payload.copyInto(packet, t + headerLen, payloadOffset, payloadOffset + payloadLength)

        val cs = Checksums.transport(sourceIp, destIp, IpProtocol.TCP, packet, t, tcpLen)
        putShort(packet, t + 16, cs)
        return packet
    }

    fun ipv4Udp(
        sourceIp: ByteArray,
        destIp: ByteArray,
        sourcePort: Int,
        destPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val packet = ByteArray(20 + 8 + payload.size)
        writeIpv4Header(packet, sourceIp, destIp, IpProtocol.UDP, 8 + payload.size)
        writeUdpDatagram(packet, 20, sourceIp, destIp, sourcePort, destPort, payload)
        return packet
    }

    /**
     * The app's reply as one IPv4/UDP packet, or several IP fragments when the
     * datagram exceeds [mtu].
     *
     * amneziawg-go receives datagrams sized to the server's path, not our tun's,
     * so origins routinely hand us more than the tun MTU — a QUIC reply of 1336B
     * over a 1280B tun, say. A router in this position fragments rather than
     * drops; the app's IP stack reassembles the fragments before the datagram
     * reaches the socket, so the app sees one whole datagram either way.
     */
    fun ipv4UdpFragments(
        sourceIp: ByteArray,
        destIp: ByteArray,
        sourcePort: Int,
        destPort: Int,
        payload: ByteArray,
        mtu: Int,
    ): List<ByteArray> {
        val udpLen = 8 + payload.size
        // The checksum covers the whole datagram, so build it once as the IP
        // payload and slice that; each fragment is just a range of these bytes.
        val datagram = ByteArray(udpLen)
        writeUdpDatagram(datagram, 0, sourceIp, destIp, sourcePort, destPort, payload)

        if (udpLen <= mtu - 20) {
            val packet = ByteArray(20 + udpLen)
            writeIpv4Header(packet, sourceIp, destIp, IpProtocol.UDP, udpLen)
            datagram.copyInto(packet, 20)
            return listOf(packet)
        }

        // A fragment's payload must be a multiple of 8 — the offset field counts
        // 8-byte units — except the last, which carries the remainder.
        val maxChunk = ((mtu - 20) / 8) * 8
        val id = nextIdentification()
        val fragments = ArrayList<ByteArray>((udpLen + maxChunk - 1) / maxChunk)
        var offset = 0
        while (offset < udpLen) {
            val chunk = minOf(maxChunk, udpLen - offset)
            val more = offset + chunk < udpLen
            val packet = ByteArray(20 + chunk)
            writeIpv4Header(
                packet, sourceIp, destIp, IpProtocol.UDP, chunk,
                identification = id,
                flagsAndOffset = (if (more) MORE_FRAGMENTS else 0) or (offset / 8),
            )
            datagram.copyInto(packet, 20, offset, offset + chunk)
            fragments += packet
            offset += chunk
        }
        return fragments
    }

    /** Writes an 8-byte UDP header + [payload] at [at], with the RFC 768 checksum. */
    private fun writeUdpDatagram(
        packet: ByteArray,
        at: Int,
        sourceIp: ByteArray,
        destIp: ByteArray,
        sourcePort: Int,
        destPort: Int,
        payload: ByteArray,
    ) {
        val udpLen = 8 + payload.size
        putShort(packet, at, sourcePort)
        putShort(packet, at + 2, destPort)
        putShort(packet, at + 4, udpLen)
        // checksum (at+6..7) left zero for computation
        payload.copyInto(packet, at + 8)
        var cs = Checksums.transport(sourceIp, destIp, IpProtocol.UDP, packet, at, udpLen)
        // A zero UDP checksum means "not computed"; RFC 768 maps that to 0xFFFF.
        if (cs == 0) cs = 0xFFFF
        putShort(packet, at + 6, cs)
    }

    private fun writeIpv4Header(
        packet: ByteArray,
        sourceIp: ByteArray,
        destIp: ByteArray,
        protocol: Int,
        transportLen: Int,
        identification: Int = 0,
        flagsAndOffset: Int = DONT_FRAGMENT,
    ) {
        val total = 20 + transportLen
        packet[0] = ((4 shl 4) or 5).toByte() // version 4, IHL 5
        packet[1] = 0 // DSCP/ECN
        putShort(packet, 2, total)
        putShort(packet, 4, identification)
        putShort(packet, 6, flagsAndOffset)
        packet[8] = 64 // TTL
        packet[9] = protocol.toByte()
        // checksum (10..11) zero for computation
        sourceIp.copyInto(packet, 12)
        destIp.copyInto(packet, 16)
        val cs = Checksums.ipv4Header(packet, 0, 5)
        putShort(packet, 10, cs)
    }

    /** Fragments of one datagram share an id, so the peer groups them for reassembly. */
    private val identification = java.util.concurrent.atomic.AtomicInteger()
    private fun nextIdentification(): Int = identification.incrementAndGet() and 0xFFFF

    private fun putShort(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 8).toByte()
        b[o + 1] = v.toByte()
    }

    private fun putInt(b: ByteArray, o: Int, v: Long) {
        b[o] = (v shr 24).toByte()
        b[o + 1] = (v shr 16).toByte()
        b[o + 2] = (v shr 8).toByte()
        b[o + 3] = v.toByte()
    }

    private const val MSS_KIND: Byte = 2
    private const val MSS_LENGTH: Byte = 4

    // The 3-bit flags + 13-bit fragment offset that share bytes 6-7 of the header.
    private const val DONT_FRAGMENT = 0x4000
    private const val MORE_FRAGMENTS = 0x2000

    private val EMPTY = ByteArray(0)
}
