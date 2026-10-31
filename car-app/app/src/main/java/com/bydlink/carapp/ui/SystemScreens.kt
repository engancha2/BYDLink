package com.bydlink.carapp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Abrir pantallas del sistema en DiLink. Casi todos los ajustes estándar de Android llevan aquí a una página
 * de BYD de "no compatible" (com.byd.carsettings/...UnsupportActivity): se prueban en orden y esa se salta.
 */
object SystemScreens {
    /**
     * Herramientas de desarrollador de BYD (com.byd.byddevelopmenttools): sus pantallas de ADB son privadas y
     * solo se llega por la de código de verificación. Después, el ajuste estándar si este equipo lo tiene.
     */
    fun devOptions(ctx: Context): Boolean = openFirst(ctx,
        Intent("android.byd.intent.action.INPUT_CODE_VERIFICATION"),
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        *settingsIntents(ctx))

    /** Los ajustes de BYD (su app propia) o, en otros equipos, los de Android. */
    fun systemSettings(ctx: Context): Boolean = openFirst(ctx, *settingsIntents(ctx))

    /** La ficha de BYDLink en ajustes; en DiLink, la lista de apps de BYD. */
    fun appDetails(ctx: Context): Boolean = openFirst(ctx,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
        Intent().setClassName(BYD_SETTINGS, "com.byd.systemsettings.applications2.view.ApplicationListActivity"),
        *settingsIntents(ctx))

    private const val BYD_SETTINGS = "com.byd.carsettings"

    private fun settingsIntents(ctx: Context): Array<Intent> =
        listOfNotNull(ctx.packageManager.getLaunchIntentForPackage(BYD_SETTINGS), Intent(Settings.ACTION_SETTINGS)).toTypedArray()

    private fun openFirst(ctx: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            val a = ctx.packageManager.resolveActivity(i, 0)?.activityInfo ?: continue
            if (a.name.contains("unsupport", ignoreCase = true) || !a.exported) continue
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (e: Throwable) {}
        }
        return false
    }
}
