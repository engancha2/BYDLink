package com.bydlink.carapp

import android.content.Context
import java.util.UUID

/**
 * Configuración de la app del coche.
 *  - carId: identificador único y PERSISTENTE de ESTE coche (se genera solo la 1ª vez).
 *    El móvil se vincula a él con un código (ver PairingManager). Multiusuario.
 *  - USE_BYD_SDK: false para probar en móvil/emulador (coche simulado); true en el coche.
 */
object BuildConfigCompat {
    const val USE_BYD_SDK: Boolean = true   // true = lee el coche real; false = simulado (para probar sin coche)
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

    fun setPairCode(ctx: Context, code: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pairCode", code).apply()
}
