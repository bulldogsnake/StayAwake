package com.bulldogsnake.stayawake.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bulldogsnake.stayawake.Prefs
import com.bulldogsnake.stayawake.safety.ParkedMonitor

class StayAwakeSession : Session() {

    private var browser: CarBrowser? = null
    private var monitor: ParkedMonitor? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                monitor?.stop()
                monitor = null
                browser?.detach()
                browser = null
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        val prefs = Prefs(carContext)
        val b = CarBrowser(carContext, prefs)
        val m = ParkedMonitor(carContext, prefs)
        browser = b
        monitor = m
        return PlayerScreen(carContext, b, m)
    }
}
