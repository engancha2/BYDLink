package com.bydlink.carapp.car

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.model.CarState

/**
 * TODOS los mandos del coche. El servicio enruta aquí cada comando del móvil:
 *   `else -> carCommands.handle(action, payload)`
 *
 * Contrato:
 *   - Devuelve `null` si la acción NO es suya (para que el caller la ignore).
 *   - Si es suya y va bien, devuelve datos del resultado (p. ej. {"applied":true} o el estado
 *     leído de vuelta).
 *   - Si falla, LANZA una excepción con un mensaje en español claro (el caller la reporta).
 *
 * Reglas de oro (del catálogo del SDK):
 *   - El SDK devuelve 0 aunque ignore la orden → tras cada setter se LEE de vuelta el estado y
 *     se informa de si se aplicó de verdad (`applied`).
 *   - SEGURIDAD en el coche (no fiarse del móvil): nada que mueva asientos, abra
 *     puertas/maletero/ventanas/techo o apague el coche con el coche EN MOVIMIENTO. `powerOff`
 *     exige velocidad 0 conocida + marcha P + confirmación. Todo se registra en el log.
 *
 * Vías: principal por reflexión con [BydPermissionContext] (GET/SET de firma); para
 * lock/unlock/trunk/power-off hay además un refuerzo por el envelope `autoservice` vía ADB local.
 */
