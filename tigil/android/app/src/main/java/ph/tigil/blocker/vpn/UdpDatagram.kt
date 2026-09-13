package ph.tigil.blocker.vpn

/**
 * Minimal IPv4 + UDP reader/writer.
 *
 * The VPN interface hands us raw IP packets, so there is no socket API to lean
 * on — we parse the headers ourselves. Only what a DNS interceptor needs is
 * implemented: IPv4, UDP, no fragmentation. Anything else is reported as
 * unparseable and dropped by the caller.
 */
class UdpDatagram private constructor(
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
) {

    /**
     * Build the reply to this datagram: same wire path, reversed.
     *
     * The tunnel is a raw IP interface, so a "reply" is a complete packet we
     * synthesise with the endpoints swapped — the kernel does none of this for
     * us.
     */
    fun buildReply(replyPayload: ByteArray): ByteArray {
        val totalLength = IPV4_HEADER_LENGTH + UDP_HEADER_LENGTH + replyPayload.size
        val packet = ByteArray(totalLength)

        packet[0] = 0x45                                   // IPv4, IHL 5
        packet[1] = 0                                      // DSCP / ECN
        writeShort(packet, 2, totalLength)
        writeShort(packet, 4, 0)                           // identification
        writeShort(packet, 6, 0x4000)                      // don't fragment
        packet[8] = 64                                     // TTL
        packet[9] = PROTOCOL_UDP.toByte()
        writeShort(packet, 10, 0)                          // checksum, filled below
        destinationAddress.copyInto(packet, 12)            // reply source = our dst
        sourceAddress.copyInto(packet, 16)
        writeShort(packet, 10, checksum(packet, 0, IPV4_HEADER_LENGTH))

        var offset = IPV4_HEADER_LENGTH
        writeShort(packet, offset, destinationPort)
        writeShort(packet, offset + 2, sourcePort)
        writeShort(packet, offset + 4, UDP_HEADER_LENGTH + replyPayload.size)
        writeShort(packet, offset + 6, 0)                  // checksum, filled below
        offset += UDP_HEADER_LENGTH
        replyPayload.copyInto(packet, offset)

        writeShort(packet, IPV4_HEADER_LENGTH + 6, udpChecksum(packet))
        return packet
    }

    /**
     * UDP checksum over the IPv4 pseudo-header plus the UDP header and payload.
     * Optional in IPv4, but some stacks (and some Android builds) drop packets
     * with a bogus one, and a zero checksum is indistinguishable from "wrong"
     * to a picky receiver — so compute it properly.
     */
    private fun udpChecksum(packet: ByteArray): Int {
        val udpLength = packet.size - IPV4_HEADER_LENGTH
        var sum = 0L

        for (index in 12 until 20 step 2) {                // src + dst addresses
            sum += readShort(packet, index)
        }
        sum += PROTOCOL_UDP
        sum += udpLength

        var index = IPV4_HEADER_LENGTH
        while (index + 1 < packet.size) {
            sum += readShort(packet, index)
            index += 2
        }
        if (index < packet.size) {                          // odd trailing byte
            sum += (packet[index].toInt() and 0xFF) shl 8
        }

        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val result = (sum.inv() and 0xFFFF).toInt()
        // All-zeroes means "no checksum" on the wire, so RFC 768 says send all-ones.
        return if (result == 0) 0xFFFF else result
    }

    companion object {
        const val IPV4_HEADER_LENGTH = 20
        const val UDP_HEADER_LENGTH = 8
        private const val PROTOCOL_UDP = 17

        /** Returns null for anything that is not a complete, unfragmented IPv4/UDP packet. */
        fun parse(buffer: ByteArray, length: Int): UdpDatagram? {
            if (length < IPV4_HEADER_LENGTH) return null
            if ((buffer[0].toInt() and 0xF0) != 0x40) return null       // not IPv4
            if ((buffer[9].toInt() and 0xFF) != PROTOCOL_UDP) return null

            // Fragmented packets carry a partial UDP header at best. DNS over
            // UDP is never fragmented in practice; dropping is correct here.
            val fragment = readShort(buffer, 6)
            if ((fragment and 0x2000) != 0 || (fragment and 0x1FFF) != 0) return null

            val headerLength = (buffer[0].toInt() and 0x0F) * 4
            if (headerLength < IPV4_HEADER_LENGTH) return null
            if (length < headerLength + UDP_HEADER_LENGTH) return null

            val udpLength = readShort(buffer, headerLength + 4)
            if (udpLength < UDP_HEADER_LENGTH) return null
            val payloadLength = minOf(
                udpLength - UDP_HEADER_LENGTH,
                length - headerLength - UDP_HEADER_LENGTH,
            )
            if (payloadLength < 0) return null

            val payloadStart = headerLength + UDP_HEADER_LENGTH
            return UdpDatagram(
                sourceAddress = buffer.copyOfRange(12, 16),
                destinationAddress = buffer.copyOfRange(16, 20),
                sourcePort = readShort(buffer, headerLength),
                destinationPort = readShort(buffer, headerLength + 2),
                payload = buffer.copyOfRange(payloadStart, payloadStart + payloadLength),
            )
        }

        private fun readShort(buffer: ByteArray, offset: Int): Int =
            ((buffer[offset].toInt() and 0xFF) shl 8) or
                (buffer[offset + 1].toInt() and 0xFF)

        private fun writeShort(buffer: ByteArray, offset: Int, value: Int) {
            buffer[offset] = (value ushr 8).toByte()
            buffer[offset + 1] = value.toByte()
        }

        private fun checksum(buffer: ByteArray, offset: Int, length: Int): Int {
            var sum = 0L
            var index = offset
            while (index + 1 < offset + length) {
                sum += readShort(buffer, index)
                index += 2
            }
            while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
            return (sum.inv() and 0xFFFF).toInt()
        }
    }
}
