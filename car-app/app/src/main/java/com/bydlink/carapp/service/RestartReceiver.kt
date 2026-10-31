package com.bydlink.carapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Relanza el servicio cuando el watchdog (AlarmManager) lo dispara. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ContextCompat.startForegroundService(context, Intent(context, MainService::class.java))
    }
}
