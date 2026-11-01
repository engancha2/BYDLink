package com.bydlink.carapp.car

import android.content.Context
import android.util.Log
import com.bydlink.carapp.model.*

/**
 * Implementación real sobre el SDK de BYD, afinada con el sondeo del Dolphin Surf.
 * Usa [BydPermissionContext] para que los GET/SET de firma funcionen sin firma de sistema.
 * Lecturas corregidas y ampliadas según el catálogo del SDK y el informe de apps de la
 * comunidad (ver protocol/telemetria.md). Cada lectura va protegida: un fallo no rompe las demás.
 *
 * Las lecturas lentas (feature IDs del BMS: SOH, temperaturas de celda, tensión HV, potencia,
 * 12 V) se agrupan en un ciclo más espaciado y se cachean, para no hacer cientos de llamadas
 * cada 2 s.
 *
 * Los MANDOS viven en [CarCommands]; aquí solo quedan los setters mínimos del interfaz
 * [CarGateway] que usa el servicio/mock (corregidos: asientos por `setting`, no `instrument`).
 */
class BydGateway(ctx: Context) : CarGateway {

    private val pctx = BydPermissionContext(ctx.applicationContext)
    private val appCtx = ctx.applicationContext

    /** Límite de carga que informa el coche (código de BYD o, en otros modelos, el objetivo de SOC). */
    private fun readChargeLimit(): Int? = ChargeLimits.read(charge)
        ?: san(BydReflect.callInt(setting, "getSOCTarget"))?.takeIf { it in 50..100 }

    @Volatile var lastLocation: Location = Location()

    private val ac by lazy { dev("ac.BYDAutoAcDevice") }
    private val body by lazy { dev("bodywork.BYDAutoBodyworkDevice") }
    private val charge by lazy { dev("charging.BYDAutoChargingDevice") }
    private val gearbox by lazy { dev("gearbox.BYDAutoGearboxDevice") }
    private val instrument by lazy { dev("instrument.BYDAutoInstrumentDevice") }
    private val speedDev by lazy { dev("speed.BYDAutoSpeedDevice") }
    private val tyre by lazy { dev("tyre.BYDAutoTyreDevice") }
    private val statistic by lazy { dev("statistic.BYDAutoStatisticDevice") }
    private val setting by lazy { dev("setting.BYDAutoSettingDevice") }
    private val light by lazy { dev("light.BYDAutoLightDevice") }
    private val safetybelt by lazy { dev("safetybelt.BYDAutoSafetyBeltDevice") }
    private val ota by lazy { dev("ota.BYDAutoOtaDevice") }
    private val power by lazy { dev("power.BYDAutoPowerDevice") }

    private fun dev(suffix: String) = BydReflect.device(pctx, "android.hardware.bydauto.$suffix")
    private fun san(v: Int?) = BydReflect.san(v)
    private fun sanD(v: Double?) = BydReflect.sanD(v)

    // Marcha: familia Seagull/Dolphin → getCurrentGear 3=P, 1=R, 0=N, 2=D (informe comunidad).
    // En el Surf P da 3. R/N/D por confirmar conduciendo (guardamos gearRaw para calibrar).
    private fun gearName(code: Int?) = when (code) { 3 -> "P"; 1 -> "R"; 0 -> "N"; 2 -> "D"; else -> "P" }
    private fun powerName(level: Int?) = when (level) { 0 -> "OFF"; 1 -> "ACC"; 2 -> "ON"; 3 -> "OK"; 4 -> "FAKE_OK"; else -> null }

    // --- Caché de lecturas lentas (cada SLOW_EVERY ciclos ≈ 10 s) ---
    private var tick = 0
    private var slowSoh: Int? = null
    private var slowTMax: Int? = null
    private var slowTMin: Int? = null
    private var slowHv: Int? = null
    private var slowPower: Int? = null
    private var slowAux12: Double? = null
    private var slowEnergyLeft: Double? = null
    private var slowLastTrip: Double? = null
    private var slowTotalEnergy: Double? = null
    private var slowDriveMode: Int? = null
    private var slowTyreTemps: TyreTemps? = null
    private var slowBms: Map<String, Int>? = null
    private val energyDev by lazy { dev("energy.BYDAutoEnergyDevice") }

