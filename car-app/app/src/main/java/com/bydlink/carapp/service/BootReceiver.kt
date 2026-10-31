package com.bydlink.carapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Arranca el servicio al encender el coche: arranque de Android, "quickboot" de algunos equipos,
 * avisos propios de BYD al dar contacto (ACC/IGN), desbloqueo y tras actualizarse la app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("BYDLink", "arranque por: ${intent?.action}")
        try {
            ContextCompat.startForegroundService(context, Intent(context, MainService::class.java))
        } catch (e: Throwable) {
            // Android 12+ no deja arrancar servicios en primer plano desde algunos avisos si la app no
            // está exenta de ahorro de batería. El vigilante y el job periódico la levantan igualmente.
            Log.e("BYDLink", "no se pudo arrancar el servicio desde ${intent?.action}", e)
        }
    }
}
