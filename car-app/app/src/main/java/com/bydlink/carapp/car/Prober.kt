package com.bydlink.carapp.car

import android.content.Context
import com.bydlink.carapp.App
import com.bydlink.carapp.BuildConfigCompat
import com.google.firebase.database.ServerValue
import java.lang.reflect.Modifier

/**
 * Sondeo de SOLO-LECTURA del coche (nunca setters): lista por reflexión los métodos REALES del
 * SDK BYD y del framework de cámara, prueba getters sin args, getters CON argumento (puertas,
 * ventanas, neumáticos, cinturones, luces, radar…), lecturas por FEATURE ID (BMS, 12 V, cierre…)
 * y unas pocas lecturas por shell (ADB local). Sirve para afinar el código a este modelo exacto
 * SIN ejecutar comandos arbitrarios (no es un shell abierto).
 */
object Prober {

    private val DEVICES = linkedMapOf(
        "ac" to "android.hardware.bydauto.ac.BYDAutoAcDevice",
        "bodywork" to "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
        "charging" to "android.hardware.bydauto.charging.BYDAutoChargingDevice",
        "gearbox" to "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
        "instrument" to "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
        "speed" to "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
        "tyre" to "android.hardware.bydauto.tyre.BYDAutoTyreDevice",
        "statistic" to "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
        "light" to "android.hardware.bydauto.light.BYDAutoLightDevice",
        "setting" to "android.hardware.bydauto.setting.BYDAutoSettingDevice",
        // Devices que faltaban por sondear:
        "power" to "android.hardware.bydauto.power.BYDAutoPowerDevice",
        "special" to "android.hardware.bydauto.special.BYDAutoSpecialDevice",
        "doorlock" to "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice",
        "safetybelt" to "android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice",
        "radar" to "android.hardware.bydauto.radar.BYDAutoRadarDevice",
        "sensor" to "android.hardware.bydauto.sensor.BYDAutoSensorDevice",
        "energy" to "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
        "engine" to "android.hardware.bydauto.engine.BYDAutoEngineDevice",
        "pm2p5" to "android.hardware.bydauto.pm2p5.BYDAutoPM2p5Device",
        "time" to "android.hardware.bydauto.time.BYDAutoTimeDevice",
        "ota" to "android.hardware.bydauto.ota.BYDAutoOtaDevice",
        "security" to "android.hardware.bydauto.security.BYDAutoSecurityDevice",
        "multimedia" to "android.hardware.bydauto.multimedia.BYDAutoMultimediaDevice",
        // Los que usa Beetle Launcher y aún no mirábamos (puede que el Surf no tenga muchos).
        "collectdata" to "android.hardware.bydauto.collectdata.BYDAutoCollectDataDevice",   // motor: rpm, par, temp.
        "collision" to "android.hardware.bydauto.collision.BYDAutoCollisionDevice",
        "seat" to "android.hardware.bydauto.seat.BYDAutoSeatDevice",
        "wiper" to "android.hardware.bydauto.wiper.BYDAutoWiperDevice",
        "vehiclehealth" to "android.hardware.bydauto.vehiclehealth.BYDAutoVehicleHealthDevice",
        "doormirror" to "android.hardware.bydauto.doormirror.BYDAutoRearViewMirrorDevice",
        "motor" to "android.hardware.bydauto.motor.BYDAutoMotorDevice",
        "adas" to "android.hardware.bydauto.adas.BYDAutoADASDevice",
        "dipilot" to "android.hardware.bydauto.dipilot.BYDAutoDiPilotDevice",
        "panorama" to "android.hardware.bydauto.panorama.BYDAutoPanoramaDevice",
        "audio" to "android.hardware.bydauto.audio.BYDAutoAudioDevice",
        "vehicledata" to "android.hardware.bydauto.vehicledata.BYDAutoVehicleDataDevice",
        "whud" to "android.hardware.bydauto.whud.BYDAutoWhudDevice",
        "gb" to "android.hardware.bydauto.gb.BYDAutoGBDevice"
    )

