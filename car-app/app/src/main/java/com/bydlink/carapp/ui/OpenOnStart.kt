package com.bydlink.carapp.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.car.CarShell
import com.bydlink.carapp.service.StateHub
import java.util.concurrent.Executors

/**
 * "Abrir BYDLink al arrancar el coche": al dar el contacto, poner delante la pantalla de BYDLink (el lanzador).
 * Android 10 no deja a una app abrir pantallas desde segundo plano, pero el ADB del coche sí (`am start`): abre
 * WakeActivity (exportada con permiso DUMP, que tiene el shell) y esa, ya delante, abre HomeActivity.
 * Solo al pasar de apagado a encendido o cuando BYD nos arranca por el contacto; nunca a mitad de un viaje
 * (p. ej. tras una actualización) y como mucho una vez cada 10 minutos.
 */
object OpenOnStart {
    private const val TAG = "BYDLink"
    private const val PREFS = "launcher"
    private const val KEY = "openOnStart"
    private const val MIN_GAP_MS = 10 * 60_000L
    /** Avisos con los que BootReceiver arranca el servicio que sí significan "se acaba de encender el coche". */
    val START_REASONS = setOf("com.byd.action.ACC_ON", "com.byd.action.IGN_ON", "android.intent.action.BOOT_COMPLETED",
        "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON")

    @Volatile private var lastOpen = 0L
    private val exec = Executors.newSingleThreadExecutor()

    fun enabled(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    fun set(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()

    fun request(ctx: Context, why: String) {
        val app = ctx.applicationContext
        if (!enabled(app)) return
        val now = SystemClock.elapsedRealtime()
        if (lastOpen > 0 && now - lastOpen < MIN_GAP_MS) return
        if ((StateHub.state?.speed ?: 0) > 5) return
        lastOpen = now
        exec.execute {
            try {
                Thread.sleep(4000)   // que DiLink termine de poner su pantalla de arranque, si no la nuestra queda detrás
                if ((StateHub.state?.speed ?: 0) > 5) return@execute
                val sh = CarShell(app.filesDir)
                try {
                    if (sh.connect(BuildConfigCompat.adbHost(app)) && sh.works()) {
                        sh.shell("am start -n ${app.packageName}/.ui.WakeActivity --es open home")
                        Log.i(TAG, "abrir al arrancar ($why): BYDLink delante")
                    } else Log.w(TAG, "abrir al arrancar ($why): sin ADB")
                } finally { sh.close() }
            } catch (e: Throwable) { Log.w(TAG, "abrir al arrancar: $e") }
        }
    }
}
