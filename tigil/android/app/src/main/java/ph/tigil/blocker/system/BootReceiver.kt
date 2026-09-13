package ph.tigil.blocker.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import ph.tigil.blocker.data.Prefs
import ph.tigil.blocker.vpn.TigilVpnService

/**
 * Re-arms protection after a reboot.
 *
 * Without this the blocker has an obvious hole: restart the phone and you are
 * unprotected until you remember to open the app. Rebooting is also the first
 * thing someone tries when they want around it.
 *
 * We can only restart if the VPN consent is still granted — [VpnService.prepare]
 * returning null is how we know. If consent was revoked there is nothing a
 * receiver can do; the UI asks again on next launch.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        if (!Prefs(context).enabled) return

        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "cannot auto-start: VPN consent not granted")
            return
        }
        Log.i(TAG, "re-arming protection after ${intent.action}")
        TigilVpnService.start(context)
    }

    private companion object {
        const val TAG = "TigilBoot"
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