    // Getters con argumento a sondear: device -> (método, rango de índices).
    private val ARG_GETTERS = listOf(
        Triple("bodywork", "getDoorState", 1..7),
        Triple("bodywork", "getWindowState", 1..4),
        Triple("bodywork", "getWindowOpenPercent", 1..6),
        Triple("tyre", "getTyrePressureValue", 1..4),
        Triple("tyre", "getTyrePressureState", 1..4),
        Triple("tyre", "getTyreAirLeakState", 1..4),
        Triple("tyre", "getTyreBatteryValue", 1..4),
        Triple("instrument", "getWheelTemperature", 1..4),
        Triple("instrument", "getWheelPressure", 1..4),
        Triple("safetybelt", "getSafetyBeltStatus", 1..5),
        Triple("safetybelt", "getPassengerStatus", 1..5),
        Triple("instrument", "getSafetyBeltStatus", 1..5),
        Triple("ac", "getTemprature", 1..4),
        Triple("ac", "getAcDefrostState", 1..2),
        Triple("radar", "getRadarProbeState", 0..8),
        Triple("light", "getLightStatus", 1..8),
        Triple("doorlock", "getDoorLockStatus", 1..7),
        Triple("setting", "getSeatHeatingState", 0..4),
        Triple("setting", "getSeatVentilatingState", 0..4)
    )

    // Lecturas por feature ID: device -> (símbolo, fallback, tipo 'i'/'d').
    private data class Feat(val device: String, val symbol: String, val fallback: Int, val type: Char)
    private val FEATURES = listOf(
        Feat("statistic", "Statistic.STATISTIC_BATTERY_HEALTHY_INDEX", 1145045032, 'i'),
        Feat("statistic", "Statistic.STATISTIC_HIGHEST_BATTERY_TEMP", 1148190752, 'i'),
        Feat("statistic", "Statistic.STATISTIC_LOWEST_BATTERY_TEMP", 1148190736, 'i'),
        Feat("statistic", "Statistic.STATISTIC_AVERAGE_BATTERY_TEMP", 1148190776, 'i'),
        Feat("statistic", "Statistic.STATISTIC_HIGHEST_BATTERY_VOLTAGE", 1147142192, 'i'),
        Feat("statistic", "Statistic.STATISTIC_LOWEST_BATTERY_VOLTAGE", 1147142160, 'i'),
        Feat("charging", "Charging.CHARGING_CHARGE_BATTERY_VOLT", 1145045000, 'i'),
        Feat("engine", "Engine.ENGINE_POWER", 339738656, 'i'),
        Feat("engine", "Engine.ENGINE_FRONT_MOTOR_SPEED", 1141899272, 'i'),
        Feat("engine", "Engine.ENGINE_REAR_MOTOR_SPEED", 621805576, 'i'),
        Feat("ota", "Ota.OTA_BATTERY_POWER_VOLTAGE", 1128267816, 'd'),
        Feat("ota", "Ota.OTA_LF_DOOR_LOCK", 1081081864, 'i'),
        Feat("instrument", "Instrument.INSTRUMENT_2IN1_LF_TYRE_TEMPERATURE", 1246797848, 'i'),
        Feat("instrument", "Instrument.INSTRUMENT_2IN1_RF_TYRE_TEMPERATURE", 1246797860, 'i'),
        Feat("instrument", "Instrument.INSTRUMENT_2IN1_LB_TYRE_TEMPERATURE", 1246797872, 'i'),
        Feat("instrument", "Instrument.INSTRUMENT_2IN1_RB_TYRE_TEMPERATURE", 1246797884, 'i'),
        Feat("setting", "Setting.SETTING_TARGET_DRIVING_MODE", 255852712, 'i')
    )

