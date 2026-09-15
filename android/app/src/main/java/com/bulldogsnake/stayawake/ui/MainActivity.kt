package com.bulldogsnake.stayawake.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.bulldogsnake.stayawake.Prefs
import com.bulldogsnake.stayawake.R
import com.bulldogsnake.stayawake.Site
import com.bulldogsnake.stayawake.safety.ParkedMonitor

/** Phone-side setup: permissions, sign-in, Android Auto instructions, and options. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        findViewById<Button>(R.id.btn_grant_location).setOnClickListener {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION,
            )
        }
        findViewById<Button>(R.id.btn_grant_car_speed).setOnClickListener {
            requestPermissions(arrayOf(ParkedMonitor.CAR_SPEED_PERMISSION), REQ_CAR_SPEED)
        }
        findViewById<Button>(R.id.btn_grant_notifications).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            }
        }

        findViewById<Button>(R.id.btn_login_youtube).setOnClickListener { openLogin(Site.YOUTUBE) }
        findViewById<Button>(R.id.btn_login_netflix).setOnClickListener { openLogin(Site.NETFLIX) }
        findViewById<Button>(R.id.btn_login_prime).setOnClickListener { openLogin(Site.PRIME) }

        findViewById<Button>(R.id.btn_open_android_auto).setOnClickListener { openAndroidAuto() }

        findViewById<Button>(R.id.btn_clear_data).setOnClickListener {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            prefs.lastUrl = null
            Toast.makeText(this, "Browser data cleared", Toast.LENGTH_SHORT).show()
        }

        bindSwitch(R.id.sw_desktop_youtube, { prefs.desktopMode(Site.YOUTUBE) }, { prefs.setDesktopMode(Site.YOUTUBE, it) })
        bindSwitch(R.id.sw_desktop_netflix, { prefs.desktopMode(Site.NETFLIX) }, { prefs.setDesktopMode(Site.NETFLIX, it) })
        bindSwitch(R.id.sw_desktop_prime, { prefs.desktopMode(Site.PRIME) }, { prefs.setDesktopMode(Site.PRIME, it) })
        bindSwitch(R.id.sw_keep_screen_on, { prefs.keepPhoneScreenOn }, { prefs.keepPhoneScreenOn = it })
        bindSwitch(R.id.sw_manual_park, { prefs.manualParkConfirmAllowed }, { prefs.manualParkConfirmAllowed = it })

        val threshold = findViewById<EditText>(R.id.et_speed_threshold)
        threshold.setText(prefs.speedThresholdKmh.toInt().toString())
        findViewById<Button>(R.id.btn_save_threshold).setOnClickListener {
            val value = threshold.text.toString().trim().toFloatOrNull()
            if (value == null || value < 1f || value > 15f) {
                Toast.makeText(this, "Enter a value between 1 and 15 km/h", Toast.LENGTH_SHORT).show()
            } else {
                prefs.speedThresholdKmh = value
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    private fun bindSwitch(id: Int, read: () -> Boolean, write: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = read()
        sw.setOnCheckedChangeListener { _, checked -> write(checked) }
    }

    private fun openLogin(site: Site) {
        startActivity(LoginActivity.intent(this, site))
    }

    private fun openAndroidAuto() {
        val launch = packageManager.getLaunchIntentForPackage(ANDROID_AUTO_PACKAGE)
        if (launch != null) {
            startActivity(launch)
        } else {
            Toast.makeText(this, "Android Auto app not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshPermissionStatus() {
        val location = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val carSpeed = granted(ParkedMonitor.CAR_SPEED_PERMISSION)
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)
        findViewById<TextView>(R.id.tv_permission_status).text = buildString {
            append("Location: ").append(if (location) "granted" else "missing").append('\n')
            append("Car speed: ").append(if (carSpeed) "granted" else "missing").append('\n')
            append("Notifications: ").append(if (notifications) "granted" else "missing")
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissionStatus()
    }

    companion object {
        private const val REQ_LOCATION = 1
        private const val REQ_CAR_SPEED = 2
        private const val REQ_NOTIFICATIONS = 3
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    }
}
