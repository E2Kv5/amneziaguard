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
    fun `syn-ack advertises the mss option`() {
        val packet = PacketBuilder.ipv4Tcp(
            src, dst, 443, 51000, seq = 0, ack = 1,
            flags = PacketBuilder.TcpFlag.SYN or PacketBuilder.TcpFlag.ACK,
            mss = 1240,
        )
        val parsed = IpPacket.parse(packet)!!
        val t = parsed.transportOffset
        val f = TcpFlags(parsed.data, t)
        // Data offset must grow to 6 words, or the peer never sees the option.
        assertEquals(24, f.dataOffsetBytes)
        assertEquals(2, packet[t + 20].toInt()) // kind = MSS
        assertEquals(4, packet[t + 21].toInt()) // length
        assertEquals(1240, ((packet[t + 22].toInt() and 0xFF) shl 8) or (packet[t + 23].toInt() and 0xFF))
        assertEquals(0xFFFF, foldOnesComplement(packet, 0, 20))
        assertEquals(0, Checksums.transport(src, dst, IpProtocol.TCP, packet, 20, packet.size - 20))
    }

    @Test
    fun `mss option and payload coexist without overlapping`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val packet = PacketBuilder.ipv4Tcp(
            src, dst, 443, 51000, seq = 0, ack = 1,
            flags = PacketBuilder.TcpFlag.ACK, payload = payload, mss = 1240,
        )
        val parsed = IpPacket.parse(packet)!!
        val t = parsed.transportOffset
        assertEquals(24, TcpFlags(parsed.data, t).dataOffsetBytes)
        // Payload must start after the option, not on top of it.
        assertArrayEquals(payload, packet.copyOfRange(t + 24, t + 28))
        assertEquals(48, parsed.totalLength) // 20 IP + 24 TCP + 4 payload
    }

    @Test
    fun `segment without mss keeps the 20-byte header`() {
        val packet = PacketBuilder.ipv4Tcp(
            src, dst, 443, 51000, seq = 0, ack = 1, flags = PacketBuilder.TcpFlag.ACK,
        )
        val parsed = IpPacket.parse(packet)!!
        assertEquals(20, TcpFlags(parsed.data, parsed.transportOffset).dataOffsetBytes)
        assertEquals(40, parsed.totalLength)
    }

    @Test
    fun `udp datagram that fits the mtu is a single unfragmented packet`() {
        val payload = ByteArray(1000) { it.toByte() }
        val fragments = PacketBuilder.ipv4UdpFragments(src, dst, 53, 40000, payload, mtu = 1280)
        assertEquals(1, fragments.size)
        val parsed = IpPacket.parse(fragments[0])!!
        assertEquals(IpProtocol.UDP, parsed.protocol)
        assertEquals(53, parsed.sourcePort)
        assertEquals(40000, parsed.destPort)
        // More-fragments clear and offset zero: nothing to reassemble.
        assertEquals(0, fragmentFlagsAndOffset(fragments[0]) and 0x3FFF)
        assertEquals(0xFFFF, foldOnesComplement(fragments[0], 0, 20))
        assertEquals(0, Checksums.transport(src, dst, IpProtocol.UDP, fragments[0], 20, parsed.totalLength - 20))
    }

    @Test
    fun `oversized udp datagram fragments and reassembles to the original`() {
        val payload = ByteArray(3000) { (it * 7).toByte() }
        val mtu = 1280
        val fragments = PacketBuilder.ipv4UdpFragments(src, dst, 443, 51000, payload, mtu)
        assertTrue("expected multiple fragments", fragments.size > 1)

        val id = identification(fragments[0])
        var expectedOffset = 0
        val reassembled = ArrayList<Byte>()
        fragments.forEachIndexed { i, frag ->
            assertTrue("fragment ${frag.size}B exceeds mtu", frag.size <= mtu)
            assertEquals("all fragments share one id", id, identification(frag))
            val flags = fragmentFlagsAndOffset(frag)
            assertEquals("offset in 8-byte units", expectedOffset / 8, flags and 0x1FFF)
            val last = i == fragments.lastIndex
            assertEquals("more-fragments set on all but last", if (last) 0 else 0x2000, flags and 0x2000)
            // Non-last fragments must carry a multiple of 8 payload bytes.
            val ipPayload = frag.copyOfRange(20, frag.size)
            if (!last) assertEquals(0, ipPayload.size % 8)
            reassembled.addAll(ipPayload.toList())
            expectedOffset += ipPayload.size
            assertEquals(0xFFFF, foldOnesComplement(frag, 0, 20)) // each IP header valid
        }

        // The reassembled IP payload is the whole UDP datagram: header + payload.
        val datagram = reassembled.toByteArray()
        assertEquals(8 + payload.size, datagram.size)
        assertEquals(443, ((datagram[0].toInt() and 0xFF) shl 8) or (datagram[1].toInt() and 0xFF))
        assertEquals(51000, ((datagram[2].toInt() and 0xFF) shl 8) or (datagram[3].toInt() and 0xFF))
        assertArrayEquals(payload, datagram.copyOfRange(8, datagram.size))
        // Checksum over the reassembled datagram verifies against the pseudo-header.
        assertEquals(0, Checksums.transport(src, dst, IpProtocol.UDP, datagram, 0, datagram.size))
    }

    private fun identification(packet: ByteArray): Int =
        ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)

    private fun fragmentFlagsAndOffset(packet: ByteArray): Int =
        ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)

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
