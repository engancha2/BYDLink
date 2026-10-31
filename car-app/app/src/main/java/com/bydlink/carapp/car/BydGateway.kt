package com.bydlink.carapp.car

import android.content.Context
import com.bydlink.carapp.model.*

/**
 * Implementación real sobre el SDK de BYD, TODO por reflexión (ver [BydReflect]) para que
 * funcione en el máximo de modelos/versiones de DiLink. Cada lectura prueba varios nombres
 * de método y cae a un valor por defecto si no existe. El control va con try/catch.
 *
 * ⚠️ Las firmas de CONTROL (cerrar, ventanas, clima, asientos) son best-effort según la
 * documentación del SDK y las apps de referencia; hay que CONFIRMARLAS en el coche con el
 * diagnóstico antes de confiar en ellas. Las LECTURAS son seguras (no actúan sobre el coche).
 *
 * La ubicación GPS no viene del SDK BYD sino del Android del head unit: la inyecta el
 * servicio con [lastLocation] (ver LocationReader).
 */
class BydGateway(private val ctx: Context) : CarGateway {

    @Volatile var lastLocation: Location = Location()

    private val ac by lazy { BydReflect.device(ctx, "android.hardware.bydauto.ac.BYDAutoAcDevice") }
    private val body by lazy { BydReflect.device(ctx, "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice") }
    private val charge by lazy { BydReflect.device(ctx, "android.hardware.bydauto.charging.BYDAutoChargingDevice") }
    private val gearbox by lazy { BydReflect.device(ctx, "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice") }
    private val instrument by lazy { BydReflect.device(ctx, "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice") }
    private val speed by lazy { BydReflect.device(ctx, "android.hardware.bydauto.speed.BYDAutoSpeedDevice") }
    private val tyre by lazy { BydReflect.device(ctx, "android.hardware.bydauto.tyre.BYDAutoTyreDevice") }
    private val statistic by lazy { BydReflect.device(ctx, "android.hardware.bydauto.statistic.BYDAutoStatisticDevice") }

    private fun gearName(code: Int?) = when (code) { 1 -> "R"; 2 -> "N"; 3 -> "D"; 0 -> "P"; else -> "P" }

    override fun readState(): CarState {
        val soc = BydReflect.callInt(body, "getBatteryCapacity")
            ?: BydReflect.callInt(charge, "getBatterySoc", "getBatteryPercent", "getSoc")
            ?: BydReflect.callInt(statistic, "getSoc") ?: 0
        val charging = BydReflect.callBool(charge, "isCharging")
            ?: ((BydReflect.callInt(charge, "getChargerState", "getChargingState") ?: 0) > 0)
        return CarState(
            ts = System.currentTimeMillis(),
            soc = soc,
            charging = charging,
            chargeRestMin = BydReflect.callInt(charge, "getChargingRestTime") ?: 0,
            gear = gearName(BydReflect.callInt(gearbox, "getGearboxAutoModeType", "getGear")),
            speed = (BydReflect.callFloat(speed, "getCurrentSpeed", "getSpeed") ?: 0f).toInt(),
            locked = BydReflect.callBool(body, "isLocked", "getLockState") ?: true,
            doors = readDoors(),
            windows = readWindows(),
            tyres = readTyres(),
            seatbelts = Seatbelts(
                driver = (BydReflect.callIntArg(instrument, "getSafetyBeltStatus", 0) ?: 0) == 1,
                passenger = (BydReflect.callIntArg(instrument, "getSafetyBeltStatus", 1) ?: 0) == 1
            ),
            outTemp = (BydReflect.callIntArg(ac, "getTemprature", 4) ?: BydReflect.callInt(ac, "getOutCarTemperature") ?: 0),
            tempUnit = if ((BydReflect.callInt(ac, "getTemperatureUnit") ?: 1) == 0) "F" else "C",
            odometer = BydReflect.callInt(statistic, "getOdometer", "getTotalMileage", "getMileage")
                ?: BydReflect.callInt(body, "getOdometer") ?: 0,
            rangeKm = BydReflect.callInt(statistic, "getRange", "getRemainingRange", "getEnduranceMileage") ?: (soc * 4),
            power = BydReflect.callInt(statistic, "getInstantPower", "getPower") ?: 0,
            voltage = BydReflect.callInt(charge, "getBatteryPowerVoltage", "getBatteryVoltage") ?: 0,
            location = lastLocation
        )
    }

    // getDoorState(area): 0=cerrada, 1=abierta (áreas por confirmar). Si hay un getter sin arg
    // que devuelve bitmask, se usa; si no, por área.
    private fun readDoors(): Doors {
        fun d(area: Int) = (BydReflect.callIntArg(body, "getDoorState", area) ?: 0) == 1
        return Doors(d(1), d(3), d(2), d(4), d(5), d(6))
    }
    private fun readWindows(): Windows {
        fun w(area: Int) = (BydReflect.callIntArg(body, "getWindowState", area) ?: 0) == 1
        return Windows(w(1), w(3), w(2), w(4), (BydReflect.callInt(body, "getSunroofState") ?: 0) == 1)
    }
    private fun readTyres(): Tyres {
        fun t(wheel: Int) = ((BydReflect.callIntArg(tyre, "getTyrePressure", wheel)
            ?: BydReflect.callIntArg(tyre, "getTirePressure", wheel) ?: 0).toDouble() / 100.0) // kPa→bar aprox; ajustar
        return Tyres(t(0), t(1), t(2), t(3))
    }

    override fun observe(onChange: (CarState) -> Unit) {
        // Los listeners del SDK (AbsBYDAuto*Listener) se registrarían aquí por reflexión; de
        // momento el servicio hace sondeo periódico con readState(), que es suficiente.
    }
    override fun stop() { /* unregister listeners si se registraron */ }

    // -------- Control (best-effort; verificar en el coche) --------
    override fun setLock(locked: Boolean) {
        if (BydReflect.call(body, "lockDevice", locked) == null) BydReflect.call(body, "lockDevice")
    }
    override fun setWindows(open: Boolean) {
        val st = if (open) 1 else 0
        // intento 1: todas a la vez; intento 2: por área 1..4 (source=1)
        if (BydReflect.call(body, "setAllWindowState", st, 1) == null &&
            BydReflect.call(body, "setAllWindowState", st) == null) {
            for (area in 1..4) BydReflect.call(body, "setBodyWindowCtrlState", area, st)
        }
    }
    override fun setClimate(on: Boolean, temp: Int) {
        if (on) { if (BydReflect.call(ac, "start", 0) == null) BydReflect.call(ac, "setAcState", true) }
        else { if (BydReflect.call(ac, "stop", 0) == null) BydReflect.call(ac, "setAcState", false) }
        // zona 1 (conductor), source=1, param4=1 (según ref byd-apps). Si no, setTemperature(temp).
        if (BydReflect.call(ac, "setAcTemperature", 1, temp, 1, 1) == null)
            BydReflect.call(ac, "setTemperature", temp.toFloat())
    }
    override fun setSeatComfort(driver: Int?, passenger: Int?) {
        driver?.let { applySeat(0, it) }
        passenger?.let { applySeat(1, it) }
    }
    private fun applySeat(pos: Int, stage: Int) {
        if (stage >= 0) BydReflect.call(instrument, "setSeatHeatingState", pos, stage)
        else BydReflect.call(instrument, "setSeatVentilatingState", pos, -stage)
    }
    override fun loadSeatMemory(slot: Int) { BydReflect.call(instrument, "loadSeatMemory", slot) }
}
