package com.bulldogsnake.stayawake

import android.content.Context
import android.content.SharedPreferences

/** Small wrapper around SharedPreferences for the handful of user options. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("stayawake", Context.MODE_PRIVATE)

    /** Last page shown on the car screen, restored when the car surface comes back. */
    var lastUrl: String?
        get() = sp.getString(KEY_LAST_URL, null)
        set(value) = sp.edit().putString(KEY_LAST_URL, value).apply()

    fun desktopMode(site: Site): Boolean = sp.getBoolean("desktop_" + site.id, site.defaultDesktop)

    fun setDesktopMode(site: Site, enabled: Boolean) =
        sp.edit().putBoolean("desktop_" + site.id, enabled).apply()

    /** Hold a dim wake lock on the phone while the car screen is active. */
    var keepPhoneScreenOn: Boolean
        get() = sp.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = sp.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    /** Allow the user to confirm "I am parked" when no speed source is available at all. */
    var manualParkConfirmAllowed: Boolean
        get() = sp.getBoolean(KEY_MANUAL_PARK, false)
        set(value) = sp.edit().putBoolean(KEY_MANUAL_PARK, value).apply()

    /** Speeds above this are treated as "moving" and pause playback. */
    var speedThresholdKmh: Float
        get() = sp.getFloat(KEY_SPEED_THRESHOLD, DEFAULT_SPEED_THRESHOLD_KMH)
        set(value) = sp.edit().putFloat(KEY_SPEED_THRESHOLD, value).apply()

    companion object {
        const val DEFAULT_SPEED_THRESHOLD_KMH = 5f
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_MANUAL_PARK = "manual_park_confirm"
        private const val KEY_SPEED_THRESHOLD = "speed_threshold_kmh"
    }
}
