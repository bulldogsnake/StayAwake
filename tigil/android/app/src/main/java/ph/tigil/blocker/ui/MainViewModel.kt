package ph.tigil.blocker.ui

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ph.tigil.blocker.data.BlocklistRepository
import ph.tigil.blocker.data.Prefs
import java.util.concurrent.TimeUnit

data class UiState(
    val enabled: Boolean = false,
    val locked: Boolean = false,
    val lockedUntilEpochMs: Long = 0L,
    val strictMode: Boolean = true,
    val blockedCount: Long = 0L,
    val lastBlockedHost: String? = null,
    val domainCount: Int = 0,
    val protectingSinceEpochMs: Long = 0L,
    /**
     * Android's own "Private DNS" setting sends every lookup to a DoT resolver
     * of the user's choosing, entirely around our tunnel. It is the single
     * biggest hole in an on-device DNS blocker, so we detect it and say so
     * instead of appearing to work while blocking nothing.
     */
    val privateDnsActive: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = Prefs(application)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.changes().collect { refresh() }
        }
        refresh()
    }

    fun refresh() {
        _state.value = UiState(
            enabled = prefs.enabled,
            locked = prefs.isLocked,
            lockedUntilEpochMs = prefs.lockedUntilEpochMs,
            strictMode = prefs.strictMode,
            blockedCount = prefs.blockedCount,
            lastBlockedHost = prefs.lastBlockedHost,
            domainCount = domainCount(),
            protectingSinceEpochMs = prefs.protectingSinceEpochMs,
            privateDnsActive = isPrivateDnsActive(),
        )
    }

    private fun domainCount(): Int = runCatching {
        val manifest = getApplication<Application>().assets
            .open("manifest.json").bufferedReader().use { it.readText() }
        org.json.JSONObject(manifest).optInt("domainCount", 0)
    }.getOrDefault(0)

    private fun isPrivateDnsActive(): Boolean = runCatching {
        // "hostname" means a user-chosen DoT server; "opportunistic" upgrades
        // opportunistically but still asks the DNS server we advertise first,
        // so only "hostname" actually bypasses us.
        Settings.Global.getString(
            getApplication<Application>().contentResolver, "private_dns_mode",
        ) == "hostname"
    }.getOrDefault(false)

    fun setStrictMode(value: Boolean) {
        prefs.strictMode = value
        refresh()
    }

    /**
     * Start a commitment lock. Protection cannot be turned off until it
     * expires — the point is to put a delay between the urge and the off
     * switch, which is where relapse actually happens.
     */
    fun commit(days: Int) {
        prefs.lockedUntilEpochMs = System.currentTimeMillis() +
            TimeUnit.DAYS.toMillis(days.toLong())
        refresh()
    }

    fun addUserBlock(domain: String) {
        val cleaned = domain.trim().lowercase().removePrefix("www.").trim('.')
        if (cleaned.isEmpty() || !cleaned.contains('.')) return
        prefs.userBlocklist = prefs.userBlocklist + cleaned
        BlocklistRepository.engine(getApplication())
            .setUserLists(prefs.userAllowlist, prefs.userBlocklist)
        refresh()
    }
}
