package ph.tigil.blocker.data

import org.json.JSONObject
import java.util.regex.Pattern

/** Why a hostname was allowed or blocked — surfaced in the UI and the log. */
enum class Verdict { ALLOWED, BLOCKED_LIST, BLOCKED_KEYWORD, BLOCKED_USER }

val Verdict.isBlocked: Boolean get() = this != Verdict.ALLOWED

/**
 * Decides whether a hostname is gambling.
 *
 * Order matters and is deliberate:
 *
 *   1. user allowlist   — the person's own override beats everything
 *   2. shipped allowlist— help lines, banks, the regulator, false positives
 *   3. user blocklist   — something we missed that they hit
 *   4. compiled list    — ~346k known domains
 *   5. heuristics       — substring / regex / TLD rules against the
 *                         registrable domain
 *
 * Step 5 is what makes this worth shipping. Philippine operators rotate
 * domains weekly (wpc2025 -> wpc2026, jiliko1 -> jiliko7), so a list-only
 * blocker is stale the day it ships. The heuristics match the *shape* of an
 * operator domain, which is much more stable than any individual domain.
 */
class RuleEngine(
    private val blocklist: Blocklist,
    private val shippedAllowlist: Set<String>,
    private val substrings: List<String>,
    private val regexes: List<Pattern>,
    private val tlds: List<String>,
) {

    @Volatile private var userAllowlist: Set<String> = emptySet()
    @Volatile private var userBlocklist: Set<String> = emptySet()

    /**
     * Verdict cache. A phone re-resolves the same few hundred hostnames over
     * and over; without this, every query would re-run 13 regexes. Bounded and
     * cleared whenever the rules change.
     */
    private val cache = object : LinkedHashMap<String, Verdict>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Verdict>) =
            size > MAX_CACHE_ENTRIES
    }

    fun setUserLists(allow: Set<String>, block: Set<String>) {
        userAllowlist = allow.map { it.lowercase().trim('.') }.toSet()
        userBlocklist = block.map { it.lowercase().trim('.') }.toSet()
        synchronized(cache) { cache.clear() }
    }

    fun evaluate(rawHost: String): Verdict {
        val host = rawHost.lowercase().trim('.')
        if (host.isEmpty()) return Verdict.ALLOWED

        synchronized(cache) { cache[host] }?.let { return it }
        val verdict = compute(host)
        synchronized(cache) { cache[host] = verdict }
        return verdict
    }

    private fun compute(host: String): Verdict {
        if (matchesSuffix(host, userAllowlist)) return Verdict.ALLOWED
        if (matchesSuffix(host, shippedAllowlist)) return Verdict.ALLOWED
        if (matchesSuffix(host, userBlocklist)) return Verdict.BLOCKED_USER
        if (blocklist.blocks(host)) return Verdict.BLOCKED_LIST

        val registrable = registrableDomain(host)
        for (tld in tlds) if (registrable.endsWith(tld)) return Verdict.BLOCKED_KEYWORD
        for (needle in substrings) {
            if (registrable.contains(needle)) return Verdict.BLOCKED_KEYWORD
        }
        for (pattern in regexes) {
            if (pattern.matcher(registrable).find()) return Verdict.BLOCKED_KEYWORD
        }
        return Verdict.ALLOWED
    }

    /** True if [host] equals, or is a subdomain of, any entry in [set]. */
    private fun matchesSuffix(host: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var start = 0
        while (start < host.length) {
            if (set.contains(host.substring(start))) return true
            val dot = host.indexOf('.', start)
            if (dot < 0) return false
            start = dot + 1
        }
        return false
    }

    companion object {
        private const val MAX_CACHE_ENTRIES = 2_048

        /**
         * Two-label public suffixes we must not mistake for a registrable
         * domain. Not the full Public Suffix List — that is 10k entries and
         * a dependency; this covers the suffixes these feeds actually use.
         * A miss here only means a heuristic tests a slightly longer string,
         * which is the safe direction (fewer false positives, not more).
         */
        private val MULTI_LABEL_SUFFIXES = setOf(
            "com.ph", "net.ph", "org.ph", "gov.ph", "edu.ph", "co.ph",
            "com.au", "co.uk", "org.uk", "gov.uk", "co.jp", "com.br",
            "com.mx", "com.cn", "com.hk", "com.tw", "com.sg", "com.my",
            "co.id", "co.th", "com.vn", "co.kr", "co.nz", "com.tr",
            "co.za", "com.ar", "com.co",
        )

        fun registrableDomain(host: String): String {
            val parts = host.split('.')
            if (parts.size <= 2) return host
            val lastTwo = parts.takeLast(2).joinToString(".")
            val keep = if (lastTwo in MULTI_LABEL_SUFFIXES) 3 else 2
            return parts.takeLast(keep).joinToString(".")
        }

        /** Build from the `rules.json` produced by build_blocklist.py. */
        fun fromJson(json: String, blocklist: Blocklist): RuleEngine {
            val root = JSONObject(json)
            fun strings(key: String): List<String> {
                val array = root.optJSONArray(key) ?: return emptyList()
                return (0 until array.length()).map { array.getString(it) }
            }
            return RuleEngine(
                blocklist = blocklist,
                shippedAllowlist = strings("allowlist").toSet(),
                substrings = strings("substrings"),
                regexes = strings("regexes").map {
                    Pattern.compile(it, Pattern.CASE_INSENSITIVE)
                },
                tlds = strings("tlds"),
            )
        }
    }
}
