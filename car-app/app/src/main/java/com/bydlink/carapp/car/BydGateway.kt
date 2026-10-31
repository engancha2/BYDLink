package com.bydlink.carapp.car

import android.content.Context
import com.bydlink.carapp.model.*

/**
 * Implementación real sobre el SDK de BYD, afinada con el sondeo del Dolphin Surf.
 * Usa [BydPermissionContext] para que los GET/SET de firma funcionen sin firma de sistema.
 * Métodos y valores confirmados por reflexión en el coche real.
 */
class BydGateway(ctx: Context) : CarGateway {

    private val pctx = BydPermissionContext(ctx.applicationContext)

    @Volatile var lastLocation: Location = Location()

    private val ac by lazy { BydReflect.device(pctx, "android.hardware.bydauto.ac.BYDAutoAcDevice") }
    private val body by lazy { BydReflect.device(pctx, "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice") }
    private val charge by lazy { BydReflect.device(pctx, "android.hardware.bydauto.charging.BYDAutoChargingDevice") }
    private val gearbox by lazy { BydReflect.device(pctx, "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice") }
    private val instrument by lazy { BydReflect.device(pctx, "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice") }
    private val speedDev by lazy { BydReflect.device(pctx, "android.hardware.bydauto.speed.BYDAutoSpeedDevice") }
    private val tyre by lazy { BydReflect.device(pctx, "android.hardware.bydauto.tyre.BYDAutoTyreDevice") }
    private val statistic by lazy { BydReflect.device(pctx, "android.hardware.bydauto.statistic.BYDAutoStatisticDevice") }

    // TODO: calibrar el mapeo de marcha con el valor real de getCurrentGear() (re-sondeo).
    private fun gearName(code: Int?) = when (code) { 2 -> "R"; 3 -> "N"; 4 -> "D"; 1 -> "P"; else -> code?.let { "P" } ?: "P" }

    override fun readState(): CarState {
        val soc = BydReflect.callIntArg(body, "getBatteryCapacity") ?: BydReflect.callInt(statistic, "getRemainingBatteryPower") ?: 0
        return CarState(
            ts = System.currentTimeMillis(),
            soc = soc,
            charging = (BydReflect.callInt(charge, "getChargingState", "getChargerWorkState", "getChargerState") ?: 0) > 0,
            chargingPower = (BydReflect.callFloat(charge, "getChargingPower") ?: 0f).toInt(),
            chargeRestMin = chargingRestMin(),
            gear = gearName(BydReflect.callInt(gearbox, "getCurrentGear")),
            speed = (BydReflect.callFloat(speedDev, "getCurrentSpeed") ?: 0f).toInt(),
            locked = true,  // lock state no expuesto por bodywork en este modelo (ver doorlock más adelante)
            doors = readDoors(),
            windows = readWindows(),
            tyres = readTyres(),
            seatbelts = Seatbelts(
                driver = (BydReflect.callIntArg(instrument, "getSafetyBeltStatus", 0) ?: 0) == 1,
                passenger = (BydReflect.callIntArg(instrument, "getSafetyBeltStatus", 1) ?: 0) == 1
            ),
            outTemp = (BydReflect.callIntArg(ac, "getTemprature", 4) ?: 0),
            tempUnit = if ((BydReflect.callInt(ac, "getTemperatureUnit") ?: 1) == 0) "F" else "C",
            odometer = BydReflect.callInt(statistic, "getTotalMileageValue") ?: 0,
            rangeKm = BydReflect.callInt(statistic, "getElecDrivingRangeValue", "getEVMileageValue") ?: 0,
            power = 0,
            voltage = BydReflect.callInt(body, "getBatteryVoltageLevel", "getBatteryPowerValue") ?: 0,
            location = lastLocation
        )
    }

    private fun chargingRestMin(): Int = when (val a = BydReflect.call(charge, "getChargingRestTime")) {
        is IntArray -> if (a.size >= 2) a[0] * 60 + a[1] else a.getOrElse(0) { 0 }
        is Number -> a.toInt()
        else -> 0
    }

    // getDoorState(area)/getWindowState(area): áreas a confirmar. 0=cerrada, 1=abierta (típico).
    private fun readDoors(): Doors {
        fun d(area: Int) = (BydReflect.callIntArg(body, "getDoorState", area) ?: 0) == 1
        return Doors(d(1), d(3), d(2), d(4), d(5), d(6))
    }
    private fun readWindows(): Windows {
        fun w(area: Int) = (BydReflect.callIntArg(body, "getWindowState", area) ?: 0) == 1
        return Windows(w(1), w(3), w(2), w(4), (BydReflect.callInt(body, "getSunroofState") ?: 0) == 1)
    }
    // getTyrePressureValue(rueda): valor crudo (kPa aprox) → bar. Calibrar unidad con re-sondeo.
    private fun readTyres(): Tyres {
        fun t(w: Int) = ((BydReflect.callIntArg(tyre, "getTyrePressureValue", w) ?: 0).toDouble() / 100.0)
        return Tyres(t(0), t(1), t(2), t(3))
    }

    override fun observe(onChange: (CarState) -> Unit) { /* sondeo periódico en el servicio */ }
    override fun stop() {}

    // -------- Control (best-effort; verificar) --------
    override fun setLock(locked: Boolean) {
        if (BydReflect.call(body, "lockDevice", locked) == null) BydReflect.call(body, "lockDevice")
    }
    override fun setWindows(open: Boolean) {
        val st = if (open) 1 else 0
        if (BydReflect.call(body, "setAllWindowState", st, 1) == null && BydReflect.call(body, "setAllWindowState", st) == null) {
            for (area in 1..4) BydReflect.call(body, "setBodyWindowCtrlState", area, st)
        }
    }
    override fun setClimate(on: Boolean, temp: Int) {
        if (on) BydReflect.call(ac, "start", 0) else BydReflect.call(ac, "stop", 0)
        if (BydReflect.call(ac, "setAcTemperature", 1, temp, 1, 1) == null) BydReflect.call(ac, "setTemperature", temp.toFloat())
    }
    override fun setSeatComfort(driver: Int?, passenger: Int?) {
        driver?.let { applySeat(0, it) }; passenger?.let { applySeat(1, it) }
    }
    private fun applySeat(pos: Int, stage: Int) {
        if (stage >= 0) BydReflect.call(instrument, "setSeatHeatingState", pos, stage)
        else BydReflect.call(instrument, "setSeatVentilatingState", pos, -stage)
    }
    override fun loadSeatMemory(slot: Int) { BydReflect.call(instrument, "loadSeatMemory", slot) }
}