    /**
     * Lecturas del BMS (como BYDMate), en crudo para calibrar escalas en el coche: el mismo bloque 0x444000xx que la
     * salud (0x44400028). Cada id se prueba en varios dispositivos porque cada uno solo deja leer los suyos.
     */
    private var slowBmsVolt: Double? = null
    private var slowBmsAmp: Double? = null

    /** Voltaje y corriente del BMS: la corriente es decimal (leerla como entero daba basura). V × I = potencia real. */
    private fun readBmsVI() {
        val devs = listOf(charge, statistic, instrument, body)
        fun d(fid: Int): Double? = devs.firstNotNullOfOrNull { dv -> try { BydReflect.featureDouble(dv, fid) } catch (e: Throwable) { null } }
        // El voltaje viene como entero en el Surf (302) y la corriente como decimal (-5,8 A cargando).
        slowBmsVolt = (d(0x44400008) ?: devs.firstNotNullOfOrNull { dv -> try { BydReflect.featureInt(dv, 0x44400008) } catch (e: Throwable) { null } }?.toDouble())
            ?.takeIf { it in 50.0..1000.0 }
        slowBmsAmp = d(0x44400018)?.takeIf { kotlin.math.abs(it) in 0.0..1000.0 }
    }

    private fun readBms(): Map<String, Int>? {
        val devs = listOf(charge, statistic, instrument, body)
        fun f(fid: Int): Int? = devs.firstNotNullOfOrNull { d -> try { BydReflect.featureInt(d, fid) } catch (e: Throwable) { null } }
        val m = linkedMapOf<String, Int>()
        listOf("volt" to 0x44400008, "current" to 0x44400018, "availPower" to 0x44400038, "powerDD" to 0x32300018,
            "extPower" to 0x4a508010, "acCurrent" to 0x0002e98f, "power" to 0x000b4c9a, "dcBus" to 0x000d7696)
            .forEach { (k, fid) -> f(fid)?.let { m[k] = it } }
        return m.takeIf { it.isNotEmpty() }
    }

    // Potencia de carga = lo que suben los kWh de la sesión por hora, en una ventana de hasta 10 min (los kWh vienen
    // redondeados: con menos de 2 min la cuenta salta mucho).
    private val chargeSamples = ArrayDeque<Pair<Long, Double>>()
    private fun chargeRate(charging: Boolean, kwh: Double?): Double? {
        val now = System.currentTimeMillis()
        if (!charging || kwh == null) { chargeSamples.clear(); return null }
        if (chargeSamples.isNotEmpty() && kwh < chargeSamples.last().second) chargeSamples.clear()   // carga nueva
        chargeSamples.addLast(now to kwh)
        while (chargeSamples.size > 2 && now - chargeSamples.first().first > 10 * 60_000) chargeSamples.removeFirst()
        val (t0, k0) = chargeSamples.first()
        val hours = (now - t0) / 3_600_000.0
        if (hours < 2.0 / 60) return null
        return ((kwh - k0) / hours).takeIf { it in 0.0..400.0 }?.let { Math.round(it * 10) / 10.0 }
    }

