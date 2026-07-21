package com.amneziaguard.core.netstack.packet

/**
 * The 16-bit one's-complement Internet checksum (RFC 1071) used by IPv4, TCP
 * and UDP. Kept pure so it is unit-testable on the JVM.
 */
object Checksums {

    /** Raw one's-complement sum over [length] bytes of [data] from [offset]. */
    fun internetChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            // Odd trailing byte is treated as the high byte of a 16-bit word.
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        return fold(sum)
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

        var i = transportOffset
        val end = transportOffset + transportLength
        while (i + 1 < end) {
            sum += ((transport[i].toInt() and 0xFF) shl 8) or (transport[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (transport[i].toInt() and 0xFF) shl 8
        }
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
