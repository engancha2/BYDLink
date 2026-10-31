package com.bydlink.carapp.car

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Energía y conexión con el coche APAGADO. Lo usa el servicio para intentar mantener el
 * head-unit despierto cuando el coche entra en ACC-off, como hacen Electro/SentryEV.
 *
 * Todo por REFLEXIÓN y con try/catch: si una API no existe en este modelo, se devuelve qué
 * funcionó y qué no, sin romper nada. NO toca el binder `accmodemanager` (`acquireAccLock`):
 * eso lo valora el servicio aparte.
 */
class PowerControl(private val ctx: Context) {

    private companion object {
        const val TAG = "BYDLink"
        // IDs de alimentación de periféricos del BYDAutoSpecialDevice (mantener WiFi/USB aparcado),
        // vistos en SentryEV: 0x2ea0000f y 0x2ea00020.
        val PERIPH_IDS = intArrayOf(0x2ea0000f, 0x2ea00020)
    }

    private val pctx = BydPermissionContext(ctx.applicationContext)
    private fun dev(suffix: String) = BydReflect.device(pctx, "android.hardware.bydauto.$suffix")
    private val power by lazy { dev("power.BYDAutoPowerDevice") }
    private val special by lazy { dev("special.BYDAutoSpecialDevice") }
    private val body by lazy { dev("bodywork.BYDAutoBodyworkDevice") }
    private val ota by lazy { dev("ota.BYDAutoOtaDevice") }

    /** Tensión de la batería auxiliar de 12 V (para el gobernador de energía: cortar vigilancia
     *  si baja de ~12.0 V y no dejar el coche sin arranque). null si no se puede leer. */
    fun readAux12V(): Double? {
        return try {
            BydReflect.callDouble(ota, "getBatteryPowerVoltage", "getBatteryVoltage")?.let { BydReflect.sanD(it) }
                ?: BydReflect.callDouble(power, "getBatteryVoltage", "getBatteryPowerVoltage")?.let { BydReflect.sanD(it) }
                ?: BydReflect.featureDouble(body, BydReflect.fid("Ota.OTA_BATTERY_POWER_VOLTAGE", 1128267816))
                ?: BydReflect.featureDouble(ota, BydReflect.fid("Ota.OTA_BATTERY_POWER_VOLTAGE", 1128267816))
        } catch (e: Throwable) { null }
    }

    /**
     * ¿Contacto puesto (ACC/ON)? Primero el servicio `accmodemanager` del sistema (getAccModeStatus,
     * transacción 3, sin permisos): lo responde Android, no el coche, así que no se cuelga con el coche
     * dormido. Si no existe, el powerLevel del SDK (∈ {ACC,ON,OK} = sí, OFF = no). null si n/d.
     */
    fun readAccOn(): Boolean? {
        accModeStatus()?.let { return it == 1 }
        val lvl = BydReflect.san(BydReflect.callInt(body, "getPowerLevel"))
        return when (lvl) { 0 -> false; 1, 2, 3 -> true; else -> null }
    }

    private fun accModeStatus(): Int? {
        val b = try {
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "accmodemanager") as? android.os.IBinder
        } catch (e: Throwable) { null } ?: return null
        val data = android.os.Parcel.obtain(); val reply = android.os.Parcel.obtain()
        return try {
            data.writeInterfaceToken(b.interfaceDescriptor ?: "")
            if (!b.transact(3, data, reply, 0)) return null
            reply.readException(); reply.readInt()
        } catch (e: Throwable) { null } finally { data.recycle(); reply.recycle() }
    }

    /**
     * Intenta mantener el head-unit despierto con el coche en ACC-off, probando varias vías por
     * reflexión. Devuelve un mapa con lo que funcionó (para depurar/telemetría). No lanza.
     */
    fun keepAwake(on: Boolean): Map<String, Any?> {
        val r = LinkedHashMap<String, Any?>()
        // 1) BYDAutoPowerDevice.setKeepWakeUpAfterAccOff(boolean)
        r["setKeepWakeUpAfterAccOff"] = tryBoolMethod(power, "setKeepWakeUpAfterAccOff", on)
        // 2) wakeUpMcu() (solo al activar)
        if (on) r["wakeUpMcu"] = (BydReflect.call(power, "wakeUpMcu") != null)
        // 3) PowerManager.wakeUp(long, int=9 ACC_ON) (reflexión sobre el servicio del sistema)
        if (on) r["wakeUp"] = tryWakeUp()
        // 4) BYDAutoSpecialDevice.setSpecialConfig(id, val) + set(int[]{id}, EventValue) para periféricos
        var periph = false
        for (id in PERIPH_IDS) {
            if (BydReflect.call(special, "setSpecialConfig", id, if (on) 3 else 0) != null) periph = true
            if (BydReflect.setFeature(special, id, if (on) 3 else 0) != null) periph = true
        }
        r["peripheralPower"] = periph
        Log.i(TAG, "keepAwake($on) -> $r")
        return r
    }

    private fun tryBoolMethod(obj: Any?, name: String, value: Boolean): Boolean {
        if (obj == null) return false
        return try {
            val m = obj.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == 1 } ?: return false
            m.isAccessible = true
            m.invoke(obj, value); true
        } catch (e: Throwable) { false }
    }

    private fun tryWakeUp(): Boolean {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            val m = pm.javaClass.methods.firstOrNull {
                it.name == "wakeUp" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Long::class.javaPrimitiveType && it.parameterTypes[1] == Int::class.javaPrimitiveType
            } ?: return false
            m.isAccessible = true
            m.invoke(pm, android.os.SystemClock.uptimeMillis(), 9) // reason 9 = ACC_ON
            true
        } catch (e: Throwable) { false }
    }
}