    fun run(ctx: Context): String {
        val pctx = BydPermissionContext(ctx.applicationContext)  // salta permisos GET/SET
        val insts = HashMap<String, Any?>()
        val sb = StringBuilder()
        sb.appendLine("== BYDLink PROBE == ${java.util.Date()} (con BydPermissionContext)")
        sb.appendLine("carId=${BuildConfigCompat.carId(ctx)} vCode=${BuildConfigCompat.versionCode(ctx)}")

        sb.appendLine("\n== Permisos ==")
        for (p in listOf(
            "android.permission.CAMERA", "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.BYDAUTO_AC_COMMON", "android.permission.BYDAUTO_BODYWORK_COMMON",
            "android.permission.BYDAUTO_PANORAMA_COMMON", "android.permission.BYDAUTO_TYRE_COMMON",
            "android.permission.BYDAUTO_CHARGING_COMMON", "android.permission.BYDAUTO_INSTRUMENT_COMMON"
        )) {
            sb.appendLine("  ${if (ctx.checkSelfPermission(p) == 0) "OK   " else "deny "} $p")
        }

        for ((name, cls) in DEVICES) {
            sb.appendLine("\n== $name :: $cls ==")
            try {
                val c = Class.forName(cls)
                val inst = try { c.getMethod("getInstance", Context::class.java).invoke(null, pctx) }
                           catch (e: Throwable) { try { c.getMethod("getInstance").invoke(null) } catch (e2: Throwable) { null } }
                insts[name] = inst
                sb.appendLine("  getInstance -> ${if (inst != null) "ok" else "null (${name})"}")
                sb.appendLine("  -- métodos --")
                c.methods.filter { it.declaringClass == c }.sortedBy { it.name }.forEach { m ->
                    val st = if (Modifier.isStatic(m.modifiers)) "static " else ""
                    sb.appendLine("    $st${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString(","){it.simpleName}})")
                }
                if (inst != null) {
                    sb.appendLine("  -- getters sin args --")
                    c.methods.filter { it.parameterTypes.isEmpty() && (it.name.startsWith("get") || it.name.startsWith("is") || it.name.startsWith("has")) }
                        .sortedBy { it.name }.forEach { m ->
                            val v = try { m.invoke(inst) } catch (e: Throwable) { "EX:${e.cause?.javaClass?.simpleName}:${e.cause?.message ?: e.message}" }
                            sb.appendLine("    ${m.name}() = ${fmt(v)}")
                        }
                }
            } catch (e: Throwable) { sb.appendLine("  NO disponible: ${e.message}") }
        }

        // Getters CON argumento (el probe anterior solo probaba sin-args).
        sb.appendLine("\n== Getters con argumento ==")
        for ((dname, method, range) in ARG_GETTERS) {
            val inst = insts[dname] ?: continue
            for (i in range) {
                val v = try {
                    val m = inst.javaClass.methods.firstOrNull { it.name == method && it.parameterTypes.size == 1 } ?: break
                    m.invoke(inst, i)
                } catch (e: Throwable) { "EX:${e.cause?.message ?: e.message}" }
                sb.appendLine("  $dname.$method($i) = ${fmt(v)}")
            }
        }

        // Lecturas por FEATURE ID (get(int[],Class)).
        sb.appendLine("\n== Lecturas por feature id ==")
        for (f in FEATURES) {
            val inst = insts[f.device] ?: continue
            val fid = BydReflect.fid(f.symbol, f.fallback)
            val v = if (f.type == 'd') BydReflect.featureDouble(inst, fid) else BydReflect.featureInt(inst, fid)
            sb.appendLine("  ${f.device} ${f.symbol} (0x${Integer.toHexString(fid)}) = $v")
        }

        // Lecturas por SHELL (ADB local, SOLO lectura).
        sb.append(shellProbe(ctx))

        // IAccModeManager (si la clase existe): lista de métodos por reflexión.
        sb.appendLine("\n== android.os.IAccModeManager ==")
        try {
            val c = Class.forName("android.os.IAccModeManager")
            c.methods.sortedBy { it.name }.forEach { m ->
                sb.appendLine("  ${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString(","){it.simpleName}})")
            }
        } catch (e: Throwable) { sb.appendLine("  no existe: ${e.message}") }

        sb.append(cameraProbe(ctx))
        return sb.toString()
    }

