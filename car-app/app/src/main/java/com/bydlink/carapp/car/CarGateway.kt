package com.bydlink.carapp.car

import com.bydlink.carapp.model.CarState

/**
 * Abstracción del coche. Dos implementaciones:
 *  - [BydGateway]     : habla con el SDK real de BYD (en la pantalla del coche).
 *  - [MockCarGateway] : valores simulados (para desarrollar en un móvil/emulador).
 * Así el resto de la app no depende del SDK.
 */
interface CarGateway {
    /** Lee el estado actual completo del vehículo. */
    fun readState(): CarState

    /** Empieza a emitir cambios de estado. [onChange] se llama cuando algo cambia. */
    fun observe(onChange: (CarState) -> Unit)
    fun stop()

    // --- Acciones ---
    fun setLock(locked: Boolean)
    fun setClimate(on: Boolean, temp: Int)
    /** stage -2..2 (negativo ventilación, positivo calefacción). */
    fun setSeatComfort(driver: Int?, passenger: Int?)
    fun loadSeatMemory(slot: Int)
}