    override fun readState(): CarState {
        tick++
        if (tick % SLOW_EVERY == 1) readSlow()

        val gearRaw = BydReflect.callInt(gearbox, "getCurrentGear")
        val limitReal = prot { readChargeLimit() }
        val charging = prot { isCharging() } ?: false
        // kWh metidos en esta carga (en el Surf getChargingPower da 0, pero esto sube mientras carga).
        val charged = prot { BydReflect.callDouble(charge, "getChargingCapacity")?.let { sanD(it) }?.takeIf { it in 0.0..500.0 } }
        // Potencia: V × I del BMS si da un valor razonable; si no, lo que suben los kWh de la sesión por hora.
        val volt = slowBmsVolt ?: slowHv?.toDouble()           // 302 V en el Surf (la misma lectura del BMS que "voltage")
        val vi = if (charging) volt?.let { v -> slowBmsAmp?.let { a -> v * kotlin.math.abs(a) / 1000.0 } }?.takeIf { it in 0.1..400.0 } else null
        val kw = vi?.let { Math.round(it * 10) / 10.0 } ?: chargeRate(charging, charged)
        val soc = prot { san(BydReflect.callInt(statistic, "getSOCBatteryPercentage"))
            ?: BydReflect.callDouble(statistic, "getElecPercentageValue")?.let { sanD(it)?.toInt() }
            ?: (san(BydReflect.callInt(body, "getBatteryCapacity"))?.div(2)) }
        val limit = limitReal ?: ChargeLimits.remembered(appCtx)
        ChargeLimits.learn(appCtx, limit, limitReal != null, soc, charging)
        return CarState(
            ts = System.currentTimeMillis(),
            // batería / carga
            soc = soc,
            charging = charging,
            chargingPower = prot { BydReflect.callDouble(charge, "getChargingPower")?.let { sanD(it) }?.takeIf { it > 0 }?.toInt() }
                ?: kw?.let { Math.round(it).toInt() },
            chargeKw = prot { BydReflect.callDouble(charge, "getChargingPower")?.let { sanD(it) }?.takeIf { it > 0 } } ?: kw,
            chargedKwh = if (charging) charged else null,
            chargeRestMin = prot { chargingRestMin() },
            chargeLimit = limit,
            chargeLimitConfirmed = limitReal != null,
            chargeLimitIgnored = ChargeLimits.ignored(appCtx),
            chargingGun = prot { gunName(BydReflect.callInt(charge, "getChargingGunState")) },
            bmsState = prot { san(BydReflect.callInt(charge, "getBatteryManagementDeviceState")) },
            soh = slowSoh,
            batteryTempMax = slowTMax,
            batteryTempMin = slowTMin,
            // conducción
            gear = gearName(gearRaw),
            gearRaw = gearRaw,
            speed = prot { BydReflect.callDouble(speedDev, "getCurrentSpeed")?.toInt() } ?: 0,
            powerState = prot { powerName(san(BydReflect.callInt(body, "getPowerLevel"))) },
            odometer = prot { san(BydReflect.callInt(statistic, "getTotalMileageValue")) },
            rangeKm = prot { san(BydReflect.callInt(statistic, "getElecDrivingRangeValue")) },
            power = slowPower,
            consumptionKwh100 = prot { sanD(BydReflect.callDouble(statistic, "getTotalElecConPHMValue")) },
            voltage = slowHv,
            aux12v = slowAux12,
            energyLeftKwh = slowEnergyLeft,
            lastTripKwh100 = slowLastTrip,
            totalEnergyKwh = slowTotalEnergy,
            parkBrake = prot { san(BydReflect.callInt(gearbox, "getParkBrakeSwitch"))?.let { it == 1 } },
            driveMode = slowDriveMode,
            bmsRaw = slowBms,
            bmsVolt = slowBmsVolt ?: slowHv?.toDouble(),
            bmsAmp = slowBmsAmp,
            // cierre / seguridad
            locked = prot { readLocked() },
            alarm = prot { san(BydReflect.callInt(body, "getAlarmState"))?.let { it == 1 } },
            // carrocería
            doors = prot { readDoors() } ?: Doors(),
            windows = prot { readWindows() } ?: Windows(),
            windowsPct = prot { readWindowsPct() },
            // ruedas
            tyres = prot { readTyres() } ?: Tyres(),
            tyresState = prot { readTyresState() },
            tyreTemps = slowTyreTemps,
            // cinturones / ocupación
            seatbelts = prot { readBelts() } ?: Seatbelts(),
            occupancy = prot { readOccupancy() },
            // temperaturas
            outTemp = prot { san(BydReflect.callInt(instrument, "getOutCarTemperature"))
                ?: BydReflect.callIntArg(ac, "getTemprature", 4)?.let { san(it) } },
            inTemp = prot { BydReflect.callIntArg(ac, "getTemprature", 0)?.let { san(it) } },
            tempUnit = prot { if ((BydReflect.callInt(ac, "getTemperatureUnit") ?: 1) == 0) "F" else "C" } ?: "C",
            // clima / confort / luces
            climate = prot { readClimate() },
            seatComfort = prot { readSeatComfort() },
            lights = prot { readLights() },
            // posición
            location = lastLocation
        )
    }

