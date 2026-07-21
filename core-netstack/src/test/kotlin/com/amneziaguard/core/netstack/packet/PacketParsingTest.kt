package com.amneziaguard.core.netstack.packet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketParsingTest {

    /** Builds a minimal IPv4+TCP packet with a valid header checksum. */
    private fun ipv4Tcp(
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
        flags: Int = 0x02, // SYN
    ): ByteArray {
        val ihl = 5
        val tcpLen = 20
        val total = ihl * 4 + tcpLen
        val p = ByteArray(total)
        p[0] = ((4 shl 4) or ihl).toByte()
        p[2] = (total shr 8).toByte()
        p[3] = total.toByte()
        p[9] = IpProtocol.TCP.toByte()
        src.copyInto(p, 12)
        dst.copyInto(p, 16)
        val cs = Checksums.ipv4Header(p, 0, ihl)
        p[10] = (cs shr 8).toByte()
        p[11] = cs.toByte()
        // TCP header
        val t = ihl * 4
        p[t] = (srcPort shr 8).toByte(); p[t + 1] = srcPort.toByte()
        p[t + 2] = (dstPort shr 8).toByte(); p[t + 3] = dstPort.toByte()
        p[t + 12] = (5 shl 4).toByte() // data offset 5 words
        p[t + 13] = flags.toByte()
        return p
    }

    @Test
    fun `parses ipv4 tcp header fields`() {
        val src = byteArrayOf(10, 0, 0, 2)
        val dst = byteArrayOf(1, 1, 1, 1)
        val packet = IpPacket.parse(ipv4Tcp(src, dst, 40000, 443))!!

        assertEquals(4, packet.version)
        assertEquals(IpProtocol.TCP, packet.protocol)
        assertArrayEquals(src, packet.sourceIp)
        assertArrayEquals(dst, packet.destIp)
        assertEquals(40000, packet.sourcePort)
        assertEquals(443, packet.destPort)
        assertEquals(20, packet.headerLength)
    }

    @Test
    fun `flow key round-trips protocol and endpoints`() {
        val packet = IpPacket.parse(ipv4Tcp(byteArrayOf(10, 0, 0, 2), byteArrayOf(8, 8, 8, 8), 1234, 53))!!
        val key = packet.flowKey()
        assertEquals(IpProtocol.TCP, key.protocol)
        assertEquals(1234, key.sourcePort)
        assertEquals(53, key.destPort)
        assertArrayEquals(byteArrayOf(8, 8, 8, 8), key.destIp)
    }

    @Test
    fun `tcp flags decode syn and ack`() {
        val packet = IpPacket.parse(ipv4Tcp(byteArrayOf(10, 0, 0, 2), byteArrayOf(1, 1, 1, 1), 1, 2, flags = 0x12))!!
        val f = TcpFlags(packet.data, packet.transportOffset)
        assertTrue(f.syn)
        assertTrue(f.ack)
        assertTrue(!f.fin)
    }

    @Test
    fun `valid ipv4 header checksum verifies to zero`() {
        val p = ipv4Tcp(byteArrayOf(192.toByte(), 168.toByte(), 0, 5), byteArrayOf(9, 9, 9, 9), 5555, 80)
        // Recomputing over the header including the stored checksum yields 0.
        var sum = 0L
        var i = 0
        while (i < 20) {
            sum += ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        assertEquals(0xFFFF, sum.toInt())
    }

    @Test
    fun `parses ipv6 udp header`() {
        val p = ByteArray(40 + 8)
        p[0] = 0x60 // version 6
        p[4] = 0; p[5] = 8 // payload length
        p[6] = IpProtocol.UDP.toByte()
        // src ::1
        p[8 + 15] = 1
        // dst ::2
        p[24 + 15] = 2
        val t = 40
        p[t] = 0x30; p[t + 1] = 0x39 // src port 12345
        p[t + 2] = 0; p[t + 3] = 53 // dst port 53
        val packet = IpPacket.parse(p)!!
        assertEquals(6, packet.version)
        assertEquals(IpProtocol.UDP, packet.protocol)
        assertEquals(12345, packet.sourcePort)
        assertEquals(53, packet.destPort)
    }

    @Test
    fun `rejects truncated and unknown-version packets`() {
        assertNull(IpPacket.parse(ByteArray(0)))
        assertNull(IpPacket.parse(byteArrayOf(0x40))) // v4 but too short
        assertNull(IpPacket.parse(byteArrayOf(0x70, 0, 0, 0))) // version 7
    }
}
