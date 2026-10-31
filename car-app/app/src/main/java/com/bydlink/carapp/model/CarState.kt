package com.bydlink.carapp.model

/** Modelo de datos que la app del coche publica en Firebase (ver protocol/commands.md). */
data class Location(
    val lat: Double = 0.0, val lng: Double = 0.0,
    val heading: Int = 0, val ts: Long = 0
)

data class Doors(
    val fl: Boolean = false, val fr: Boolean = false,
    val rl: Boolean = false, val rr: Boolean = false,
    val trunk: Boolean = false, val hood: Boolean = false
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
    val seatbelts: Seatbelts = Seatbelts(),
    val outTemp: Int = 0,
    val tempUnit: String = "C",
    val odometer: Int = 0,
    val location: Location = Location()
)

/** Un comando recibido del móvil. */
data class Command(val id: String, val action: String, val payload: Map<String, Any?>)
