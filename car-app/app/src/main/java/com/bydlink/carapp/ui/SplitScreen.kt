package com.bydlink.carapp.ui

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.car.CarShell

/**
 * Dos apps en pantalla dividida (Android 10): la primera en el modo "pantalla dividida principal" y la segunda se
 * abre después, que Android coloca en la otra mitad. Idea de homeo26/byd-split-launcher (MIT), hecha por ADB del coche
 * (`am start --windowingMode 3`) porque la API equivalente es interna. Un lanzador no puede ir en una de las mitades.
 * Llamar fuera del hilo principal. Devuelve un texto para el usuario.
 */
object SplitScreen {
    private const val TAG = "BYDLink"

    fun open(ctx: Context, primary: ComponentName, secondary: ComponentName?): String {
        val sh = CarShell(ctx.filesDir)
        return try {
            if (!sh.connect(BuildConfigCompat.adbHost(ctx)) || !sh.works()) return "Hace falta el ADB del coche para dividir la pantalla."
            val r1 = sh.shell("am start --windowingMode 3 -n ${primary.flattenToShortString()}")
            Log.i(TAG, "pantalla dividida: ${primary.flattenToShortString()} → ${r1?.trim()}")
            if (secondary != null) {
                Thread.sleep(900)
                sh.shell("am start -n ${secondary.flattenToShortString()}")
            }
            if (r1?.contains("Error") == true) "Este coche no deja dividir la pantalla así." else "Pantalla dividida"
        } catch (e: Throwable) {
            Log.e(TAG, "pantalla dividida", e); "No se pudo dividir la pantalla"
        } finally { sh.close() }
    }
}
