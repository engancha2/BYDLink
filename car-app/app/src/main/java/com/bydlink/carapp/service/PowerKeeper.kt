package com.bydlink.carapp.service

import android.content.Context
import android.util.Log
import com.bydlink.carapp.car.PowerControl
import com.bydlink.carapp.model.CarState
import com.google.firebase.database.ServerValue

/**
 * Seguir conectado con el coche apagado (como Electro y SentryEV) SIN dejarlo sin batería.
 *
 * Con el contacto quitado pide al coche que no duerma la pantalla/MCU y mantenga WiFi/datos
 * ([PowerControl.keepAwake]). Un gobernador lo suelta (deja que el coche duerma) si:
 *  - la batería de 12 V baja de [min12v] (vuelve a permitirlo al recuperar [resume12v]): un 12 V vacío
 *    deja el coche sin poder arrancar;
 *  - la batería principal baja de [minSoc] %;
 *  - pasan más de [maxHours] horas apagado.
 * Ajustes en config.power (los cambia el móvil). Estado publicado en meta/power.
 */
class PowerKeeper(
    ctx: Context, carId: String,
    private val config: () -> Map<String, Any?>,
    /** Retener o soltar el wakelock del servicio: es lo que de verdad impide que el coche se duerma. */
    private val hold: (Boolean) -> Unit,
    /** false mientras el SDK del coche no responde: no se le pregunta (se colgaría) y los 12 V no se saben. */
    private val sdkOk: () -> Boolean,
) {

    private val power = PowerControl(ctx.applicationContext)
    private val ref = com.bydlink.carapp.App.db.getReference("cars/$carId/meta/power")
    private var offSince = 0L
    private var keeping = false          // pidiendo al coche (SDK) que no duerma
    private var holding = true           // el servicio arranca con el wakelock cogido
    private var last = ""

    private fun num(k: String, def: Double) = (config()[k] as? Number)?.toDouble() ?: def
    private val enabled get() = config()["keepAwake"] as? Boolean ?: true
    private val maxHours get() = num("maxHours", 12.0)
    private val minSoc get() = num("minSoc", 20.0)
    private val min12v get() = num("min12v", 12.0)
    private val resume12v get() = num("resume12v", 12.5)

    /** Se llama periódicamente (cada ~30 s) con el último estado leído. */
    fun tick(s: CarState?) {
        try {
            val accOn = power.readAccOn() ?: true      // si no se sabe, no tocar nada
            val v12 = if (sdkOk()) power.readAux12V() else null
            val soc = s?.soc?.takeIf { it in 0..100 }?.toDouble()
            val now = System.currentTimeMillis()
            var reason: String? = null
            if (accOn) {
                offSince = 0L
                if (keeping) set(false)          // con el coche encendido no hace falta nada
                holdLock(true)
            } else {
                if (offSince == 0L) offSince = now
                reason = when {
                    !enabled -> "desactivado"
                    v12 != null && v12 < (if (keeping) min12v else resume12v) -> "12V bajos"
                    soc != null && soc < minSoc -> "batería baja"
                    // Sin poder leer los 12 V no hay red de seguridad: como mucho 4 horas.
                    now - offSince > (if (v12 == null) minOf(maxHours, 4.0) else maxHours) * 3_600_000 -> "tiempo máximo"
                    else -> null
                }
                if (reason == null && !keeping) set(true)
                if (reason != null && keeping) set(false)
                holdLock(reason == null)         // al llegar a un límite se suelta y el coche se duerme
            }
            publish(accOn, v12, reason)
        } catch (e: Throwable) { Log.e(TAG, "energía: fallo", e) }
    }

    private fun set(on: Boolean) {
        val r = if (!sdkOk()) mapOf("sdk" to "no responde")
            else try { power.keepAwake(on) } catch (e: Throwable) { mapOf("error" to (e.message ?: e.toString())) }
        keeping = on
        Log.i(TAG, "energía: mantener despierto = $on → $r")
    }

    private fun holdLock(on: Boolean) {
        if (on == holding) return
        hold(on); holding = on
        Log.i(TAG, "energía: wakelock = $on")
    }

    private fun publish(accOn: Boolean, v12: Double?, reason: String?) {
        val awake = !accOn && holding            // lo que de verdad pasa: coche apagado y despierto por nosotros
        val key = "$accOn|$awake|$reason|${v12?.let { "%.1f".format(it) }}"
        if (key == last) return                  // solo cuando cambia algo
        last = key
        ref.setValue(mapOf(
            "accOn" to accOn, "keepAwake" to awake, "offSince" to offSince.takeIf { it > 0 },
            "aux12v" to v12, "reason" to reason, "ts" to ServerValue.TIMESTAMP))
    }

    private companion object { const val TAG = "BYDLink" }
}
