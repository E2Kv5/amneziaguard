package com.amneziaguard.core.netstack.packet

/** Transport protocol numbers we handle. */
object IpProtocol {
    const val TCP = 6
    const val UDP = 17
    const val ICMP = 1
    const val ICMPV6 = 58
}

/** Identifies a connection for UID lookup and flow tracking. */
data class FlowKey(
    val protocol: Int,
    val sourceIp: ByteArray,
    val sourcePort: Int,
    val destIp: ByteArray,
    val destPort: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FlowKey) return false
        return protocol == other.protocol &&
            sourcePort == other.sourcePort &&
            destPort == other.destPort &&
            sourceIp.contentEquals(other.sourceIp) &&
            destIp.contentEquals(other.destIp)
    }

    override fun hashCode(): Int {
        var result = protocol
        result = 31 * result + sourcePort
        result = 31 * result + destPort
        result = 31 * result + sourceIp.contentHashCode()
        result = 31 * result + destIp.contentHashCode()
        return result
    }
}

/**
 * A parsed IPv4 or IPv6 packet: header fields plus the offset/length of the
 * transport payload within the original buffer. Zero-copy — it references the
 * backing [data] array rather than copying.
 */
class IpPacket private constructor(
    val data: ByteArray,
    val version: Int,
    val protocol: Int,
    val sourceIp: ByteArray,
    val destIp: ByteArray,
    val headerLength: Int,
    val totalLength: Int,
) {
    val transportOffset: Int get() = headerLength
    val transportLength: Int get() = totalLength - headerLength

    val sourcePort: Int get() = readPort(transportOffset)
    val destPort: Int get() = readPort(transportOffset + 2)

    fun flowKey(): FlowKey =
        FlowKey(protocol, sourceIp, sourcePort, destIp, destPort)

    private fun readPort(offset: Int): Int {
        if (protocol != IpProtocol.TCP && protocol != IpProtocol.UDP) return 0
        if (offset + 1 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    companion object {
        fun parse(data: ByteArray, length: Int = data.size): IpPacket? {
            if (length < 1) return null
            return when (data[0].toInt() and 0xF0 shr 4) {
                4 -> parseV4(data, length)
                6 -> parseV6(data, length)
                else -> null
            }
        }

        private fun parseV4(data: ByteArray, length: Int): IpPacket? {
            if (length < 20) return null
            val ihl = (data[0].toInt() and 0x0F) * 4
            if (ihl < 20 || ihl > length) return null
            val totalLength = word(data, 2).coerceAtMost(length)
            val protocol = data[9].toInt() and 0xFF
            val src = data.copyOfRange(12, 16)
            val dst = data.copyOfRange(16, 20)
            return IpPacket(data, 4, protocol, src, dst, ihl, totalLength)
        }

        private fun parseV6(data: ByteArray, length: Int): IpPacket? {
            if (length < 40) return null
            val payloadLength = word(data, 4)
            val nextHeader = data[6].toInt() and 0xFF
            val src = data.copyOfRange(8, 24)
            val dst = data.copyOfRange(24, 40)
            // No extension-header walking yet — the common case has the
            // transport header immediately after the fixed 40-byte header.
            val total = (40 + payloadLength).coerceAtMost(length)
            return IpPacket(data, 6, nextHeader, src, dst, 40, total)
        }

        private fun word(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }
}

/** TCP control flags for the segment at [offset] within [data]. */
class TcpFlags(private val data: ByteArray, private val offset: Int) {
    private val flags: Int get() = data[offset + 13].toInt() and 0xFF
    val fin: Boolean get() = flags and 0x01 != 0
    val syn: Boolean get() = flags and 0x02 != 0
    val rst: Boolean get() = flags and 0x04 != 0
    val psh: Boolean get() = flags and 0x08 != 0
    val ack: Boolean get() = flags and 0x10 != 0
    val sequenceNumber: Long get() = uint32(offset + 4)
    val acknowledgementNumber: Long get() = uint32(offset + 8)
    val dataOffsetBytes: Int get() = ((data[offset + 12].toInt() and 0xF0) shr 4) * 4

    private fun uint32(o: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (data[o + i].toLong() and 0xFF)
        return v
    }
}
