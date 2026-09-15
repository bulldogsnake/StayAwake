package com.bulldogsnake.stayawake.safety

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat
import com.bulldogsnake.stayawake.Prefs
import java.util.Locale
import kotlin.math.abs

/**
 * Decides whether the vehicle is parked. Video is only allowed in [State.PARKED].
 *
 * Sources, in order of preference:
 *  1. Vehicle speed reported by the head unit through the Car App Library (needs the
 *     CAR_SPEED permission).
 *  2. Phone GPS / fused location speed (needs the location permission).
 *
 * With no data at all the state is [State.UNKNOWN], which also blocks playback.
 */
class ParkedMonitor(private val carContext: CarContext, private val prefs: Prefs) {

    enum class State { UNKNOWN, PARKED, MOVING }

    data class Status(val state: State, val detail: String)

    fun interface Listener {
        fun onStatus(status: Status)
    }

    var listener: Listener? = null

    var status: Status = Status(State.UNKNOWN, "Starting…")
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private var carInfo: CarInfo? = null
    private var carSpeedListener: OnCarDataAvailableListener<Speed>? = null
    private var locationListener: LocationListener? = null

    private var carSpeedMps = Float.NaN
    private var carSpeedAt = 0L
    private var gpsSpeedMps = Float.NaN
    private var gpsSpeedAt = 0L
    private var stoppedSince = 0L
    private var manualConfirm = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            evaluate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        startSources()
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        stopSources()
    }

    /** Call after permissions were granted so the sources can be (re)attached. */
    fun restartSources() {
        stopSources()
        startSources()
    }

    fun confirmParkedManually() {
        if (!prefs.manualParkConfirmAllowed) return
        manualConfirm = true
        evaluate()
    }

    fun hasCarSpeedPermission(): Boolean = hasPermission(CAR_SPEED_PERMISSION)

    fun hasLocationPermission(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(carContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun startSources() {
        if (hasCarSpeedPermission()) {
            try {
                val info = carContext.getCarService(CarHardwareManager::class.java).carInfo
                val l = OnCarDataAvailableListener<Speed> { speed -> onCarSpeed(speed) }
                info.addSpeedListener(ContextCompat.getMainExecutor(carContext), l)
                carInfo = info
                carSpeedListener = l
            } catch (t: Throwable) {
                Log.w(TAG, "Car speed not available from this host", t)
            }
        }
        if (hasLocationPermission()) {
            try {
                val lm = carContext.getSystemService(LocationManager::class.java)
                val provider = pickProvider(lm)
                if (lm != null && provider != null) {
                    val l = object : LocationListener {
                        override fun onLocationChanged(location: Location) = onLocation(location)
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    }
                    lm.requestLocationUpdates(provider, LOCATION_INTERVAL_MS, 0f, l, Looper.getMainLooper())
                    locationListener = l
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Location updates not available", t)
            }
        }
    }

    private fun stopSources() {
        carSpeedListener?.let { l -> runCatching { carInfo?.removeSpeedListener(l) } }
        carSpeedListener = null
        carInfo = null
        locationListener?.let { l ->
            runCatching { carContext.getSystemService(LocationManager::class.java)?.removeUpdates(l) }
        }
        locationListener = null
    }

    private fun pickProvider(lm: LocationManager?): String? {
        if (lm == null) return null
        val providers = lm.allProviders
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && providers.contains(LocationManager.FUSED_PROVIDER)) {
            return LocationManager.FUSED_PROVIDER
        }
        if (providers.contains(LocationManager.GPS_PROVIDER)) return LocationManager.GPS_PROVIDER
        if (providers.contains(LocationManager.NETWORK_PROVIDER)) return LocationManager.NETWORK_PROVIDER
        return null
    }

    private fun onCarSpeed(speed: Speed) {
        val display = speed.displaySpeedMetersPerSecond
        val raw = speed.rawSpeedMetersPerSecond
        val value = when {
            display.status == CarValue.STATUS_SUCCESS && display.value != null -> display.value
            raw.status == CarValue.STATUS_SUCCESS && raw.value != null -> raw.value
            else -> null
        } ?: return
        carSpeedMps = abs(value)
        carSpeedAt = SystemClock.elapsedRealtime()
        evaluate()
    }

    private fun onLocation(location: Location) {
        if (!location.hasSpeed()) return
        gpsSpeedMps = abs(location.speed)
        gpsSpeedAt = SystemClock.elapsedRealtime()
        evaluate()
    }

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        val thresholdMps = prefs.speedThresholdKmh / 3.6f

        // Prefer a fresh car reading, then fresh GPS, then whatever we last heard.
        val speed: Float
        val source: String
        when {
            carSpeedAt != 0L && now - carSpeedAt <= CAR_FRESH_MS -> { speed = carSpeedMps; source = "car" }
            gpsSpeedAt != 0L && now - gpsSpeedAt <= GPS_FRESH_MS -> { speed = gpsSpeedMps; source = "GPS" }
            carSpeedAt != 0L -> { speed = carSpeedMps; source = "car, last reading" }
            gpsSpeedAt != 0L -> { speed = gpsSpeedMps; source = "GPS, last reading" }
            else -> { speed = Float.NaN; source = "" }
        }

        val next = when {
            speed.isNaN() -> {
                if (manualConfirm && prefs.manualParkConfirmAllowed) {
                    Status(State.PARKED, "Parked (confirmed manually)")
                } else {
                    Status(State.UNKNOWN, noDataDetail())
                }
            }
            speed > thresholdMps -> {
                stoppedSince = 0L
                manualConfirm = false
                Status(
                    State.MOVING,
                    String.format(Locale.US, "Moving at %.0f km/h (%s)", speed * 3.6f, source),
                )
            }
            else -> {
                if (stoppedSince == 0L) stoppedSince = now
                if (now - stoppedSince >= STOP_GRACE_MS) {
                    Status(State.PARKED, "Parked ($source)")
                } else {
                    Status(State.MOVING, "Coming to a stop…")
                }
            }
        }

        if (next != status) {
            status = next
            listener?.onStatus(next)
        }
    }

    private fun noDataDetail(): String {
        val missing = mutableListOf<String>()
        if (!hasCarSpeedPermission()) missing += "Car speed"
        if (!hasLocationPermission()) missing += "Location"
        val base = if (missing.isNotEmpty()) {
            "Open StayAwake Auto on your phone and grant: " + missing.joinToString(", ")
        } else {
            "Waiting for vehicle speed or a GPS fix…"
        }
        return if (prefs.manualParkConfirmAllowed) "$base\nTap here to confirm you are parked" else base
    }

    companion object {
        private const val TAG = "ParkedMonitor"
        const val CAR_SPEED_PERMISSION = "com.google.android.gms.permission.CAR_SPEED"
        private const val TICK_MS = 1000L
        private const val LOCATION_INTERVAL_MS = 1000L
        private const val CAR_FRESH_MS = 10_000L
        private const val GPS_FRESH_MS = 5_000L
        private const val STOP_GRACE_MS = 1_500L
    }
}
