package com.amneziaguard.core.netstack.packet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketBuilderTest {

    private val src = byteArrayOf(1, 1, 1, 1)
    private val dst = byteArrayOf(10, 0, 0, 2)

    private fun foldOnesComplement(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.toInt()
    }

    @Test
    fun `built tcp packet parses back with same fields`() {
        val packet = PacketBuilder.ipv4Tcp(
            sourceIp = src, destIp = dst,
            sourcePort = 443, destPort = 51000,
            seq = 1000, ack = 2000,
            flags = PacketBuilder.TcpFlag.SYN or PacketBuilder.TcpFlag.ACK,
            payload = byteArrayOf(9, 8, 7),
        )
        val parsed = IpPacket.parse(packet)!!
        assertEquals(4, parsed.version)
        assertEquals(IpProtocol.TCP, parsed.protocol)
        assertArrayEquals(src, parsed.sourceIp)
        assertArrayEquals(dst, parsed.destIp)
        assertEquals(443, parsed.sourcePort)
        assertEquals(51000, parsed.destPort)

        val f = TcpFlags(parsed.data, parsed.transportOffset)
        assertTrue(f.syn)
        assertTrue(f.ack)
        assertEquals(1000L, f.sequenceNumber)
        assertEquals(2000L, f.acknowledgementNumber)
    }

    @Test
    fun `built tcp ip and transport checksums are valid`() {
        val packet = PacketBuilder.ipv4Tcp(
            src, dst, 443, 51000, seq = 5, ack = 6,
            flags = PacketBuilder.TcpFlag.ACK, payload = byteArrayOf(1, 2, 3, 4, 5),
        )
        // IP header checksum folds to 0xFFFF.
        assertEquals(0xFFFF, foldOnesComplement(packet, 0, 20))
        // TCP checksum with pseudo-header folds to 0xFFFF.
        var sum = 0L
        for (b in src) sum += (b.toInt() and 0xFF).toLong()
        // Recompute via the pseudo-header the same way Checksums.transport does:
        val recomputed = com.amneziaguard.core.netstack.packet.Checksums.transport(
            src, dst, IpProtocol.TCP, packet, 20, packet.size - 20,
        )
        assertEquals(0, recomputed) // stored checksum makes it verify to 0
    }

    @Test
    fun `built udp packet round-trips and has valid checksum`() {
        val payload = "hello".toByteArray()
        val packet = PacketBuilder.ipv4Udp(src, dst, 53, 40000, payload)
        val parsed = IpPacket.parse(packet)!!
        assertEquals(IpProtocol.UDP, parsed.protocol)
        assertEquals(53, parsed.sourcePort)
        assertEquals(40000, parsed.destPort)
        assertEquals(0xFFFF, foldOnesComplement(packet, 0, 20))

        val verify = Checksums.transport(src, dst, IpProtocol.UDP, packet, 20, packet.size - 20)
        assertEquals(0, verify)
    }

    @Test
    fun `rst segment has no payload and correct flag`() {
        val packet = PacketBuilder.ipv4Tcp(
            src, dst, 80, 12345, seq = 0, ack = 1,
            flags = PacketBuilder.TcpFlag.RST or PacketBuilder.TcpFlag.ACK,
        )
        val parsed = IpPacket.parse(packet)!!
        assertEquals(40, parsed.totalLength) // 20 IP + 20 TCP, no payload
        val f = TcpFlags(parsed.data, parsed.transportOffset)
        assertTrue(f.rst)
        assertTrue(f.ack)
    }
}
