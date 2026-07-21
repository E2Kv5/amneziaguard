package com.amneziaguard.core.netstack.packet

import org.junit.Assert.assertEquals
import org.junit.Test

class ChecksumsTest {

    @Test
    fun `internet checksum matches RFC 1071 worked example`() {
        // Classic RFC 1071 example: the 16-bit words 0x0001 0xf203 0xf4f5 0xf6f7
        // sum to a checksum of 0x220d.
        val data = byteArrayOf(
            0x00, 0x01, 0xf2.toByte(), 0x03,
            0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(),
        )
        assertEquals(0x220d, Checksums.internetChecksum(data, 0, data.size))
    }

    @Test
    fun `odd length pads the final byte as high order`() {
        val even = Checksums.internetChecksum(byteArrayOf(0x12, 0x00), 0, 2)
        val odd = Checksums.internetChecksum(byteArrayOf(0x12), 0, 1)
        assertEquals(even, odd)
    }

    @Test
    fun `checksum plus data verifies to zero`() {
        val data = byteArrayOf(0x45, 0x00, 0x12, 0x34, 0xab.toByte(), 0xcd.toByte())
        val cs = Checksums.internetChecksum(data, 0, data.size)
        // Appending the checksum and re-summing must fold to 0xffff (i.e. ~0).
        val withCs = data + byteArrayOf((cs shr 8).toByte(), cs.toByte())
        var sum = 0L
        var i = 0
        while (i < withCs.size) {
            sum += ((withCs[i].toInt() and 0xFF) shl 8) or (withCs[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        assertEquals(0xFFFF, sum.toInt())
    }
}
