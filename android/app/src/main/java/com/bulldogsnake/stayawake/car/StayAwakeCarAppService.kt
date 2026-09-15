package com.bulldogsnake.stayawake.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Entry point Android Auto binds to. */
class StayAwakeCarAppService : CarAppService() {

    // Personal-use app installed outside the Play Store: accept any host (Android Auto, DHU).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = StayAwakeSession()
}
