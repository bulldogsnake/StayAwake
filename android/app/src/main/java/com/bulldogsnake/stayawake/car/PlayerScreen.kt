package com.bulldogsnake.stayawake.car

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bulldogsnake.stayawake.R
import com.bulldogsnake.stayawake.safety.LocationForegroundService
import com.bulldogsnake.stayawake.safety.ParkedMonitor

/**
 * The only template the car shows: a NavigationTemplate whose "map" surface is our WebView.
 * The action strip offers Home, Search, Type, Back; the map strip offers Pan and Reload.
 */
class PlayerScreen(
    carContext: CarContext,
    private val browser: CarBrowser,
    private val monitor: ParkedMonitor,
) : Screen(carContext) {

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            browser.attach(surfaceContainer)
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            browser.detach()
        }

        override fun onClick(x: Float, y: Float) {
            browser.onClick(x, y)
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            browser.onScroll(distanceX, distanceY)
        }

        override fun onFling(velocityX: Float, velocityY: Float) {
            browser.onFling()
        }

        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
            // Pinch-zoom is intentionally ignored; the pages are laid out to fit.
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
                monitor.listener = ParkedMonitor.Listener { status -> browser.setStatus(status) }
                browser.setStatus(monitor.status)
                browser.onManualParkConfirm = { monitor.confirmParkedManually() }
                monitor.start()
                LocationForegroundService.startIfPermitted(carContext)
                requestPermissionsIfNeeded()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                monitor.stop()
                browser.detach()
                LocationForegroundService.stop(carContext)
                runCatching { carContext.getCarService(AppManager::class.java).setSurfaceCallback(null) }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val actionStrip = ActionStrip.Builder()
            .addAction(iconAction(R.drawable.ic_home) { browser.loadStartPage() })
            .addAction(iconAction(R.drawable.ic_search) {
                screenManager.push(SearchScreen(carContext, "Search") { query -> browser.search(query) })
            })
            .addAction(iconAction(R.drawable.ic_keyboard) {
                screenManager.push(SearchScreen(carContext, "Type into the page") { text -> browser.typeText(text) })
            })
            .addAction(iconAction(R.drawable.ic_arrow_back) { browser.goBack() })
            .build()

        val mapActionStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(iconAction(R.drawable.ic_refresh) { browser.reload() })
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .setMapActionStrip(mapActionStrip)
            .setPanModeListener { _ -> /* scroll events arrive while pan mode is on */ }
            .build()
    }

    private fun iconAction(@DrawableRes icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener { onClick() }
            .build()

    private fun requestPermissionsIfNeeded() {
        val wanted = listOf(ParkedMonitor.CAR_SPEED_PERMISSION, Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(carContext, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        try {
            carContext.requestPermissions(missing) { _, _ ->
                monitor.restartSources()
                LocationForegroundService.startIfPermitted(carContext)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Permission request failed", t)
        }
    }

    companion object {
        private const val TAG = "PlayerScreen"
    }
}
