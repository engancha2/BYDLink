package com.bydlink.carapp.model

/** Modelo de datos que la app del coche publica en Firebase (cars/{id}/status cada 2 s).
 *  Mismo formato que lee el móvil (ver protocol/telemetria.md y phone/).
 *
 *  Regla NUEVA: si un dato no está disponible se publica `null` (nunca el centinela 65535,
 *  -10011, 1048575, etc.). Por eso muchos campos son anulables. Los campos que el móvil
 *  usa desde hace tiempo mantienen el mismo nombre y significado; `gear` y `speed` NO
 *  cambian de significado (el actualizador los usa para saber si el coche está parado).
 *  Todos con valor por defecto → Firebase serializa bien el objeto. */
data class Location(
    val lat: Double = 0.0, val lng: Double = 0.0,
    val heading: Int = 0, val ts: Long = 0
)

data class Doors(
    val fl: Boolean? = null, val fr: Boolean? = null,
    val rl: Boolean? = null, val rr: Boolean? = null,
    val trunk: Boolean? = null, val hood: Boolean? = null
)

/** true=abierta, false=cerrada/desconocida. Se mantiene no-anulable por compatibilidad con el
 *  móvil y el servicio (que las evalúan como boolean); el detalle fino va en [WindowsPct]. */
data class Windows(
    val fl: Boolean = false, val fr: Boolean = false,
    val rl: Boolean = false, val rr: Boolean = false,
    val sunroof: Boolean = false
)

/** % de apertura por ventana/techo (0=cerrada, 100=abierta), null si no se sabe. */
data class WindowsPct(
    val fl: Int? = null, val fr: Int? = null,
    val rl: Int? = null, val rr: Int? = null,
    val sunroof: Int? = null
)

/** Presión por rueda en bar (null si no disponible). */
data class Tyres(
    val fl: Double? = null, val fr: Double? = null,
    val rl: Double? = null, val rr: Double? = null
)

/** Estado/alerta de presión por rueda: 0=normal, 1=sobrepresión, 2=baja presión. null si n/d. */
data class TyresState(
    val fl: Int? = null, val fr: Int? = null,
    val rl: Int? = null, val rr: Int? = null
)

/** Temperatura por rueda en °C (sensores TPMS, instrument.getWheelTemperature). null si n/d. */
data class TyreTemps(
    val fl: Int? = null, val fr: Int? = null,
    val rl: Int? = null, val rr: Int? = null
)

data class Seatbelts(val driver: Boolean? = null, val passenger: Boolean? = null)

/** Ocupación de plaza: true=alguien, false=nadie, null=desconocido. */
data class Occupancy(
    val driver: Boolean? = null, val passenger: Boolean? = null,
    val rearLeft: Boolean? = null, val rearMid: Boolean? = null, val rearRight: Boolean? = null
)

/** Estado completo del clima. temp en °C (consigna), fan 0-7, mode 1..7 (dirección aire),
 *  cycle: true=recirculación interior. Desempañado delante/detrás. Nulls si n/d. */
data class Climate(
    val on: Boolean? = null,
    val temp: Int? = null,
    val fan: Int? = null,
    val mode: Int? = null,
    val recirc: Boolean? = null,
    val defrostFront: Boolean? = null,
    val defrostRear: Boolean? = null,
    val maxCooling: Boolean? = null,
    val ventilation: Boolean? = null,
    val auto: Boolean? = null
)

/** Nivel de calefacción/ventilación por asiento. Convenio unificado -2..2 del móvil
 *  (negativo=ventilación, positivo=calefacción, 0=apagado); null si no equipado/desconocido. */
data class SeatComfort(
    val driver: Int? = null,
    val passenger: Int? = null
)

/** Luces (0=apagada, 1=encendida; null si n/d). */
data class Lights(
    val lowBeam: Boolean? = null,
    val highBeam: Boolean? = null,
    val position: Boolean? = null,
    val drl: Boolean? = null,
    val rearFog: Boolean? = null,
    val hazard: Boolean? = null
)