class CarCommands(
    private val ctx: Context,
    private val gateway: CarGateway,
    private val readState: () -> CarState?,
    private val autoCloseWindowsOnLock: () -> Boolean
) {
    private companion object {
        const val TAG = "BYDLink"
        // Constantes de BYDAutoBodyworkDevice (byd-car-sdk): ventanas.
        const val WIN_OPEN = 1; const val WIN_CLOSE = 2; const val WIN_STOP = 3; const val WIN_HALF = 4; const val WIN_VENT = 5
        // Setters permitidos en la orden de diagnóstico sdkCall.
        val SDK_SETTERS = setOf("setBodyWindowCtrlState", "setAllWindowState", "setMoonRoofState", "setMoonRoofAndSunshadeStop",
            "setAcStartState", "setAcTemperature", "setAcWindLevel", "setAcCycleMode", "setAcWindMode", "setAcDefrostState",
            "setSeatHeatingState", "setSeatVentilatingState", "setLightStatus", "setDoubleFlashLightState",
            // Vistos en Beetle Launcher: cierre (setting/doorlock), ventanas, volante, limpias, límite de carga.
            "setDoorLock", "setAllDoorsLock", "setDoorLockStatus", "setWindowControlState",
            "setSteeringWheelHeatingState", "setSteeringWheelWarmState", "setAutoRainWiperState",
            "setChargeLimit", "setSOCTarget", "setChargeStopCapacityState", "setChargeStopSwitchState", "setAcChargingLimit")

        // Feature IDs (símbolo del catálogo del coche + fallback numérico validado por la
        // comunidad en Leopard3/Seagull; en el Surf hay que confirmarlos con el sondeo).
        val FID_LOCK = BydReflect.fid("Bodywork.BODYWORK_FOUR_DOOR_SET", 1276141590)   // 1=unlock 2=lock
        val FID_FRUNK = BydReflect.fid("Setting.SETTING_ELECTRIC_FORECABIN_SWITCH_SET", 1276182560) // 1=open 3=close
        val FID_SUNROOF = BydReflect.fid("Bodywork.BODYWORK_MOON_ROOF_CTL_SET", 1125122056) // 1 open 2 close 3 tilt 4 stop 6 comfort
        val FID_SUNSHADE = BydReflect.fid("Bodywork.BODYWORK_SUNSHADE_PANEL_CTL_SET", 1125122060) // 1 open 2 close
        val FID_WIN_CTRL = intArrayOf(                                                   // 1 open 2 close 3 stop 4 half 5 vent
            BydReflect.fid("Bodywork.BODYWORK_LF_WINDOW_CTRL_SET", 1125122104),
            BydReflect.fid("Bodywork.BODYWORK_RF_WINDOW_CTRL_SET", 1125122107),
            BydReflect.fid("Bodywork.BODYWORK_LR_WINDOW_CTRL_SET", 1125122112),
            BydReflect.fid("Bodywork.BODYWORK_RR_WINDOW_CTRL_SET", 1125122115)
        )
        val FID_WIN_POS = intArrayOf(                                                    // 0..100 %
            BydReflect.fid("Bodywork.BODYWORK_LF_WINDOW_TARGET_POSITION_SET", 1276219408),
            BydReflect.fid("Bodywork.BODYWORK_RF_WINDOW_TARGET_POSITION_SET", 1276219424),
            BydReflect.fid("Bodywork.BODYWORK_LR_WINDOW_TARGET_POSITION_SET", 1276219416),
            BydReflect.fid("Bodywork.BODYWORK_RR_WINDOW_TARGET_POSITION_SET", 1276219432)
        )
        val FID_PREHEAT = BydReflect.fid("Charging.CHARGING_PREHEAT_SWITCH_SET", 1324617774) // 1=on 2=off
        val FID_DRIVE_MODE = BydReflect.fid("Setting.SETTING_TARGET_DRIVING_MODE_SET", 1276260400) // 1..21
        val FID_HAZARD = BydReflect.fid("Light.LIGHT_TURN_SIGNAL_LIGHT_SET", 871366669)  // 1=on 0=off
        val FID_INTERIOR = BydReflect.fid("Setting.SET_INSIDE_LIGHT_STATE_SET", 1330643002) // 2=on 1=off
        val FID_WHISTLE = BydReflect.fid("Security.SECURITY_HIGH_RISK_WHISTLE_WARNING_SET", -1)
        val FID_DFLASH = BydReflect.fid("Security.SECURITY_HIGH_RISK_DOUBLE_FLASH_WARNING_SET", -1)
        val FID_SENTRY = BydReflect.fid("Special.SPECIAL_SENTRY_MODE_SET", -1)
        val FID_SEAT_MEM = BydReflect.fid("Setting.SET_LF_MEMORY_LOCATION_SET", 1276186678)

        // Envelope autoservice (tx=40, writeInt(5);writeInt(op))
        const val OP_UNLOCK = 5
        const val OP_TRUNK = 6
        const val OP_LOCK = 7
        const val OP_POWER_OFF = 9
        const val OP_CLOSE_WINDOWS = 17
        const val OP_FLASH = 22
    }

    private val pctx = BydPermissionContext(ctx.applicationContext)
    private val sdk = BydReflect.sdkPresent()

    private fun dev(suffix: String) = BydReflect.device(pctx, "android.hardware.bydauto.$suffix")
    private val ac by lazy { dev("ac.BYDAutoAcDevice") }
    private val body by lazy { dev("bodywork.BYDAutoBodyworkDevice") }
    private val charge by lazy { dev("charging.BYDAutoChargingDevice") }
    private val setting by lazy { dev("setting.BYDAutoSettingDevice") }
    private val light by lazy { dev("light.BYDAutoLightDevice") }
    private val security by lazy { dev("security.BYDAutoSecurityDevice") }
    private val special by lazy { dev("special.BYDAutoSpecialDevice") }
    private val ota by lazy { dev("ota.BYDAutoOtaDevice") }

    // ----------------------------------------------------------------------------------------
    fun handle(action: String, p: Map<String, Any?>): Map<String, Any?>? {
        // Acciones de este handler:
        val known = action in ACTIONS
        if (!known) return null
        if (!sdk) {
            // Las comprobaciones de seguridad valen igual sin SDK (nunca se salta una confirmación).
            if (action == "powerOff") {
                val s = readState() ?: err("No puedo apagar el coche: no conozco su estado ahora mismo.")
                if (s.speed != 0 || s.gear != "P") err("No puedo apagar el coche: no está parado en P.")
                if (sv(p, "confirm") != "APAGAR") err("Apagado cancelado: falta la confirmación.")
            }
            return handleMock(action, p)
        }
        Log.i(TAG, "comando $action $p")
        return when (action) {
            // --- cierre ---
            "setLock" -> setLock(bv(p, "locked") ?: true)
            "lock" -> setLock(true)
            "unlock" -> setLock(false)
            // --- ventanas / techo ---
            "openWindows" -> windowsAll(open = true)
            "closeWindows" -> windowsAll(open = false)
            "setWindow" -> setWindow(iv(p, "area") ?: err("Falta 'area' (1-4)."), p)
            "setSunroof" -> setRoof(FID_SUNROOF, sv(p, "state") ?: (if (bv(p, "open") == true) "open" else "close"), "techo")
            "setSunshade" -> setRoof(FID_SUNSHADE, sv(p, "state") ?: (if (bv(p, "open") == true) "open" else "close"), "cortinilla")
            // --- maletero / frunk / tapa de carga ---
            "openTrunk" -> trunk(open = true)
            "closeTrunk" -> trunk(open = false)
            "openFrunk" -> frunk()
            "setChargePort" -> chargePort(bv(p, "locked") ?: false)
            // --- clima ---
            "setClimate" -> climate(p)
            "setDefrost" -> defrost(p)
            // --- asientos ---
            "setSeatComfort" -> seatComfort(iv(p, "driver"), iv(p, "passenger"))
            "setSeatHeat" -> seatSingle(iv(p, "pos") ?: 0, (iv(p, "level") ?: 0), heat = true)
            "setSeatVent" -> seatSingle(iv(p, "pos") ?: 0, (iv(p, "level") ?: 0), heat = false)
            "loadSeatMemory" -> seatMemory(iv(p, "slot") ?: 0)
            // --- carga ---
            "setChargeLimit" -> chargeLimit(iv(p, "percent") ?: err("Falta 'percent' (60-100)."))
            "batteryPreheat" -> preheat(bv(p, "on") ?: true)
            // --- conducción / modo valet ---
            "setDriveMode" -> driveMode(p)
            "setOverspeedLock" -> overspeed(bv(p, "on") ?: false)
            "setSpeedLimit" -> speedLimit(iv(p, "kmh"))
            // --- luces / localizar ---
            "flashLights" -> flash(whistle = false)
            "locateCar" -> flash(whistle = bv(p, "whistle") ?: true)
            "setInteriorLight" -> interiorLight(bv(p, "on") ?: false)
            "setMirrorHeat" -> mirrorHeat(bv(p, "on") ?: false)
            // --- antirrobo ---
            "setSentry" -> sentry(bv(p, "on") ?: false)
            "powerOff" -> powerOff(p)
            "sdkCall" -> sdkCall(p)
            else -> null
        }
    }

    private val ACTIONS = setOf(
        "setLock", "lock", "unlock", "openWindows", "closeWindows", "setWindow", "setSunroof", "setSunshade",
        "openTrunk", "closeTrunk", "openFrunk", "setChargePort", "setClimate", "setDefrost",
        "setSeatComfort", "setSeatHeat", "setSeatVent", "loadSeatMemory", "setChargeLimit", "batteryPreheat",
        "setDriveMode", "setOverspeedLock", "setSpeedLimit", "flashLights", "locateCar", "setInteriorLight",
        "setMirrorHeat", "setSentry", "powerOff", "sdkCall"
    )

    /** Sin SDK (desarrollo en móvil/emulador): delega los mandos básicos en el gateway simulado. */
    private fun handleMock(action: String, p: Map<String, Any?>): Map<String, Any?> {
        // Solo se dice "aplicado" de lo que el coche simulado hace de verdad.
        var simulated = true
        when (action) {
            "setLock", "lock", "unlock" -> gateway.setLock(if (action == "unlock") false else bv(p, "locked") ?: (action != "unlock"))
            "openWindows" -> gateway.setWindows(true)
            "closeWindows" -> gateway.setWindows(false)
            "setClimate" -> gateway.setClimate(bv(p, "on") ?: false, iv(p, "temp") ?: 21)
            "setSeatComfort" -> gateway.setSeatComfort(iv(p, "driver"), iv(p, "passenger"))
            "loadSeatMemory" -> gateway.loadSeatMemory(iv(p, "slot") ?: 0)
            else -> simulated = false
        }
        return mapOf("applied" to simulated, "mock" to true)
    }

    // ===================== DIAGNÓSTICO (calibrar en el coche) =====================
    /**
     * `sdkCall {device:"bodywork", method:"setBodyWindowCtrlState", args:[1,1]}` → llama al SDK y devuelve el
     * resultado y (si es un setter) el estado leído después. Lecturas (get*) libres; setters solo los de
     * [SDK_SETTERS] y con el coche parado. Sirve para averiguar los valores que acepta cada modelo.
     */
    private fun sdkCall(p: Map<String, Any?>): Map<String, Any?> {
        val devName = sv(p, "device") ?: err("Falta 'device'.")
        val method = sv(p, "method") ?: err("Falta 'method'.")
        val args = (p["args"] as? List<*>)?.map { (it as? Number)?.toInt() ?: err("Los argumentos deben ser números.") } ?: emptyList()
        val d = when (devName) {
            "bodywork" -> body; "ac" -> ac; "setting" -> setting; "light" -> light
            // "instrument" → instrument.BYDAutoInstrumentDevice; los nombres raros se pasan completos
            // (p. ej. "pm2p5.BYDAutoPM2p5Device").
            else -> (if ('.' in devName) dev(devName)
                     else dev("$devName.BYDAuto${devName.replaceFirstChar { it.uppercase() }}Device"))
                ?: err("Dispositivo desconocido: $devName")
        }
        val isGet = method.startsWith("get")
        if (!isGet) {
            if (method !in SDK_SETTERS) err("Ese método no está permitido en diagnóstico: $method")
            requireStopped("probar mandos")
        }
        Log.w(TAG, "sdkCall $devName.$method$args")
        val r = BydReflect.call(d, method, *args.toTypedArray())
        Thread.sleep(if (isGet) 0 else 600)
        val s = readState()
        return mapOf("result" to r?.toString(), "windows" to s?.windows, "windowsPct" to s?.windowsPct, "locked" to readLocked())
    }

    // ===================== CIERRE =====================
    private fun setLock(locked: Boolean): Map<String, Any?> {
        val v = if (locked) 2 else 1
        var ok = BydReflect.setFeature(body, FID_LOCK, v) != null
        if (!ok) ok = autoservice(if (locked) OP_LOCK else OP_UNLOCK)
        if (!ok) { BydReflect.call(body, "lockDevice", locked); ok = true }
        if (locked && autoCloseWindowsOnLock()) runCatching { windowsAll(open = false) }
        val real = readLocked()
        Log.i(TAG, "setLock($locked) -> real=$real")
        return mapOf("applied" to (real == null || real == locked), "locked" to real)
    }

    private fun readLocked(): Boolean? {
        val lf = BydReflect.san(BydReflect.callInt(ota, "getLFDoorLockState"))
            ?: BydReflect.featureInt(ota, BydReflect.fid("Ota.OTA_LF_DOOR_LOCK", 1081081864))
        when (lf) { 2 -> return true; 1 -> return false }
        return BydReflect.san(BydReflect.callInt(body, "getAutoSystemState"))?.let { it >= 1 }
    }

    // ===================== VENTANAS / TECHO =====================
    private fun windowsAll(open: Boolean): Map<String, Any?> {
        if (open) requireStopped("bajar las ventanas")       // cerrar se permite siempre (seguridad)
        val st = if (open) WIN_OPEN else WIN_CLOSE
        val before = readState()?.windowsPct?.fl
        var ok = BydReflect.call(body, "setAllWindowState", st, st, st, st) != null
        if (!ok) { for (i in 1..4) if (BydReflect.setFeature(body, FID_WIN_CTRL[i - 1], if (open) 1 else 2) != null) ok = true }
        if (!open && !ok) ok = autoservice(OP_CLOSE_WINDOWS)
        learnWindows(before, open)
        val w = readState()?.windows
        return mapOf("applied" to ok, "windows" to w)
    }

    /**
     * Multimodelo: si la ventana del conductor debía moverse y no se mueve, este coche no deja moverlas desde
     * las apps (el Surf acepta la orden y no hace nada) → la web deja de ofrecer el mando. Si se mueve, al revés.
     */
    private fun learnWindows(before: Int?, wantOpen: Boolean) {
        if (before == null || !(if (wantOpen) before < 90 else before > 5)) { Thread.sleep(400); return }
        Thread.sleep(2500)
        val after = readState()?.windowsPct?.fl ?: return
        Capabilities.learn(ctx, "windows", after != before, com.bydlink.carapp.BuildConfigCompat.carId(ctx))
    }

    private fun setWindow(area: Int, p: Map<String, Any?>): Map<String, Any?> {
        if (area !in 1..4) err("Área de ventana inválida (usa 1=del.izq, 2=del.der, 3=tras.izq, 4=tras.der).")
        val pct = iv(p, "percent")
        val open = bv(p, "open")
        if ((pct != null && pct > 10) || open == true) requireStopped("bajar una ventana")
        var ok: Boolean
        val before = if (area == 1) readState()?.windowsPct?.fl else null   // solo la del conductor se lee en el Surf
        if (pct != null) {
            ok = BydReflect.setFeature(body, FID_WIN_POS[area - 1], pct.coerceIn(0, 100)) != null
            if (ok) BydReflect.setFeature(body, FID_WIN_POS[area - 1], 255)  // "sin petición", como las apps BYD
        } else {
            val st = when (sv(p, "mode")) { "half" -> WIN_HALF; "vent" -> WIN_VENT; "stop" -> WIN_STOP
                else -> if (open == true) WIN_OPEN else WIN_CLOSE }
            ok = BydReflect.call(body, "setBodyWindowCtrlState", area, st) != null
            if (!ok) ok = BydReflect.setFeature(body, FID_WIN_CTRL[area - 1], if (open == true) 1 else 2) != null
        }
        if (open != null && sv(p, "mode") == null) learnWindows(before, open) else Thread.sleep(300)
        return mapOf("applied" to ok, "windows" to readState()?.windows, "windowsPct" to readState()?.windowsPct)
    }

    private fun setRoof(fid: Int, state: String, name: String): Map<String, Any?> {
        val code = when (state.lowercase()) {
            "open", "abrir" -> 1; "close", "cerrar" -> 2; "tilt" -> 3; "stop", "parar" -> 4; "comfort" -> 6
            else -> state.toIntOrNull() ?: 1
        }
        if (code != 2 && code != 4) requireStopped("mover el $name")
        // Preferimos los setters con nombre (en el Surf: setMoonRoofState / setSunshadeState).
        val named = if (fid == FID_SUNROOF) "setMoonRoofState" else "setSunshadeState"
        var ok = BydReflect.call(body, named, code) != null
        if (!ok) ok = BydReflect.setFeature(body, fid, code) != null
        Thread.sleep(300)
        return mapOf("applied" to ok, "windows" to readState()?.windows)
    }

    // ===================== MALETERO / FRUNK / TAPA CARGA =====================
    private fun trunk(open: Boolean): Map<String, Any?> {
        if (open) requireStopped("abrir el maletero")
        var ok = BydReflect.call(setting, "voiceCtlBackDoor", if (open) 1 else 2) != null
        if (!ok) ok = BydReflect.call(setting, "setBackDoorElectricMode", if (open) 1 else 0) != null
        if (open && !ok) ok = autoservice(OP_TRUNK)
        return mapOf("applied" to ok, "trunk" to readState()?.doors?.trunk)
    }

    private fun frunk(): Map<String, Any?> {
        requireStopped("abrir el maletero delantero")
        val ok = BydReflect.setFeature(body, FID_FRUNK, 1) != null
        return mapOf("applied" to ok)
    }

    private fun chargePort(locked: Boolean): Map<String, Any?> {
        val ok = BydReflect.call(setting, "setChargingPort", if (locked) 1 else 0) != null
        val real = BydReflect.san(BydReflect.callInt(setting, "getChargingPort"))
        return mapOf("applied" to ok, "chargePort" to real)
    }

    // ===================== CLIMA =====================
    private fun climate(p: Map<String, Any?>): Map<String, Any?> {
        val on = bv(p, "on")
        if (on != null) { if (on) BydReflect.call(ac, "start", 0) else BydReflect.call(ac, "stop", 0) }
        // Automático antes que temperatura y ventilador: cambiar de modo después podría pisarlos.
        bv(p, "auto")?.let { BydReflect.call(ac, "setAcControlMode", 0, if (it) 0 else 1) }
        val temp = iv(p, "temp")?.coerceIn(17, 33)
        temp?.let { tc ->
            if (BydReflect.call(ac, "setAcTemperature", 1, tc, 1, 1) == null)
                BydReflect.call(ac, "setTemperature", tc.toFloat())
        }
        val fan = iv(p, "fan")?.coerceIn(0, 7)
        fan?.let { l ->
            if (BydReflect.call(ac, "setAcWindLevel", 0, l) == null)
                BydReflect.setFeature(ac, BydReflect.fid("Ac.AC_WIND_LEVEL_SET", 0x1DE0000C), l)
        }
        // Salida del aire: 1 cara, 2 cara+pies, 3 pies, 4 pies+parabrisas, 5 parabrisas, 6 los tres, 7 cara+parabrisas.
        val mode = iv(p, "mode")?.coerceIn(1, 7)
        mode?.let { BydReflect.call(ac, "setAcWindMode", 0, it) }
        bv(p, "recirc")?.let { BydReflect.call(ac, "setAcCycleMode", 0, if (it) 1 else 0) }
        bv(p, "maxCooling")?.let { BydReflect.call(ac, "setAcMaxCoolingState", if (it) 1 else 0) }
        bv(p, "ventilation")?.let { BydReflect.call(ac, "setAcVentilationState", 0, if (it) 1 else 0) }
        bv(p, "defrostFront")?.let { BydReflect.call(ac, "setAcDefrostState", 0, 1, if (it) 1 else 0) }
        bv(p, "defrostRear")?.let { BydReflect.call(ac, "setAcDefrostState", 0, 2, if (it) 1 else 0) }
        // El climatizador tarda en reflejar el cambio (más de medio segundo): esperar a verlo, hasta ~3 s.
        var c = readState()?.climate
        for (i in 0 until 10) {
            val done = (temp == null || c?.temp == temp) && (on == null || c?.on == on) && (fan == null || fan == 0 || c?.fan == fan) &&
                (mode == null || c?.mode == mode)
            if (done) break
            Thread.sleep(300); c = readState()?.climate
        }
        return mapOf("applied" to true, "climate" to c)
    }

    private fun defrost(p: Map<String, Any?>): Map<String, Any?> {
        bv(p, "front")?.let { BydReflect.call(ac, "setAcDefrostState", 0, 1, if (it) 1 else 0) }
        bv(p, "rear")?.let { BydReflect.call(ac, "setAcDefrostState", 0, 2, if (it) 1 else 0) }
        return mapOf("applied" to true, "climate" to readState()?.climate)
    }

    // ===================== ASIENTOS =====================
    private fun seatComfort(driver: Int?, passenger: Int?): Map<String, Any?> {
        driver?.let { applySeat(0, it.coerceIn(-2, 2)) }
        passenger?.let { applySeat(1, it.coerceIn(-2, 2)) }
        return mapOf("applied" to true, "seatComfort" to readState()?.seatComfort)
    }
    private fun seatSingle(pos: Int, level: Int, heat: Boolean): Map<String, Any?> {
        val stage = if (heat) level.coerceIn(0, 2) else -level.coerceIn(0, 2)
        applySeat(pos, stage)
        return mapOf("applied" to true, "seatComfort" to readState()?.seatComfort)
    }
    // Device correcto: SETTING (no instrument). Calefacción y ventilación son excluyentes.
    private fun applySeat(pos: Int, stage: Int) {
        if (stage >= 0) {
            BydReflect.call(setting, "setSeatVentilatingState", pos, 0)
            BydReflect.call(setting, "setSeatHeatingState", pos, stage)
        } else {
            BydReflect.call(setting, "setSeatHeatingState", pos, 0)
            BydReflect.call(setting, "setSeatVentilatingState", pos, -stage)
        }
    }
    private fun seatMemory(slot: Int): Map<String, Any?> {
        requireStopped("recuperar la posición del asiento")
        var ok = BydReflect.setFeature(setting, FID_SEAT_MEM, slot) != null
        if (!ok) ok = BydReflect.call(setting, "loadSeatMemory", slot) != null
        return mapOf("applied" to ok, "slot" to slot)
    }

    // ===================== CARGA =====================
    /**
     * Límite de carga como lo hacen los Ajustes del propio coche (CarSetting, clase ChargeLimit):
     * `charging.setChargeStopCapacityState(código)` con 1 = sin límite, 2 = 90 %, 3 = 80 %, 4 = 70 %, 5 = 60 %.
     * (setSOCTarget es otra cosa y en el Surf lo rechaza.) El Surf acepta la orden pero no la devuelve al leer,
     * así que se guarda lo pedido para enseñarlo y se confirma en cuanto el coche lo informe.
     */
    private fun chargeLimit(percent: Int): Map<String, Any?> {
        val pct = listOf(60, 70, 80, 90, 100).minByOrNull { kotlin.math.abs(it - percent) }!!
        val code = ChargeLimits.codeOf(pct)
        // El límite tiene además su interruptor (CHARGING_STOP_QUANTITY_SWITCH): en la carga del 31/10/2026 el Surf aceptó
        // solo el código (80 %) y cargó hasta el 96 %. Se enciende antes (y se apaga con "sin límite") por si es eso.
        val support = BydReflect.san(BydReflect.callInt(charge, "getChargeStopSupportConfig"))
        val sw = BydReflect.call(charge, "setChargeStopSwitchState", if (pct < 100) 1 else 0)
        val r = BydReflect.call(charge, "setChargeStopCapacityState", code)
        ChargeLimits.remember(ctx, pct)
        Thread.sleep(500)
        val real = ChargeLimits.read(charge)
        val swReal = BydReflect.san(BydReflect.callInt(charge, "getChargeStopSwitchState"))
        Log.i(TAG, "setChargeLimit($pct) code=$code support=$support switch=$sw→$swReal set=$r→$real")
        return mapOf("applied" to (if (real != null) real == pct else if (r != null) null else false),
            "chargeLimit" to (real ?: pct), "confirmed" to (real != null),
            "supportConfig" to support, "switch" to swReal, "ignored" to ChargeLimits.ignored(ctx))
    }
    private fun preheat(on: Boolean): Map<String, Any?> {
        val ok = BydReflect.setFeature(charge, FID_PREHEAT, if (on) 1 else 2) != null
        return mapOf("applied" to ok)
    }

    // ===================== CONDUCCIÓN / VALET =====================
    private fun driveMode(p: Map<String, Any?>): Map<String, Any?> {
        val mode = iv(p, "mode") ?: when (sv(p, "mode")?.lowercase()) {
            "normal" -> 1; "eco" -> 2; "sport" -> 3; "snow", "nieve" -> 4; "sand", "arena" -> 5
            "mud", "barro" -> 6; "mountain", "montaña" -> 7; "smart" -> 21; else -> err("Modo de conducción desconocido.")
        }
        val s = readState()
        if (s != null && s.speed > 15) err("No se puede cambiar el modo de conducción por encima de 15 km/h.")
        val ok = BydReflect.setFeature(setting, FID_DRIVE_MODE, mode) != null
        return mapOf("applied" to ok, "mode" to mode)
    }
    private fun overspeed(on: Boolean): Map<String, Any?> {
        val ok = BydReflect.call(setting, "setOverspeedLock", if (on) 1 else 0) != null
        val real = BydReflect.san(BydReflect.callInt(setting, "getOverspeedLock"))
        return mapOf("applied" to ok, "overspeedLock" to real)
    }
    private fun speedLimit(kmh: Int?): Map<String, Any?> {
        kmh ?: err("Falta 'kmh'.")
        val ok = BydReflect.call(setting, "setSpeedReminderValue", kmh) != null
        return mapOf("applied" to ok, "speedLimit" to BydReflect.san(BydReflect.callInt(setting, "getSpeedReminderValue")))
    }

    // ===================== LUCES / LOCALIZAR =====================
    private fun flash(whistle: Boolean): Map<String, Any?> {
        fun hazardOn() = BydReflect.san(BydReflect.callInt(light, "getDoubleFlashLightState")) == 1
        val wasOn = hazardOn()
        if (FID_DFLASH > 0) BydReflect.setFeature(security, FID_DFLASH, 1)
        if (whistle && FID_WHISTLE > 0) BydReflect.setFeature(security, FID_WHISTLE, 1)
        BydReflect.setFeature(light, FID_HAZARD, 1)
        if (!hazardOn()) autoservice(OP_FLASH)
        // Que el SDK acepte la orden no prueba nada: en el Surf (DiLink 3.0) la acepta y no pasa nada, porque
        // allí BYD no da a las apps claxon ni emergencias (solo leerlas; lo hace su nube). Se comprueba leyendo.
        var seen = false
        for (i in 0 until 6) { Thread.sleep(250); if (hazardOn()) { seen = true; break } }
        if (!wasOn) Capabilities.learn(ctx, "locate", seen, com.bydlink.carapp.BuildConfigCompat.carId(ctx))
        if (seen && !wasOn) Thread {
            Thread.sleep(6000)
            BydReflect.setFeature(light, FID_HAZARD, 0)
            if (FID_DFLASH > 0) BydReflect.setFeature(security, FID_DFLASH, 0)
        }.start()
        if (!seen && !wasOn) return mapOf("applied" to false, "unsupported" to true, "whistle" to whistle,
            "error" to "Este coche no deja a las apps tocar el claxon ni encender las luces de emergencia.")
        return mapOf("applied" to true, "whistle" to whistle)
    }
    private fun interiorLight(on: Boolean): Map<String, Any?> {
        val ok = BydReflect.setFeature(setting, FID_INTERIOR, if (on) 2 else 1) != null
        return mapOf("applied" to ok)
    }
    private fun mirrorHeat(on: Boolean): Map<String, Any?> {
        // En Leopard/Surf la calefacción de espejos va unida al desempañador trasero.
        val ok = BydReflect.call(ac, "setAcDefrostState", 0, 2, if (on) 1 else 0) != null
        return mapOf("applied" to ok)
    }

    // ===================== ANTIRROBO =====================
    private fun sentry(on: Boolean): Map<String, Any?> {
        var ok = false
        // 1) centinela nativo del coche vía Settings.Global (WRITE_SECURE_SETTINGS concedido).
        ok = try { Settings.Global.putInt(ctx.contentResolver, "sentrymode_enabled_switch", if (on) 1 else 0) } catch (e: Throwable) { false } || ok
        // 2) feature id del dispositivo special (si existe en este modelo).
        if (FID_SENTRY > 0) ok = BydReflect.setFeature(special, FID_SENTRY, if (on) 1 else 0) != null || ok
        Log.i(TAG, "setSentry($on) -> $ok")
        return mapOf("applied" to ok)
    }

    private fun powerOff(p: Map<String, Any?>): Map<String, Any?> {
        val s = readState() ?: err("No puedo apagar el coche: no conozco su estado ahora mismo.")
        if (s.speed != 0) err("No puedo apagar el coche: no está parado (velocidad ${s.speed} km/h).")
        if (s.gear != "P") err("No puedo apagar el coche: la marcha no está en P.")
        if (sv(p, "confirm") != "APAGAR") err("Apagado cancelado: falta la confirmación (payload confirm=\"APAGAR\").")
        Log.w(TAG, "powerOff CONFIRMADO con coche parado en P — enviando OP_POWER_OFF")
        val ok = autoservice(OP_POWER_OFF)
        Thread.sleep(800)
        val lvl = readState()?.powerState
        return mapOf("applied" to ok, "powerState" to lvl)
    }

    // ===================== ENVELOPE autoservice (ADB local) =====================
    /** `service call autoservice 40 i32 5 i32 <op>`. Devuelve true si el shell respondió sin error. */
    private fun autoservice(op: Int): Boolean {
        val sh = CarShell(ctx.filesDir)
        return try {
            if (!sh.connect(BuildConfigCompat.adbHost(ctx)) || !sh.works()) {
                Log.w(TAG, "autoservice($op): sin ADB local"); false
            } else {
                val out = sh.shell("service call autoservice 40 i32 5 i32 $op")
                Log.i(TAG, "autoservice($op) -> ${out?.trim()}")
                out != null && out.contains("Parcel")
            }
        } catch (e: Throwable) { Log.e(TAG, "autoservice($op) error", e); false } finally { sh.close() }
    }

    // ===================== Gating de seguridad + utilidades =====================
    private fun moving(): Boolean {
        val s = readState() ?: return true   // estado desconocido → fail-closed (se bloquea)
        return s.speed in 1..300 || s.gear == "D" || s.gear == "R"
    }
    private fun requireStopped(what: String) {
        if (moving()) throw IllegalStateException("No se puede $what con el coche en movimiento.")
    }
    private fun err(msg: String): Nothing = throw IllegalArgumentException(msg)

    private fun iv(p: Map<String, Any?>, k: String): Int? = (p[k] as? Number)?.toInt()
    private fun bv(p: Map<String, Any?>, k: String): Boolean? = p[k] as? Boolean
    private fun sv(p: Map<String, Any?>, k: String): String? = p[k] as? String
}
