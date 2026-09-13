package ph.tigil.blocker.vpn

/**
 * Just enough DNS wire-format handling to read the question and synthesise a
 * refusal. We never build answer records — a blocked lookup gets NXDOMAIN,
 * which makes browsers and apps fail immediately and clearly instead of
 * hanging or showing a confusing blank page from 0.0.0.0.
 */
object DnsMessage {

    private const val HEADER_LENGTH = 12
    private const val MAX_NAME_LENGTH = 253
    private const val RCODE_NAME_ERROR = 3

    /**
     * The queried hostname, or null if this is not a single-question query we
     * understand. Trailing dot stripped, lowercased.
     */
    fun questionName(message: ByteArray): String? {
        if (message.size < HEADER_LENGTH) return null
        val flags = readShort(message, 2)
        if ((flags and 0x8000) != 0) return null                // a response, not a query
        if (readShort(message, 4) != 1) return null             // QDCOUNT must be 1

        val name = StringBuilder(64)
        var offset = HEADER_LENGTH
        while (offset < message.size) {
            val length = message[offset].toInt() and 0xFF
            when {
                length == 0 -> {
                    if (name.isEmpty()) return null             // root query
                    return name.toString().lowercase()
                }
                // Compression pointers are illegal in a question section. Seeing
                // one means the packet is malformed or hostile; refuse to follow
                // it rather than risk a pointer loop.
                length and 0xC0 != 0 -> return null
                else -> {
                    val start = offset + 1
                    val end = start + length
                    if (end > message.size) return null
                    if (name.length + length + 1 > MAX_NAME_LENGTH) return null
                    if (name.isNotEmpty()) name.append('.')
                    for (index in start until end) {
                        name.append((message[index].toInt() and 0xFF).toChar())
                    }
                    offset = end
                }
            }
        }
        return null
    }

    /**
     * An NXDOMAIN response echoing [query]'s header and question section.
     *
     * Keeping the question is not optional: resolvers match a reply to its
     * outstanding query by ID *and* question, and will discard a reply that
     * omits it.
     */
    fun nameError(query: ByteArray): ByteArray {
        val questionEnd = questionSectionEnd(query) ?: return ByteArray(0)
        val response = query.copyOf(questionEnd)

        val flags = readShort(query, 2)
        val opcode = flags and 0x7800
        val recursionDesired = flags and 0x0100
        writeShort(
            response, 2,
            0x8000 or                 // QR: this is a response
                opcode or             // echo the original opcode
                recursionDesired or   // RD, echoed as the RFC requires
                0x0080 or             // RA: recursion available
                RCODE_NAME_ERROR,
        )
        writeShort(response, 6, 0)    // ANCOUNT
        writeShort(response, 8, 0)    // NSCOUNT
        writeShort(response, 10, 0)   // ARCOUNT — drops any EDNS OPT record,
                                      // which is fine for a bare NXDOMAIN
        return response
    }

    /** Offset just past the question section (QNAME + QTYPE + QCLASS). */
    private fun questionSectionEnd(message: ByteArray): Int? {
        var offset = HEADER_LENGTH
        while (offset < message.size) {
            val length = message[offset].toInt() and 0xFF
            if (length == 0) {
                val end = offset + 1 + 4
                return if (end <= message.size) end else null
            }
            if (length and 0xC0 != 0) return null
            offset += length + 1
        }
        return null
    }

    private fun readShort(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    private fun writeShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value ushr 8).toByte()
        buffer[offset + 1] = value.toByte()
    }
}
