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
        val udpLen = 8 + payload.size
        val packet = ByteArray(20 + udpLen)
        writeIpv4Header(packet, sourceIp, destIp, IpProtocol.UDP, udpLen)

        val u = 20
        putShort(packet, u, sourcePort)
        putShort(packet, u + 2, destPort)
        putShort(packet, u + 4, udpLen)
        // checksum (u+6..7) zero for computation
        payload.copyInto(packet, u + 8)

        var cs = Checksums.transport(sourceIp, destIp, IpProtocol.UDP, packet, u, udpLen)
        // A zero UDP checksum means "not computed"; RFC 768 maps that to 0xFFFF.
        if (cs == 0) cs = 0xFFFF
        putShort(packet, u + 6, cs)
        return packet
    }

    private fun writeIpv4Header(
        packet: ByteArray,
        sourceIp: ByteArray,
        destIp: ByteArray,
        protocol: Int,
        transportLen: Int,
    ) {
        val total = 20 + transportLen
        packet[0] = ((4 shl 4) or 5).toByte() // version 4, IHL 5
        packet[1] = 0 // DSCP/ECN
        putShort(packet, 2, total)
        putShort(packet, 4, 0) // identification
        putShort(packet, 6, 0x4000) // don't fragment
        packet[8] = 64 // TTL
        packet[9] = protocol.toByte()
        // checksum (10..11) zero for computation
        sourceIp.copyInto(packet, 12)
        destIp.copyInto(packet, 16)
        val cs = Checksums.ipv4Header(packet, 0, 5)
        putShort(packet, 10, cs)
    }

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

    private val EMPTY = ByteArray(0)
}
