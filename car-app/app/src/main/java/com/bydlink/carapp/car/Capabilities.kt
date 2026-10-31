package com.bydlink.carapp.car

import android.content.Context
import android.util.Log
import com.bydlink.carapp.App
import com.google.firebase.database.ServerValue

/**
 * Qué tiene ESTE coche (multimodelo): se publica en cars/{id}/caps y la web oculta lo que no hay.
 *  - true  = lo tiene (o el mando funcionó),
 *  - false = no lo tiene (el coche responde "no disponible" o el mando se probó y no hace nada),
 *  - ausente = no se sabe (la web lo enseña).
 * Dos fuentes: lo que se detecta leyendo el SDK al arrancar y lo aprendido de las órdenes (se guarda).
 */
object Capabilities {
    private const val TAG = "BYDLink"
    private const val PREFS = "caps"

    fun detect(ctx: Context): Map<String, Boolean> {
        val pctx = BydPermissionContext(ctx.applicationContext)
        fun dev(s: String) = BydReflect.device(pctx, "android.hardware.bydauto.$s")
        val ac = dev("ac.BYDAutoAcDevice")
        val body = dev("bodywork.BYDAutoBodyworkDevice")
        val setting = dev("setting.BYDAutoSettingDevice")
        val charge = dev("charging.BYDAutoChargingDevice")
        fun ok(v: Int?) = BydReflect.san(v) != null
        fun has(obj: Any?, m: String) = obj?.javaClass?.methods?.any { it.name == m } == true
        val r = LinkedHashMap<String, Boolean>()
        fun put(k: String, f: () -> Boolean) { try { r[k] = f() } catch (e: Throwable) { Log.w(TAG, "capacidad $k: ${e.message}") } }

        if (ac != null) put("climate") { ok(BydReflect.callInt(ac, "getAcStartState")) }
        // Asientos: el coche responde 65535 / "no soportado" si no tiene calefacción o ventilación.
        if (setting != null) {
            put("seatHeat") { (1..2).any { ok(BydReflect.callIntArg(setting, "getSeatHeatingState", it)) } }
            put("seatVent") { (1..2).any { ok(BydReflect.callIntArg(setting, "getSeatVentilatingState", it)) } }
            put("wheelHeat") { ok(BydReflect.callInt(setting, "getSteeringWheelHeatingState")) }
        }
        // Memorias de asiento: solo con el dispositivo de asientos (el Surf no lo tiene).
        put("seatMemory") { dev("seat.BYDAutoSeatDevice") != null }
        if (body != null) {
            put("sunroof") { (BydReflect.san(BydReflect.callInt(body, "getMoonRoofConfig")) ?: 0) > 0 ||
                ok(BydReflect.callInt(body, "getSunroofPosition")) }
            put("electricTrunk") { ok(BydReflect.callInt(body, "getBackDoorElectricMode")) ||
                ok(BydReflect.callInt(setting, "getBackDoorElectricMode")) }
        }
        if (charge != null) put("chargeLimit") { has(charge, "setChargeStopCapacityState") || has(setting, "setSOCTarget") }
        return r
    }

    /** Lo aprendido al usar un mando (p. ej. el claxon que el coche acepta pero no hace). */
    fun learn(ctx: Context, key: String, value: Boolean, carId: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.contains(key) && p.getBoolean(key, !value) == value) return
        p.edit().putBoolean(key, value).apply()
        Log.i(TAG, "capacidad aprendida: $key = $value")
        App.db.getReference("cars/$carId/caps/$key").setValue(value)
    }

    private fun learned(ctx: Context): Map<String, Boolean> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (k, v) -> (v as? Boolean)?.let { k to it } }.toMap()

    /** Lo mismo que se publica, para las pantallas de la propia app (lanzador). Pregunta al SDK: fuera del hilo principal. */
    fun local(ctx: Context): Map<String, Boolean> = detect(ctx) + learned(ctx)

    /** Detecta, añade lo aprendido (manda lo aprendido: es una prueba real) y lo publica. */
    fun publish(ctx: Context, carId: String) {
        val caps: MutableMap<String, Any?> = (detect(ctx) + learned(ctx)).toMutableMap()
        caps["ts"] = ServerValue.TIMESTAMP
        App.db.getReference("cars/$carId/caps").setValue(caps)
        Log.i(TAG, "capacidades: $caps")
    }
}
