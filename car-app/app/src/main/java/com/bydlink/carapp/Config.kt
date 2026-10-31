package com.bydlink.carapp

import android.content.Context
import java.util.UUID

/**
 * Configuración de la app del coche.
 *  - carId: identificador único y PERSISTENTE de ESTE coche (se genera solo la 1ª vez).
 *    El móvil se vincula a él con un código (ver PairingManager). Multiusuario.
 *  - USE_BYD_SDK: si hay SDK de BYD (coche) se lee el coche; si no (móvil, emulador), coche simulado.
 */
object BuildConfigCompat {
    /** true = lee el coche real; false = coche simulado. Se decide sola: en un móvil o el emulador no hay SDK de BYD. */
    val USE_BYD_SDK: Boolean by lazy {
        try { Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"); true } catch (e: Throwable) { false }
    }
    /** Web del móvil. El QR de la pantalla abre esta dirección con el código ya puesto. */
    const val PHONE_URL = "https://app.bydhack.com/"
    private const val PREFS = "bydlink"

    fun carId(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = sp.getString("carId", null)
        if (id.isNullOrBlank()) {
            id = "car-" + UUID.randomUUID().toString().replace("-", "").take(10)
            sp.edit().putString("carId", id).apply()
        }
        return id
    }

    fun pairCode(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pairCode", null)

    fun pairUrl(code: String) = "$PHONE_URL?code=$code"

    /** Versión de esta app (versionName de build.gradle.kts). Se ve en pantalla y en Ajustes del móvil. */
    fun appVersion(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (e: Throwable) { "?" }

    /** versionCode: el número que decide si una actualización es más nueva. */
    fun versionCode(ctx: Context): Long =
        try { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0)) }
        catch (e: Throwable) { 0L }

    /**
     * Solo para pruebas: si existe la preferencia "emuHost" (p. ej. 10.0.2.2), la app usa los
     * emuladores locales de Firebase en vez de la base de datos real. Solo se puede poner por ADB.
     */
    fun emulatorHost(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("emuHost", null)

    /** Dónde está el ADB del propio coche (en pruebas con el emulador Android, se alcanza por el PC). */
    fun adbHost(ctx: Context): String = emulatorHost(ctx) ?: "localhost"

    fun setPairCode(ctx: Context, code: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pairCode", code).apply()
}
