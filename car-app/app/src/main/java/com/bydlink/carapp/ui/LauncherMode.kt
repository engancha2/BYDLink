package com.bydlink.carapp.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.car.CarShell

/**
 * BYDLink como pantalla de inicio, a elección del usuario. El alias HomeAlias (categoría HOME) está desactivado de
 * fábrica: así BYDLink no aparece como lanzador hasta que se pide. Al activarlo se recuerda el lanzador que había
 * (el de BYD, Beetle...) y se pone BYDLink por defecto con el ADB del coche; al desactivarlo se devuelve el anterior.
 */
object LauncherMode {
    private const val TAG = "BYDLink"
    private const val PREFS = "launcher"
    private const val STOCK_HOME = "com.android.launcher3/com.android.launcher3.home.MainActivity"

    private fun alias(ctx: Context) = ComponentName(ctx.packageName, "com.bydlink.carapp.ui.HomeAlias")

    fun isOn(ctx: Context): Boolean =
        ctx.packageManager.getComponentEnabledSetting(alias(ctx)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** Lanzador por defecto ahora mismo ("paquete/actividad") o null si no hay uno fijado. */
    private fun currentHome(ctx: Context): String? {
        val ri = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?: return null
        val a = ri.activityInfo ?: return null
        if (a.packageName == "android") return null              // el selector: no hay ninguno por defecto
        return "${a.packageName}/${a.name}"
    }

    /** Con el lanzador elegido, comprobar que sigue siendo el de la casita y, si no, volver a ponerlo (por ADB). */
    fun reassert(ctx: Context, sh: CarShell) {
        if (!isOn(ctx)) return
        val me = alias(ctx).flattenToString()
        val cur = currentHome(ctx)
        if (cur == me || cur == "${ctx.packageName}/${ctx.packageName}.ui.HomeActivity") return
        sh.shell("cmd package set-home-activity $me")
        Log.i(TAG, "lanzador: DiLink lo había quitado ($cur); vuelto a poner")
    }

    /** Activa o desactiva. Devuelve un texto para enseñar al usuario. Se llama fuera del hilo principal. */
    fun set(ctx: Context, on: Boolean): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pm = ctx.packageManager
        val me = alias(ctx)
        val sh = CarShell(ctx.filesDir)
        return try {
            val adb = sh.connect(BuildConfigCompat.adbHost(ctx)) && sh.works()
            if (on) {
                currentHome(ctx)?.takeIf { !it.startsWith(ctx.packageName) }?.let { prefs.edit().putString("previous", it).apply() }
                pm.setComponentEnabledSetting(me, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                if (adb) sh.shell("cmd package set-home-activity ${me.flattenToString()}")
                Log.i(TAG, "lanzador: activado (antes ${prefs.getString("previous", "?")})")
                if (adb) "BYDLink es ahora la pantalla de inicio. Pulsa la casita para verla."
                else "Activado. Al pulsar la casita, elige BYDLink y «Siempre»."
            } else {
                val prev = prefs.getString("previous", null) ?: STOCK_HOME
                if (adb) sh.shell("cmd package set-home-activity $prev")
                pm.setComponentEnabledSetting(me, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                Log.i(TAG, "lanzador: desactivado, vuelve $prev")
                "Vuelve tu pantalla de inicio de antes."
            }
        } catch (e: Throwable) {
            Log.e(TAG, "lanzador: fallo", e); "No se pudo cambiar: ${e.message}"
        } finally { sh.close() }
    }
}
