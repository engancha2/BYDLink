package com.bydlink.carapp.car

import android.content.Context
import com.bydlink.carapp.BuildConfigCompat
import java.lang.reflect.Modifier

/**
 * Sondeo de solo-lectura del coche: lista por reflexión los métodos REALES del SDK BYD y del
 * framework de cámara, y prueba los getters sin argumentos (con sus valores/errores). Sirve
 * para afinar el código a este modelo exacto SIN ejecutar comandos arbitrarios (no es un shell).
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
        "setting" to "android.hardware.bydauto.setting.BYDAutoSettingDevice"
    )

    fun run(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("== BYDLink PROBE == ${java.util.Date()}")
        sb.appendLine("carId=${BuildConfigCompat.carId(ctx)}")

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
                val inst = try { c.getMethod("getInstance", Context::class.java).invoke(null, ctx) }
                           catch (e: Throwable) { try { c.getMethod("getInstance").invoke(null) } catch (e2: Throwable) { null } }
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
                            sb.appendLine("    ${m.name}() = $v")
                        }
                }
            } catch (e: Throwable) { sb.appendLine("  NO disponible: ${e.message}") }
        }

        sb.appendLine("\n== Cámara BMM ==")
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
}
