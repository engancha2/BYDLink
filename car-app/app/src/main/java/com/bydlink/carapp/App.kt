package com.bydlink.carapp

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.bydlink.carapp.service.MainService

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Arranca el servicio en cuanto la app se crea.
        ContextCompat.startForegroundService(this, Intent(this, MainService::class.java))
    }
}
