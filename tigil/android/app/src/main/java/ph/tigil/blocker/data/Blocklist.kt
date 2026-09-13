package ph.tigil.blocker.data

import java.io.DataInputStream
import java.io.InputStream

/**
 * The compiled domain blocklist: a sorted array of 64-bit FNV-1a hashes.
 *
 * ~346,000 domains would cost roughly 30 MiB of heap as strings. As hashes it
 * is 2.6 MiB flat, loads in a single sequential read, and answers a lookup with
 * a binary search — which matters because every DNS query on the device passes
 * through here.
 *
 * The trade-off is that we can no longer enumerate what is blocked, and that a
 * hash collision would over-block one unrelated domain. At 346k entries in a
 * 64-bit space the collision probability is about 3 in 10^9; the allowlist is
 * the escape hatch if it ever happens.
 *
 * Binary format (big-endian), produced by blocklist/build_blocklist.py:
 *
 *     magic        8 bytes  "TIGIL\0\0\x01"
 *     formatVer    uint32
 *     listVersion  uint32   build timestamp, epoch seconds
 *     count        uint32
 *     reserved     uint32
 *     hashes       count * int64, sorted ascending
 */
class Blocklist private constructor(
    val listVersion: Int,
    private val hashes: LongArray,
) {

    val size: Int get() = hashes.size

    /**
     * True if [host] itself, or any parent domain of it, is on the list.
     *
     * "promo.cdn.jiliko7.net" tests jiliko7.net, cdn.jiliko7.net and the full
     * host, so blocking a domain blocks every subdomain without the list having
     * to enumerate them.
     */
    fun blocks(host: String): Boolean {
        var start = 0
        while (start < host.length) {
            if (contains(Hashing.fnv1a64(host.substring(start)))) return true
            val dot = host.indexOf('.', start)
            if (dot < 0) return false
            start = dot + 1
            // Stop before testing a bare public suffix like "ph" or "com":
            // a single-label candidate can never be a registrable domain.
            if (host.indexOf('.', start) < 0) return false
        }
        return false
    }

    private fun contains(hash: Long): Boolean {
        var low = 0
        var high = hashes.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = hashes[mid]
            when {
                value < hash -> low = mid + 1
                value > hash -> high = mid - 1
                else -> return true
            }
        }
        return false
    }

    companion object {
        private val MAGIC = byteArrayOf(
            'T'.code.toByte(), 'I'.code.toByte(), 'G'.code.toByte(),
            'I'.code.toByte(), 'L'.code.toByte(), 0, 0, 1,
        )

        val EMPTY = Blocklist(0, LongArray(0))

        fun read(stream: InputStream): Blocklist = DataInputStream(
            stream.buffered(1 shl 16)
        ).use { input ->
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            require(magic.contentEquals(MAGIC)) {
                "not a Tigil blocklist (bad magic)"
            }
            val formatVersion = input.readInt()
            require(formatVersion == 1) {
                "unsupported blocklist format $formatVersion"
            }
            val listVersion = input.readInt()
            val count = input.readInt()
            require(count in 0..20_000_000) { "implausible domain count $count" }
            input.readInt()   // reserved

            val hashes = LongArray(count)
            for (index in 0 until count) hashes[index] = input.readLong()
            Blocklist(listVersion, hashes)
        }
    }
}
