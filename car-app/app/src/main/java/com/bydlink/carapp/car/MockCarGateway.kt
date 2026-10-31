package com.bydlink.carapp.car

import com.bydlink.carapp.model.CarState
import com.bydlink.carapp.model.Location
import kotlin.math.cos
import kotlin.math.sin

/** Coche simulado: permite desarrollar la app del coche sin el coche. */
class MockCarGateway : CarGateway {
    private var state = CarState(soc = 72, gear = "P", locked = true, outTemp = 19,
        location = Location(41.3870, 2.1700, 0, System.currentTimeMillis()))
    private var cb: ((CarState) -> Unit)? = null
    private var tick = 0

    override fun readState(): CarState = state

    override fun observe(onChange: (CarState) -> Unit) {
        cb = onChange
        Thread {
            while (cb != null) {
                tick++
                val a = tick / 40.0
                state = state.copy(
                    ts = System.currentTimeMillis(),
                    location = Location(41.3870 + 0.012 * sin(a), 2.1700 + 0.016 * cos(a),
                        (tick * 8) % 360, System.currentTimeMillis())
                )
                cb?.invoke(state)
                Thread.sleep(1500)
            }
        }.start()
    }
    override fun stop() { cb = null }

    override fun setLock(locked: Boolean) { state = state.copy(locked = locked); cb?.invoke(state) }
    override fun setWindows(open: Boolean) {
        state = state.copy(windows = com.bydlink.carapp.model.Windows(open, open, open, open, false)); cb?.invoke(state)
    }
    override fun setClimate(on: Boolean, temp: Int) {}
    override fun setSeatComfort(driver: Int?, passenger: Int?) {}
    override fun loadSeatMemory(slot: Int) {}
}
