package ph.tigil.blocker.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * App state.
 *
 * Deliberately SharedPreferences and not DataStore: the VPN service reads
 * settings on the DNS hot path and from a plain thread with no coroutine
 * scope, and a synchronous, already-in-memory read is exactly what that needs.
 * The UI gets a Flow via [changes].
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("tigil", Context.MODE_PRIVATE)

    /** Whether the user wants protection on. Not the same as "is running". */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * Drop DoH/DoT traffic aimed at well-known public resolvers.
     *
     * Without this, any app (or a browser with "Secure DNS" on) resolves over
     * HTTPS and never touches our tunnel. On by default: bypass-resistance is
     * the entire point of the product.
     */
    var strictMode: Boolean
        get() = prefs.getBoolean(KEY_STRICT, true)
        set(value) = prefs.edit().putBoolean(KEY_STRICT, value).apply()

    /**
     * Commitment lock. Until this timestamp the user cannot turn protection
     * off. This exists because the moment someone most wants to disable a
     * gambling blocker is the moment it is doing its job — a cooling-off delay
     * is the single most effective feature in this category.
     */
    var lockedUntilEpochMs: Long
        get() = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCKED_UNTIL, value).apply()

    val isLocked: Boolean get() = System.currentTimeMillis() < lockedUntilEpochMs

    var upstreamDns: String
        get() = prefs.getString(KEY_UPSTREAM, DEFAULT_UPSTREAM) ?: DEFAULT_UPSTREAM
        set(value) = prefs.edit().putString(KEY_UPSTREAM, value).apply()

    var userAllowlist: Set<String>
        get() = prefs.getStringSet(KEY_USER_ALLOW, emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_USER_ALLOW, value).apply()

    var userBlocklist: Set<String>
        get() = prefs.getStringSet(KEY_USER_BLOCK, emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_USER_BLOCK, value).apply()

    val blockedCount: Long get() = prefs.getLong(KEY_BLOCKED_COUNT, 0L)
    val lastBlockedHost: String? get() = prefs.getString(KEY_LAST_BLOCKED, null)
    var protectingSinceEpochMs: Long
        get() = prefs.getLong(KEY_PROTECTING_SINCE, 0L)
        set(value) = prefs.edit().putLong(KEY_PROTECTING_SINCE, value).apply()

    /**
     * Record a block. Called from the DNS thread, so it batches: committing a
     * preference write per blocked query would put disk I/O in front of every
     * DNS lookup on the device.
     */
    @Synchronized
    fun recordBlock(host: String) {
        pendingBlocks += 1
        pendingHost = host
        val now = System.currentTimeMillis()
        if (now - lastFlushMs >= FLUSH_INTERVAL_MS) flushLocked(now)
    }

    @Synchronized
    fun flushStats() = flushLocked(System.currentTimeMillis())

    private fun flushLocked(now: Long) {
        if (pendingBlocks == 0L) return
        prefs.edit()
            .putLong(KEY_BLOCKED_COUNT, blockedCount + pendingBlocks)
            .putString(KEY_LAST_BLOCKED, pendingHost)
            .apply()
        pendingBlocks = 0
        lastFlushMs = now
    }

    private var pendingBlocks = 0L
    private var pendingHost: String? = null
    private var lastFlushMs = 0L

    /** Emits the key of every changed preference, so the UI can recompose. */
    fun changes(): Flow<String> = callbackFlow {
        // trySend, not trySendBlocking: this fires on the main thread and must
        // never block. The flow is conflated, so a dropped emission just means
        // the next one carries the newer state.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            key?.let { trySend(it) }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(KEY_ENABLED)   // prime the UI with current state
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    companion object {
        const val DEFAULT_UPSTREAM = "1.1.1.1"
        private const val FLUSH_INTERVAL_MS = 3_000L

        const val KEY_ENABLED = "enabled"
        private const val KEY_STRICT = "strict_mode"
        private const val KEY_LOCKED_UNTIL = "locked_until"
        private const val KEY_UPSTREAM = "upstream_dns"
        private const val KEY_USER_ALLOW = "user_allow"
        private const val KEY_USER_BLOCK = "user_block"
        private const val KEY_BLOCKED_COUNT = "blocked_count"
        private const val KEY_LAST_BLOCKED = "last_blocked"
        private const val KEY_PROTECTING_SINCE = "protecting_since"
    }
}