    /** Lecturas lentas (BMS por feature ID). Se refrescan cada ~10 s y se cachean. */
    private fun readSlow() {
        slowSoh = prot { BydReflect.featureInt(statistic, F_SOH)?.takeIf { it in 0..100 } }
        slowTMax = prot { BydReflect.featureInt(statistic, F_TMAX)?.let { it - 40 } }
        slowTMin = prot { BydReflect.featureInt(statistic, F_TMIN)?.let { it - 40 } }
        slowHv = prot { BydReflect.featureInt(charge, F_HV_VOLT)?.takeIf { it in 1..1000 } }
        slowPower = prot { BydReflect.featureInt(dev("engine.BYDAutoEngineDevice"), F_ENGINE_POWER)
            ?: BydReflect.callInt(dev("engine.BYDAutoEngineDevice"), "getEnginePower")?.let { san(it) } }
        slowAux12 = prot { readAux12() }
        // Vistas en el Surf (sondeo) y en la pantalla de Beetle: 29,1 kWh, 9,9 kWh/100km, 2285,7 kWh.
        slowEnergyLeft = prot { BydReflect.callDouble(power, "getBatteryRemainPowerEV")?.let { sanD(it) }?.takeIf { it in 0.1..200.0 } }
        slowLastTrip = prot { BydReflect.callDouble(statistic, "getLastElecConPHMValue")?.let { sanD(it) }?.takeIf { it in 0.1..100.0 } }
        slowTotalEnergy = prot { BydReflect.callDouble(statistic, "getTotalElecConValue")?.let { sanD(it) }?.takeIf { it > 0 } }
        slowDriveMode = prot { san(BydReflect.callInt(energyDev, "getEnergyMode")) }
        slowTyreTemps = prot { readTyreTemps() }
        slowBms = prot { readBms() }
        prot { readBmsVI() }
    }

    // El cuadro numera las ruedas distinto que las presiones: 3 = del. izq., 1 = del. dcha., 4 = tras. izq.,
    // 2 = tras. dcha. (comprobado contra la pantalla de Beetle en el Surf). Si no, el dispositivo de neumáticos.
    private fun readTyreTemps(): TyreTemps? {
        fun t(area: Int, tyreArea: Int): Int? =
            (san(BydReflect.callIntArg(instrument, "getWheelTemperature", area))
                ?: san(BydReflect.callIntArg(tyre, "getTyreTemperatureValue", tyreArea)))?.takeIf { it in -40..150 }
        val r = TyreTemps(t(3, 1), t(1, 2), t(4, 3), t(2, 4))
        return if (r == TyreTemps()) null else r
    }

    private fun readAux12(): Double? =
        BydReflect.callDouble(ota, "getBatteryPowerVoltage", "getBatteryVoltage")?.let { sanD(it) }
            ?: BydReflect.featureDouble(body, F_12V)
            ?: BydReflect.callDouble(power, "getBatteryVoltage")?.let { sanD(it) }

    private fun isCharging(): Boolean {
        val bms = san(BydReflect.callInt(charge, "getBatteryManagementDeviceState"))
        if (bms == 1) return true                       // 1 = CHARGING
        if (bms != null && bms != 15) {                  // 15 = IDLE
            val p = sanD(BydReflect.callDouble(charge, "getChargingPower")) ?: 0.0
            if (p > 0.2) return true
        }
        return false
    }

    private fun chargingRestMin(): Int? = when (val a = BydReflect.call(charge, "getChargingRestTime")) {
        is IntArray -> if (a.size >= 2) san(a[0])?.let { it * 60 + (a[1].coerceAtLeast(0)) } else null
        is Number -> san(a.toInt())
        else -> null
    }

    private fun gunName(v: Int?) = when (san(v)) { 1 -> "none"; 2 -> "AC"; 3 -> "DC"; 4 -> "AC_DC"; 5 -> "V2L"; else -> null }

    // Cierre: preferimos OTA (dev 1032, 1=unlocked 2=locked). Fallback: autoSystemState (>=1 = cerrado).
    private fun readLocked(): Boolean? {
        val lf = san(BydReflect.callInt(ota, "getLFDoorLockState"))
            ?: BydReflect.featureInt(ota, F_OTA_LOCK)
        when (lf) { 2 -> return true; 1 -> return false }
        return san(BydReflect.callInt(body, "getAutoSystemState"))?.let { it >= 1 }
    }

