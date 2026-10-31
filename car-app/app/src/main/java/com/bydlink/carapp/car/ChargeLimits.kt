package com.bydlink.carapp.car

import android.content.Context

/**
 * Límite de carga de BYD (`BYDAutoChargingDevice.setChargeStopCapacityState`): no es un porcentaje sino un código,
 * el mismo que usan los Ajustes del coche (McuUiStateChangeUtils.chargeLimitUiState2McuState).
 * Lo último que se pidió se guarda, porque algunos modelos (el Surf) no lo devuelven al leer.
 */
object ChargeLimits {
    private val CODES = mapOf(100 to 1, 90 to 2, 80 to 3, 70 to 4, 60 to 5)

    fun codeOf(percent: Int): Int = CODES[percent] ?: 1
    fun percentOf(code: Int?): Int? = CODES.entries.firstOrNull { it.value == code }?.key

    /** Lo que informa el coche, o null si no lo da (65535 en el Surf). */
    fun read(charge: Any?): Int? = percentOf(BydReflect.san(BydReflect.callInt(charge, "getChargeStopCapacityState")))

    fun remember(ctx: Context, percent: Int) =
        ctx.getSharedPreferences("charge", Context.MODE_PRIVATE).edit().putInt("limit", percent).apply()

    fun remembered(ctx: Context): Int? =
        ctx.getSharedPreferences("charge", Context.MODE_PRIVATE).getInt("limit", 0).takeIf { it > 0 }
}
