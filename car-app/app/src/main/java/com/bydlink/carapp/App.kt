package com.bydlink.carapp

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.bydlink.carapp.service.MainService

class App : Application() {
    companion object {
        /** Una sola base de datos para toda la app: con los emuladores, getInstance() puede devolver otra distinta. */
        val db: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }
    }

    override fun onCreate() {
        super.onCreate()
        com.bydlink.carapp.ui.I18n.load(this)   // idioma de la pantalla (Ajustes → Idioma)
        BuildConfigCompat.emulatorHost(this)?.let { host ->   // solo pruebas (ver BuildConfigCompat)
            db.useEmulator(host, 9000)
            FirebaseAuth.getInstance().useEmulator(host, 9099)
        }
        // Arranca el servicio en cuanto la app se crea. En Android 12+ no se deja desde segundo plano (p. ej. al
        // actualizarse sola): sin el try la app entera se cerraba; el servicio arranca luego al abrirla o al encender.
        try { ContextCompat.startForegroundService(this, Intent(this, MainService::class.java)) }
        catch (e: Exception) { android.util.Log.w("BYDLink", "servicio no arrancado ahora: ${e.message}") }
    }
}
