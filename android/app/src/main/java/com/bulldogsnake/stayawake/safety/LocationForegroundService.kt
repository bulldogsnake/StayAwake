package com.bulldogsnake.stayawake.safety

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.bulldogsnake.stayawake.R

/**
 * Keeps the process at foreground-service priority while a car session is active so the
 * phone keeps delivering location (speed) updates even though no activity is visible.
 * It carries no logic of its own; ParkedMonitor does the work.
 */
class LocationForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // Android 12+ can refuse foreground starts from the background; GPS then simply
            // runs at whatever rate the system allows and the car speed source takes over.
            Log.w(TAG, "Foreground start refused", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    companion object {
        private const val TAG = "LocationFgService"
        private const val CHANNEL_ID = "speed_monitor"
        private const val NOTIFICATION_ID = 1

        fun startIfPermitted(context: Context) {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, LocationForegroundService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "Could not start location service", t)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LocationForegroundService::class.java)) }
        }
    }
}
