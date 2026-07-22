package com.amneziaguard.core.netstack.packet

/**
 * The 16-bit one's-complement Internet checksum (RFC 1071) used by IPv4, TCP
 * and UDP. Kept pure so it is unit-testable on the JVM.
 */
object Checksums {

    /** Raw one's-complement sum over [length] bytes of [data] from [offset]. */
    fun internetChecksum(data: ByteArray, offset: Int, length: Int): Int =
        fold(sumWords(data, offset, length))

    /**
     * Unfolded one's-complement sum of the 16-bit words in [data].
     *
     * Consumes eight bytes per iteration rather than two: this runs over every
     * payload byte the engine emits, so it is the datapath's hottest arithmetic.
     * Adding wider chunks is safe because folding at the end is what reduces the
     * sum mod 0xFFFF — a 64-bit accumulator cannot overflow for any buffer we
     * could hold in memory (2^31 words of 2^32 still fits in 2^63).
     */
    private fun sumWords(data: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var i = offset
        val end = offset + length
        val wide = end - 7
        while (i < wide) {
            val a = ((data[i].toInt() and 0xFF) shl 24) or ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            val b = ((data[i + 4].toInt() and 0xFF) shl 24) or ((data[i + 5].toInt() and 0xFF) shl 16) or
                ((data[i + 6].toInt() and 0xFF) shl 8) or (data[i + 7].toInt() and 0xFF)
            sum += (a.toLong() and 0xFFFFFFFFL) + (b.toLong() and 0xFFFFFFFFL)
            i += 8
        }
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            // Odd trailing byte is treated as the high byte of a 16-bit word.
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        return sum
    }

    /** IPv4 header checksum over the [ihl]*4 header bytes at [offset]. */
    fun ipv4Header(data: ByteArray, offset: Int, ihl: Int): Int {
        var sum = 0L
        val headerLen = ihl * 4
        var i = offset
        val end = offset + headerLen
        while (i + 1 < end) {
            // Skip the checksum field itself (bytes 10-11 of the header).
            if (i - offset != 10) {
                sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            }
            i += 2
        }
        return fold(sum)
    }

    /**
     * TCP/UDP checksum: pseudo-header (src, dst, protocol, transport length) +
     * the transport header and payload.
     */
    fun transport(
        sourceIp: ByteArray,
        destIp: ByteArray,
        protocol: Int,
        transport: ByteArray,
        transportOffset: Int,
        transportLength: Int,
    ): Int {
        var sum = 0L
        sum += addrWords(sourceIp)
        sum += addrWords(destIp)
        sum += protocol.toLong()
        sum += transportLength.toLong()
        sum += sumWords(transport, transportOffset, transportLength)
        return fold(sum)
    }

    private fun addrWords(addr: ByteArray): Long {
        var sum = 0L
        var i = 0
        while (i + 1 < addr.size) {
            sum += ((addr[i].toInt() and 0xFF) shl 8) or (addr[i + 1].toInt() and 0xFF)
            i += 2
        }
        return sum
    }

    private fun fold(initial: Long): Int {
        var sum = initial
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }
}
