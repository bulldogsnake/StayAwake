package ph.tigil.blocker.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ph.tigil.blocker.R
import ph.tigil.blocker.vpn.TigilVpnService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /**
     * The system VPN consent dialog. Android requires it to be launched from an
     * Activity and shown once per install — this is the only friction in setup,
     * and after it the user never has to open the app again.
     */
    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) TigilVpnService.start(this)
        viewModel.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = tigilColorScheme()) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                Surface(Modifier.fillMaxSize()) {
                    HomeScreen(
                        state = state,
                        onToggle = ::toggleProtection,
                        onStrictMode = viewModel::setStrictMode,
                        onCommit = viewModel::commit,
                        onAddBlock = viewModel::addUserBlock,
                        onOpenPrivateDnsSettings = {
                            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun toggleProtection(turnOn: Boolean) {
        if (turnOn) {
            val consentIntent = VpnService.prepare(this)
            if (consentIntent != null) vpnConsent.launch(consentIntent)
            else TigilVpnService.start(this)
        } else {
            TigilVpnService.stop(this)
        }
        viewModel.refresh()
    }
}

@Composable
private fun HomeScreen(
    state: UiState,
    onToggle: (Boolean) -> Unit,
    onStrictMode: (Boolean) -> Unit,
    onCommit: (Int) -> Unit,
    onAddBlock: (String) -> Unit,
    onOpenPrivateDnsSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        ShieldButton(enabled = state.enabled, locked = state.locked, onToggle = onToggle)
        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(
                if (state.enabled) R.string.status_on else R.string.status_off
            ),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (state.enabled) {
                stringResource(R.string.status_on_detail, state.domainCount)
            } else {
                stringResource(R.string.status_off_detail)
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        if (state.privateDnsActive) {
            WarningCard(
                title = stringResource(R.string.warn_private_dns_title),
                body = stringResource(R.string.warn_private_dns_body),
                actionLabel = stringResource(R.string.warn_private_dns_action),
                onAction = onOpenPrivateDnsSettings,
            )
            Spacer(Modifier.height(16.dp))
        }

        StatsCard(state)
        Spacer(Modifier.height(16.dp))
        CommitmentCard(state, onCommit)
        Spacer(Modifier.height(16.dp))
        SettingsCard(state, onStrictMode, onAddBlock)
        Spacer(Modifier.height(16.dp))
        HonestyCard()
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ShieldButton(enabled: Boolean, locked: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .size(180.dp)
            .clip(CircleShape)
            .clickable { if (!locked || !enabled) onToggle(!enabled) },
        shape = CircleShape,
        color = if (enabled) colors.primary else colors.surfaceVariant,
        tonalElevation = if (enabled) 8.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (enabled) "🛡" else "○",
                fontSize = 56.sp,
                color = if (enabled) colors.onPrimary else colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    when {
                        enabled && locked -> R.string.shield_locked
                        enabled -> R.string.shield_tap_to_stop
                        else -> R.string.shield_tap_to_start
                    }
                ),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                color = if (enabled) colors.onPrimary else colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatsCard(state: UiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.stats_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            StatRow(
                stringResource(R.string.stats_blocked),
                state.blockedCount.toString(),
            )
            StatRow(
                stringResource(R.string.stats_list_size),
                "%,d".format(state.domainCount),
            )
            if (state.protectingSinceEpochMs > 0L) {
                StatRow(
                    stringResource(R.string.stats_since),
                    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
                        .format(Date(state.protectingSinceEpochMs)),
                )
            }
            state.lastBlockedHost?.let {
                StatRow(stringResource(R.string.stats_last), it)
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CommitmentCard(state: UiState, onCommit: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.commit_title),
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.commit_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (state.locked) {
                Text(
                    stringResource(
                        R.string.commit_active,
                        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                            .format(Date(state.lockedUntilEpochMs)),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 7, 30).forEach { days ->
                        FilledTonalButton(onClick = { onCommit(days) }) {
                            Text(stringResource(R.string.commit_days, days))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    state: UiState,
    onStrictMode: (Boolean) -> Unit,
    onAddBlock: (String) -> Unit,
) {
    var domain by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.settings_strict),
                        style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.settings_strict_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.strictMode, onCheckedChange = onStrictMode)
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.settings_add_site),
                style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("example.com") },
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onAddBlock(domain); domain = "" }) {
                    Text(stringResource(R.string.settings_add))
                }
            }
        }
    }
}

@Composable
private fun WarningCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(10.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * We tell users what this cannot do, on the main screen, unprompted.
 *
 * A blocker that oversells itself is worse than none: someone builds their
 * recovery plan on a guarantee that is not real. Honesty here is a feature.
 */
@Composable
private fun HonestyCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.honesty_title),
                style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.honesty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.honesty_help),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun tigilColorScheme() =
    if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