data class CarState(
    val ts: Long = 0,
    // --- batería / carga ---
    val soc: Int? = null,                       // SOC % real (statistic.getSOCBatteryPercentage)
    val charging: Boolean = false,              // cargando de verdad (BMS state==CHARGING)
    val chargingPower: Int? = null,             // kW (redondeado; ver chargeKw)
    val chargeKw: Double? = null,               // kW con un decimal (en el Surf: calculado de chargedKwh en el tiempo)
    val chargedKwh: Double? = null,             // kWh metidos en la carga actual (charging.getChargingCapacity)
    val chargeRestMin: Int? = null,             // minutos restantes
    val chargeLimit: Int? = null,               // límite de carga % (60/70/80/90/100), el informado o el último pedido
    val chargeLimitConfirmed: Boolean? = null,  // true si lo informa el coche; false si es solo lo último que pedimos
    val chargingGun: String? = null,            // "none"/"AC"/"DC"/null
    val bmsState: Int? = null,                  // estado BMS crudo (0 ready,1 charging,2 finish,3 discharg,15 idle…)
    val soh: Int? = null,                       // salud de batería % (STAT_BATTERY_HEALTHY_INDEX)
    val batteryTempMax: Int? = null,            // °C celda más caliente
    val batteryTempMin: Int? = null,            // °C celda más fría
    // --- conducción ---
    val gear: String = "P",                     // "P"/"R"/"N"/"D" (NO cambiar significado)
    val gearRaw: Int? = null,                   // valor crudo de getCurrentGear (para calibrar)
    val speed: Int = 0,                         // km/h (NO cambiar significado)
    val powerState: String? = null,             // "OFF"/"ACC"/"ON"/"OK" (getPowerLevel)
    val odometer: Int? = null,                  // km (statistic.getTotalMileageValue)
    val rangeKm: Int? = null,                   // autonomía eléctrica km
    val power: Int? = null,                     // potencia tracción kW (+ consume / - regenera)
    val consumptionKwh100: Double? = null,      // consumo medio kWh/100km
    val voltage: Int? = null,                   // tensión pack HV (V)
    val aux12v: Double? = null,                 // tensión batería auxiliar 12 V
    val energyLeftKwh: Double? = null,          // energía que queda en la batería (power.getBatteryRemainPowerEV)
    val lastTripKwh100: Double? = null,         // consumo del último viaje kWh/100km
    val totalEnergyKwh: Double? = null,         // energía gastada desde nuevo (kWh)
    val parkBrake: Boolean? = null,             // freno de mano echado
    val driveMode: Int? = null,                 // modo de energía crudo (energy.getEnergyMode; significado por calibrar)
    val bmsRaw: Map<String, Int>? = null,       // lecturas del BMS en crudo (volt, current, power...) para calibrar
    val bmsVolt: Double? = null,                // V de la batería de tracción (BMS 0x44400008)
    val bmsAmp: Double? = null,                 // A de carga/descarga (BMS 0x44400018, decimal; signo por calibrar)
    // --- cierre / seguridad ---
    val locked: Boolean? = null,                // cierre real (OTA lock / autoSystemState)
    val alarm: Boolean? = null,                 // alarma antirrobo activada
    // --- carrocería ---
    val doors: Doors = Doors(),
    val windows: Windows = Windows(),           // true=abierta
    val windowsPct: WindowsPct? = null,
    // --- ruedas ---
    val tyres: Tyres = Tyres(),                 // bar
    val tyresState: TyresState? = null,
    val tyreTemps: TyreTemps? = null,
    // --- ocupación / cinturones ---
    val seatbelts: Seatbelts = Seatbelts(),
    val occupancy: Occupancy? = null,
    // --- temperaturas ---
    val outTemp: Int? = null,                   // °C exterior
    val inTemp: Int? = null,                    // °C cabina
    val tempUnit: String = "C",
    // --- clima / confort / luces ---
    val climate: Climate? = null,
    val seatComfort: SeatComfort? = null,
    val lights: Lights? = null,
    // --- posición ---
    val location: Location = Location()
)

/** Un comando recibido del móvil. */
data class Command(val id: String, val action: String, val payload: Map<String, Any?>)
