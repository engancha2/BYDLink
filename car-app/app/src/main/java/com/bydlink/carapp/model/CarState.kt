package com.bydlink.carapp.model

/** Modelo de datos que la app del coche publica en Firebase. Mismo formato que lee el móvil
 *  (ver protocol/commands.md y phone/). Todos con valores por defecto → serializa en Firebase. */
data class Location(
    val lat: Double = 0.0, val lng: Double = 0.0,
    val heading: Int = 0, val ts: Long = 0
)

data class Doors(
    val fl: Boolean = false, val fr: Boolean = false,
    val rl: Boolean = false, val rr: Boolean = false,
    val trunk: Boolean = false, val hood: Boolean = false
)

data class Windows(
    val fl: Boolean = false, val fr: Boolean = false,
    val rl: Boolean = false, val rr: Boolean = false,
    val sunroof: Boolean = false
)

data class Tyres(
    val fl: Double = 0.0, val fr: Double = 0.0,
    val rl: Double = 0.0, val rr: Double = 0.0
)

data class Seatbelts(val driver: Boolean = false, val passenger: Boolean = false)

data class CarState(
    val ts: Long = 0,
    val soc: Int = 0,
    val charging: Boolean = false,
    val chargingPower: Int = 0,
    val chargeRestMin: Int = 0,
    val gear: String = "P",
    val speed: Int = 0,
    val locked: Boolean = true,
    val doors: Doors = Doors(),
    val windows: Windows = Windows(),
    val tyres: Tyres = Tyres(),
    val seatbelts: Seatbelts = Seatbelts(),
    val outTemp: Int = 0,
    val tempUnit: String = "C",
    val odometer: Int = 0,
    val rangeKm: Int = 0,
    val power: Int = 0,
    val consumptionKwh100: Double = 0.0,
    val voltage: Int = 0,
    val location: Location = Location()
)

/** Un comando recibido del móvil. */
data class Command(val id: String, val action: String, val payload: Map<String, Any?>)