    // getDoorState(area): CMD_DOOR LF=1,RF=2,LR=3,RR=4,HOOD=5,LUGGAGE=6. 0=cerrada,1=abierta,255=undef.
    private fun readDoors(): Doors {
        fun d(area: Int): Boolean? = san(BydReflect.callIntArg(body, "getDoorState", area))?.let { it == 1 }
        return Doors(d(1), d(2), d(3), d(4), d(6), d(5))
    }
    // getWindowState(area): LF=1,RF=2,LR=3,RR=4. Techo getSunroofState (0 cerrado, 3 abierto).
    private fun readWindows(): Windows {
        fun w(area: Int): Boolean = san(BydReflect.callIntArg(body, "getWindowState", area)) == 1
        val sr = san(BydReflect.callInt(body, "getSunroofState"))?.let { it != 0 } ?: false
        return Windows(w(1), w(2), w(3), w(4), sr)
    }
    private fun readWindowsPct(): WindowsPct? {
        fun p(area: Int): Int? = san(BydReflect.callIntArg(body, "getWindowOpenPercent", area))?.takeIf { it in 0..100 }
        val sr = san(BydReflect.callInt(body, "getSunroofPosition"))?.takeIf { it in 0..100 }
        val r = WindowsPct(p(1), p(2), p(3), p(4), sr)
        return if (r == WindowsPct()) null else r
    }
    // getTyrePressureValue(area) raw ~kPa → bar (/100). Área 1=LF,2=RF,3=LR,4=RR.
    private fun readTyres(): Tyres {
        fun t(area: Int): Double? = san(BydReflect.callIntArg(tyre, "getTyrePressureValue", area))?.let { it / 100.0 }?.takeIf { it > 0.1 }
        return Tyres(t(1), t(2), t(3), t(4))
    }
    private fun readTyresState(): TyresState? {
        fun s(area: Int): Int? = san(BydReflect.callIntArg(tyre, "getTyrePressureState", area))
        val r = TyresState(s(1), s(2), s(3), s(4))
        return if (r == TyresState()) null else r
    }
    // Cinturón: 0=suelto,1=puesto,2=inválido. Áreas MAIN=1,DEPUTY=2. Probar safetybelt y, si no, instrument.
    private fun readBelts(): Seatbelts {
        fun b(area: Int): Boolean? {
            val v = san(BydReflect.callIntArg(safetybelt, "getSafetyBeltStatus", area))
                ?: san(BydReflect.callIntArg(instrument, "getSafetyBeltStatus", area))
            return when (v) { 1 -> true; 0 -> false; else -> null }
        }
        // En el Surf el del acompañante (y los traseros) marcan "puesto" con el asiento VACÍO: solo se publica
        // si el coche dice que hay alguien sentado (getPassengerStatus(1)=1); el Surf no lo expone → null.
        val someoneThere = san(BydReflect.callIntArg(safetybelt, "getPassengerStatus", 1)) == 1
        return Seatbelts(b(1), if (someoneThere) b(2) else null)
    }
    private fun readOccupancy(): Occupancy? {
        fun o(area: Int): Boolean? = when (san(BydReflect.callIntArg(safetybelt, "getPassengerStatus", area))) { 1 -> true; 0 -> false; else -> null }
        val r = Occupancy(o(1), o(2), o(3), o(4), o(5))
        return if (r == Occupancy()) null else r
    }

    private fun readClimate(): Climate? {
        val r = Climate(
            on = san(BydReflect.callInt(ac, "getAcStartState"))?.let { it == 1 },
            temp = BydReflect.callIntArg(ac, "getTemprature", 1)?.let { san(it) }?.takeIf { it in 15..35 },
            fan = san(BydReflect.callInt(ac, "getAcWindLevel"))?.takeIf { it in 0..7 },
            mode = san(BydReflect.callInt(ac, "getAcWindMode")),
            recirc = san(BydReflect.callInt(ac, "getAcCycleMode"))?.let { it == 1 },
            defrostFront = san(BydReflect.callIntArg(ac, "getAcDefrostState", 1))?.let { it == 1 },
            defrostRear = san(BydReflect.callIntArg(ac, "getAcDefrostState", 2))?.let { it == 1 },
            maxCooling = san(BydReflect.callInt(ac, "getAcMaxCoolingState"))?.let { it == 1 },
            ventilation = san(BydReflect.callInt(ac, "getAcVentilationState"))?.let { it == 1 },
            auto = san(BydReflect.callInt(ac, "getAcControlMode"))?.let { it == 0 }
        )
        return if (r == Climate()) null else r
    }

    private fun readSeatComfort(): SeatComfort? {
        fun stage(pos: Int): Int? {
            val heat = san(BydReflect.callIntArg(setting, "getSeatHeatingState", pos))?.takeIf { it in 1..2 }
            if (heat != null) return heat
            val vent = san(BydReflect.callIntArg(setting, "getSeatVentilatingState", pos))?.takeIf { it in 1..2 }
            if (vent != null) return -vent
            return if (heat == null && vent == null) null else 0
        }
        val r = SeatComfort(stage(0), stage(1))
        return if (r == SeatComfort()) null else r
    }

