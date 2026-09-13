package ph.tigil.blocker.system

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import ph.tigil.blocker.R
import ph.tigil.blocker.data.Prefs
import ph.tigil.blocker.ui.MainActivity
import ph.tigil.blocker.vpn.TigilVpnService

/**
 * Quick Settings tile.
 *
 * The brief was "a couple of clicks" — this is the shortest path there. Swipe
 * down, tap once. It also means the app itself never has to be opened again
 * after setup.
 */
class ProtectionTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        val prefs = Prefs(this)

        if (prefs.enabled) {
            if (prefs.isLocked) {
                // Turning it off is exactly what the commitment lock exists to
                // prevent, so send them to the app to see when it lifts rather
                // than silently doing nothing.
                openApp()
                return
            }
            TigilVpnService.stop(this)
        } else {
            if (VpnService.prepare(this) != null) {
                openApp()          // consent dialog can only come from an Activity
                return
            }
            TigilVpnService.start(this)
        }
        refresh()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // API 34 replaced the Intent overload with a PendingIntent one and
        // throws UnsupportedOperationException if you call the old form.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val prefs = Prefs(this)
        tile.state = if (prefs.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.contentDescription = getString(
            if (prefs.enabled) R.string.tile_on else R.string.tile_off
        )
        tile.updateTile()
    }
}
