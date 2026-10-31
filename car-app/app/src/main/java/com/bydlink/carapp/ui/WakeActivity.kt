package com.bydlink.carapp.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.bydlink.carapp.service.MainService

/**
 * Pantalla invisible que solo arranca el servicio y se cierra. La usa el vigilante para levantar la app:
 * el control de arranque de BYD (ssc) bloquea que otro usuario arranque servicios o avisos de una app de
 * terceros, pero no que abra una actividad; y el servicio arrancado desde aquí es "de la propia app".
 * Hace falta tras quitar el contacto, cuando DiLink fuerza la detención de todas las apps.
 */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("BYDLink", "arranque por: vigilante")
        try {
            ContextCompat.startForegroundService(this, Intent(this, MainService::class.java))
        } catch (e: Throwable) {
            Log.e("BYDLink", "no se pudo arrancar el servicio desde el vigilante", e)
        }
        finish()   // Theme.NoDisplay: hay que cerrar antes de onResume
    }
}
