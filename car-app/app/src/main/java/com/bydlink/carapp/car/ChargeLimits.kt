package com.bydlink.carapp.car

import android.content.Context
import android.util.Log

/**
 * Límite de carga de BYD (`BYDAutoChargingDevice.setChargeStopCapacityState`): no es un porcentaje sino un código,
 * el mismo que usan los Ajustes del coche (McuUiStateChangeUtils.chargeLimitUiState2McuState).
 * Lo último que se pidió se guarda, porque algunos modelos (el Surf) no lo devuelven al leer.
 *
 * Multimodelo: si en una carga real la batería pasa del límite pedido, este coche no lo respeta (el Surf aceptó el 80 %
 * y cargó hasta el 96 % la noche del 31/10/2026) y se recuerda para decirlo con claridad en la web y en el lanzador.
 */
object ChargeLimits {
    private val CODES = mapOf(100 to 1, 90 to 2, 80 to 3, 70 to 4, 60 to 5)
    /** Margen sobre el límite antes de dar por hecho que el coche lo ignora (el SOC sube a saltos de 1 %). */
    private const val MARGIN = 2

    fun codeOf(percent: Int): Int = CODES[percent] ?: 1
    fun percentOf(code: Int?): Int? = CODES.entries.firstOrNull { it.value == code }?.key

    /** Lo que informa el coche, o null si no lo da (65535 en el Surf). */
    fun read(charge: Any?): Int? = percentOf(BydReflect.san(BydReflect.callInt(charge, "getChargeStopCapacityState")))

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("charge", Context.MODE_PRIVATE)

    fun remember(ctx: Context, percent: Int) = prefs(ctx).edit().putInt("limit", percent).apply()

    fun remembered(ctx: Context): Int? = prefs(ctx).getInt("limit", 0).takeIf { it > 0 }

    /** true si ya se vio al coche cargar por encima del límite; null si aún no se sabe. */
    fun ignored(ctx: Context): Boolean? = if (prefs(ctx).contains("ignored")) prefs(ctx).getBoolean("ignored", false) else null

    /** Se llama en cada lectura: aprende si el coche respeta el límite viendo una carga real. */
    fun learn(ctx: Context, limit: Int?, confirmed: Boolean, soc: Int?, charging: Boolean) {
        val p = prefs(ctx)
        if (confirmed) { if (p.contains("ignored")) p.edit().remove("ignored").apply(); return }   // lo informa: se fía de él
        if (limit == null || limit >= 100 || soc == null || !charging) return
        if (soc >= limit + MARGIN && p.getBoolean("ignored", false).not()) {
            Log.w("BYDLink", "límite de carga: cargando al $soc % con el límite al $limit % → el coche no lo respeta")
            p.edit().putBoolean("ignored", true).putLong("ignoredTs", System.currentTimeMillis()).apply()
        }
    }
}