    private fun readLights(): Lights? {
        fun l(type: Int): Boolean? = san(BydReflect.callIntArg(light, "getLightStatus", type))?.let { it == 1 }
        val r = Lights(
            lowBeam = l(2), highBeam = l(3), position = l(1),
            drl = san(BydReflect.callInt(light, "getDayTimeLightState"))?.let { it == 1 },
            rearFog = l(7),
            hazard = san(BydReflect.callInt(light, "getDoubleFlashLightState"))?.let { it == 1 }
        )
        return if (r == Lights()) null else r
    }

    /** Ejecuta una lectura y traga cualquier fallo devolviendo null (no rompe las demás). */
    private inline fun <T> prot(f: () -> T?): T? = try { f() } catch (e: Throwable) { null }

    override fun observe(onChange: (CarState) -> Unit) { /* sondeo periódico en el servicio */ }
    override fun stop() {}

    // -------- Control mínimo del interfaz (los mandos ricos están en CarCommands) --------
    override fun setLock(locked: Boolean) {
        // Feature id BODYWORK_FOUR_DOOR_SET (1=unlock, 2=lock) — confirmado por la comunidad.
        val v = if (locked) 2 else 1
        if (BydReflect.setFeature(body, BydReflect.fid("Bodywork.BODYWORK_FOUR_DOOR_SET", 1276141590), v) == null)
            BydReflect.call(body, "lockDevice", locked) ?: BydReflect.call(body, "lockDevice")
        Log.i(TAG, "setLock($locked)")
    }
    override fun setWindows(open: Boolean) {
        val st = if (open) 1 else 0
        if (BydReflect.call(body, "setAllWindowState", st, st, st, st) == null &&
            BydReflect.call(body, "setAllWindowState", st) == null) {
            for (area in 1..4) BydReflect.call(body, "setBodyWindowCtrlState", area, st)
        }
    }
    override fun setClimate(on: Boolean, temp: Int) {
        if (on) BydReflect.call(ac, "start", 0) else BydReflect.call(ac, "stop", 0)
        BydReflect.call(ac, "setAcTemperature", 1, temp, 1, 1)
    }
    override fun setSeatComfort(driver: Int?, passenger: Int?) {
        driver?.let { applySeat(0, it) }; passenger?.let { applySeat(1, it) }
    }
    // CORREGIDO: los asientos van por `setting`, no por `instrument` (DOC/STUBS/CARSDK).
    private fun applySeat(pos: Int, stage: Int) {
        if (stage >= 0) { BydReflect.call(setting, "setSeatHeatingState", pos, stage); BydReflect.call(setting, "setSeatVentilatingState", pos, 0) }
        else { BydReflect.call(setting, "setSeatVentilatingState", pos, -stage); BydReflect.call(setting, "setSeatHeatingState", pos, 0) }
    }
    override fun loadSeatMemory(slot: Int) {
        if (BydReflect.setFeature(setting, BydReflect.fid("Setting.SET_LF_MEMORY_LOCATION_SET", 1276186678), slot) == null)
            BydReflect.call(setting, "loadSeatMemory", slot)
    }

    private companion object {
        const val TAG = "BYDLink"
        const val SLOW_EVERY = 5   // 1 de cada 5 ciclos (≈ 10 s) para lecturas lentas del BMS
        // Feature IDs (símbolo + fallback numérico Leopard3/CANFD, por confirmar en Surf):
        val F_SOH = BydReflect.fid("Statistic.STATISTIC_BATTERY_HEALTHY_INDEX", 1145045032)
        val F_TMAX = BydReflect.fid("Statistic.STATISTIC_HIGHEST_BATTERY_TEMP", 1148190752)
        val F_TMIN = BydReflect.fid("Statistic.STATISTIC_LOWEST_BATTERY_TEMP", 1148190736)
        val F_HV_VOLT = BydReflect.fid("Charging.CHARGING_CHARGE_BATTERY_VOLT", 1145045000)
        val F_ENGINE_POWER = BydReflect.fid("Engine.ENGINE_POWER", 339738656)
        val F_12V = BydReflect.fid("Ota.OTA_BATTERY_POWER_VOLTAGE", 1128267816)
        val F_OTA_LOCK = BydReflect.fid("Ota.OTA_LF_DOOR_LOCK", 1081081864)
    }
}
