package com.bydlink.carapp.car

import android.content.Context
import android.hardware.bydauto.ac.BYDAutoAcDevice
import android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice
import android.hardware.bydauto.charging.BYDAutoChargingDevice
import android.hardware.bydauto.gearbox.BYDAutoGearboxDevice
import android.hardware.bydauto.instrument.BYDAutoInstrumentDevice
import android.hardware.bydauto.speed.BYDAutoSpeedDevice
import com.bydlink.carapp.model.CarState
import com.bydlink.carapp.model.Location

/**
 * Implementación real sobre el SDK de BYD (android.hardware.bydauto.*).
 * OJO: las firmas de byd-stubs son aproximadas; al probar en el coche (mañana)
 * hay que ajustarlas al framework real. Las llamadas van envueltas en try/catch
 * para que un método que no exista no tire la app.
 *
 * Posición de asiento: 0 = conductor, 1 = pasajero (a confirmar).
 */
class BydGateway(private val ctx: Context) : CarGateway {

    private val ac by lazy { BYDAutoAcDevice.getInstance(ctx) }
    private val body by lazy { BYDAutoBodyworkDevice.getInstance(ctx) }
    private val charge by lazy { BYDAutoChargingDevice.getInstance(ctx) }
    private val gearbox by lazy { BYDAutoGearboxDevice.getInstance(ctx) }
    private val instrument by lazy { BYDAutoInstrumentDevice.getInstance(ctx) }
    private val speed by lazy { BYDAutoSpeedDevice.getInstance(ctx) }

    private fun gearName(code: Int) = when (code) { 1 -> "R"; 2 -> "N"; 3 -> "D"; else -> "P" }

    override fun readState(): CarState = try {
        CarState(
            ts = System.currentTimeMillis(),
            soc = safe { charge.batterySoc } ?: 0,
            charging = safe { charge.isCharging } ?: false,
            chargeRestMin = safe { charge.chargingRestTime } ?: 0,
            gear = gearName(safe { gearbox.gearboxAutoModeType } ?: 0),
            speed = (safe { speed.currentSpeed } ?: 0f).toInt(),
            locked = safe { body.isLocked } ?: true,
            outTemp = (safe { ac.outCarTemperature } ?: 0f).toInt(),
            tempUnit = if ((safe { ac.temperatureUnit } ?: 0) == 1) "F" else "C",
            seatbelts = com.bydlink.carapp.model.Seatbelts(
                driver = (safe { instrument.getSafetyBeltStatus(0) } ?: 0) == 1,
                passenger = (safe { instrument.getSafetyBeltStatus(1) } ?: 0) == 1
            ),
            location = Location() // El GPS se lee aparte (FusedLocation) en el servicio.
        )
    } catch (e: Throwable) { CarState(ts = System.currentTimeMillis()) }

    override fun observe(onChange: (CarState) -> Unit) {
        // TODO: registrar los AbsBYDAuto*Listener y llamar onChange(readState()) en cada callback.
        // De momento el servicio hace sondeo periódico con readState().
    }

    override fun stop() { /* TODO: unregister listeners */ }

    override fun setLock(locked: Boolean) { safe { body.lockDevice(locked) } }
    override fun setClimate(on: Boolean, temp: Int) { safe { ac.setAcState(on) }; safe { ac.setTemperature(temp.toFloat()) } }
    override fun setSeatComfort(driver: Int?, passenger: Int?) {
        driver?.let { applySeat(0, it) }
        passenger?.let { applySeat(1, it) }
    }
    private fun applySeat(pos: Int, stage: Int) {
        if (stage >= 0) safe { instrument.setSeatHeatingState(pos, stage) }
        else safe { instrument.setSeatVentilatingState(pos, -stage) }
    }
    override fun loadSeatMemory(slot: Int) { /* TODO: implementar con el receiver de memoria de asiento */ }

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (e: Throwable) { null }
}
