package ph.tigil.blocker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class TigilApp : Application() {

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROTECTION,
                getString(R.string.channel_protection),
                // LOW, not DEFAULT: this notification is legally required to be
                // visible while a foreground service runs, but it should never
                // make a sound or interrupt anyone.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_protection_description)
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_PROTECTION = "protection"
    }
}