    private fun shellProbe(ctx: Context): String {
        val sb = StringBuilder("\n== Shell (ADB local, solo lectura) ==\n")
        val sh = CarShell(ctx.filesDir)
        try {
            if (!sh.connect(BuildConfigCompat.adbHost(ctx)) || !sh.works()) {
                sb.appendLine("  (sin ADB local)"); return sb.toString()
            }
            for (cmd in listOf(
                "service list | grep -i acc",
                "settings get global ssc_whitelist",
                "dumpsys package com.byd.appstartmanagement | grep -i -A2 provider"
            )) {
                sb.appendLine("  \$ $cmd")
                (sh.shell(cmd) ?: "(null)").lineSequence().take(40).forEach { sb.appendLine("    $it") }
            }
        } catch (e: Throwable) { sb.appendLine("  EX: ${e.message}") } finally { sh.close() }
        return sb.toString()
    }

    private fun cameraProbe(ctx: Context): String {
        val sb = StringBuilder("\n== Cámara BMM ==\n")
        val loader = BmmCameraLoader(ctx)
        val jar = loader.available()
        sb.appendLine("  jar: ${jar ?: "no encontrado"}")
        if (jar != null && loader.load()) {
            for (cn in listOf("android.hardware.NormalCamera", "android.hardware.AVMCamera", "android.hardware.BmmCameraInfo", "android.hardware.JNIBMMCamera")) {
                sb.appendLine("  -- $cn --")
                try {
                    val c = loader.clazz(cn) ?: run { sb.appendLine("    (no carga)"); null } ?: continue
                    c.methods.filter { it.declaringClass == c }.sortedBy { it.name }.forEach { m ->
                        val st = if (Modifier.isStatic(m.modifiers)) "static " else ""
                        sb.appendLine("    $st${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString(","){it.simpleName}})")
                    }
                    for (mm in listOf("getCameraNumbers", "getCameraNumber", "getNumberOfCameras")) {
                        try { sb.appendLine("    $mm() = ${c.getMethod(mm).invoke(null)}") } catch (e: Throwable) {}
                    }
                } catch (e: Throwable) { sb.appendLine("    EX: ${e.message}") }
            }
        }
        return sb.toString()
    }

    private fun fmt(v: Any?): String = when (v) {
        is IntArray -> v.joinToString(",", "[", "]")
        else -> v.toString()
    }

    /**
     * Envía el sondeo UNA vez por versionCode a cars/{carId}/probe (texto, máx ~900 KB) +
     * cars/{carId}/probeAt, en segundo plano. Si ya se envió para esta versión, no hace nada.
     * Lo llama el servicio una vez al arrancar.
     */
    fun autoSendIfNeeded(ctx: Context, carId: String) {
        val sp = ctx.getSharedPreferences("bydlink", Context.MODE_PRIVATE)
        val vc = BuildConfigCompat.versionCode(ctx)
        if (sp.getLong("probeSentVc", -1L) == vc) return
        Thread {
            try {
                var text = run(ctx)
                if (text.length > 900_000) text = text.substring(0, 900_000) + "\n…(truncado a 900 KB)"
                val root = App.db.getReference("cars").child(carId)
                root.child("probe").setValue(text)
                root.child("probeAt").setValue(ServerValue.TIMESTAMP)
                sp.edit().putLong("probeSentVc", vc).apply()
            } catch (e: Throwable) {
                android.util.Log.e("BYDLink", "autoSendIfNeeded", e)
            }
        }.apply { isDaemon = true }.start()
    }
}
