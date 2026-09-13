package ph.tigil.blocker.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Loads the rule engine, preferring a downloaded update over the copy shipped
 * in the APK.
 *
 * The engine is a process-wide singleton: it is ~3 MiB of hashes and both the
 * VPN service and the UI need it, so loading it twice would be wasteful and
 * loading it per-query would be absurd.
 */
object BlocklistRepository {

    private const val TAG = "TigilBlocklist"
    private const val ASSET_BLOCKLIST = "tigil-blocklist.bin"
    private const val ASSET_RULES = "rules.json"

    @Volatile private var engine: RuleEngine? = null

    fun engine(context: Context): RuleEngine =
        engine ?: synchronized(this) {
            engine ?: load(context.applicationContext).also { engine = it }
        }

    /** Drop the cached engine so the next call picks up a downloaded update. */
    fun invalidate() = synchronized(this) { engine = null }

    private fun load(context: Context): RuleEngine {
        val blocklist = runCatching {
            updatedFile(context, ASSET_BLOCKLIST)?.inputStream()?.use(Blocklist::read)
                ?: context.assets.open(ASSET_BLOCKLIST).use(Blocklist::read)
        }.getOrElse {
            // A corrupt list must not take the app down. Failing open here is
            // the wrong default for a blocker, so it is surfaced loudly in the
            // UI via `size == 0` rather than silently swallowed.
            Log.e(TAG, "blocklist unreadable; heuristics only", it)
            Blocklist.EMPTY
        }

        val rulesJson = runCatching {
            updatedFile(context, ASSET_RULES)?.readText()
                ?: context.assets.open(ASSET_RULES).bufferedReader().use { it.readText() }
        }.getOrElse {
            Log.e(TAG, "rules.json unreadable; list only", it)
            "{}"
        }

        Log.i(TAG, "loaded ${blocklist.size} domains (list v${blocklist.listVersion})")
        return RuleEngine.fromJson(rulesJson, blocklist).apply {
            val prefs = Prefs(context)
            setUserLists(prefs.userAllowlist, prefs.userBlocklist)
        }
    }

    private fun updatedFile(context: Context, name: String): File? =
        File(context.filesDir, "blocklist/$name").takeIf { it.isFile && it.length() > 0 }

    fun updateDirectory(context: Context): File =
        File(context.filesDir, "blocklist").apply { mkdirs() }
}
