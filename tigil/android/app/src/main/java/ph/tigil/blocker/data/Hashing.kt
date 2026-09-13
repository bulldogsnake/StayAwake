package ph.tigil.blocker.data

/**
 * 64-bit FNV-1a.
 *
 * This must stay byte-for-byte identical to `fnv1a64()` in
 * `blocklist/build_blocklist.py`. The compiled blocklist is nothing but a
 * sorted array of these hashes, so if the two implementations ever diverge the
 * app silently blocks nothing. [HashingTest] pins a few known vectors.
 */
object Hashing {

    private const val OFFSET_BASIS = -0x340d631b7bdddcdbL   // 0xCBF29CE484222325
    private const val PRIME = 0x100000001B3L

    fun fnv1a64(value: String): Long {
        var hash = OFFSET_BASIS
        for (element in value) {
            // Domains are punycode/ASCII by the time they reach us, so a
            // per-char loop is equivalent to hashing the UTF-8 bytes and skips
            // an allocation on every single DNS query.
            val code = element.code
            if (code < 0x80) {
                hash = (hash xor code.toLong()) * PRIME
            } else {
                for (byte in element.toString().toByteArray(Charsets.UTF_8)) {
                    hash = (hash xor (byte.toLong() and 0xFF)) * PRIME
                }
            }
        }
        return hash
    }
}
