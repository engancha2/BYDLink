package com.bydlink.carapp.shell

import android.content.Context
import android.os.Looper
import com.bydlink.carapp.car.BydPermissionContext
import com.bydlink.carapp.car.BydReflect

/**
 * Ayudante que se ejecuta como el usuario `shell` (el del ADB del coche), no como la app:
 *   CLASSPATH=<apk de BYDLink> app_process / com.bydlink.carapp.shell.ShellMain <orden> [args]
 * DiLink deja a ese usuario hacer cosas que a las apps no (en el Surf: cerrar el coche), igual que hacen otros
 * lanzadores. Es código nuestro: llama al mismo SDK de BYD que la app, solo que desde otro usuario.
 * Órdenes (de momento, para probar): get <dispositivo> <método> [int], lock <1|2>, flash.
 */
object ShellMain {
    private const val FID_LOCK = 1276141590            // Bodywork.BODYWORK_FOUR_DOOR_SET: 1 = abrir, 2 = cerrar
    private const val FID_TURN_SET = 871366669         // Light.LIGHT_TURN_SIGNAL_LIGHT_SET
    private const val FID_DOUBLE_FLASH = 960495667     // Light.LIGHT_CMD_DOUBLE_FLASH_STATE

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val ctx = BydPermissionContext(systemContext())
            fun dev(s: String) = BydReflect.device(ctx, "android.hardware.bydauto.$s")
            when (args.getOrNull(0)) {
                "get" -> {
                    val d = dev(args[1]) ?: return out("ERROR dispositivo ${args[1]}")
                    val r = if (args.size > 3) BydReflect.call(d, args[2], args[3].toInt()) else BydReflect.call(d, args[2])
                    out("OK ${args[2]} = $r")
                }
                // setint <tipoDispositivo> <featureId> <valor> [--confirmo]: la vía directa de BYD
                // (BYDAutoDeviceManager.setInt), que no pasa por la lista de funciones permitidas de cada dispositivo.
                // Seguridad: el dispositivo de rescate (0x410) está vetado (su "alarma" puede llamar a emergencias) y lo de
                // alimentación/encendido (Power 0x3ed, OTA_CMD_ECL_POWER_ON 0x49a00408) exige --confirmo.
                "setint" -> {
                    val type = java.lang.Long.decode(args[1]).toInt()
                    val fid = java.lang.Long.decode(args[2]).toInt()
                    val v = args[3].toInt()
                    if (type == 0x410) return out("ERROR vetado: dispositivo de rescate (podría avisar a emergencias)")
                    if ((type == 0x3ed || fid == 0x49a00408) && "--confirmo" !in args) return out("ERROR alimentación/encendido: añade --confirmo")
                    val mgr = Class.forName("android.hardware.bydauto.BYDAutoDeviceManager")
                    val inst = mgr.getMethod("getInstance", Context::class.java).invoke(null, ctx)
                    val ota = dev("ota.BYDAutoOtaDevice"); val light = dev("light.BYDAutoLightDevice")
                    val before = BydReflect.call(ota, "getLFDoorLockState")
                    val r = mgr.getMethod("setInt", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(inst, type, fid, v)
                    Thread.sleep(2500)
                    out("OK setint 0x${Integer.toHexString(type)} 0x${Integer.toHexString(fid)}=$v -> $r | cierre antes=$before " +
                        "despues=${BydReflect.call(ota, "getLFDoorLockState")} emergencias=${BydReflect.call(light, "getDoubleFlashLightState")}")
                }
                "getint" -> {
                    val mgr = Class.forName("android.hardware.bydauto.BYDAutoDeviceManager")
                    val inst = mgr.getMethod("getInstance", Context::class.java).invoke(null, ctx)
                    val r = mgr.getMethod("getInt", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(inst, java.lang.Long.decode(args[1]).toInt(), java.lang.Long.decode(args[2]).toInt())
                    out("OK getint ${args[1]} ${args[2]} = $r")
                }
                // feat <dispositivo> <featureId> <valor>: escribir una función del coche por su número y leer el cierre.
                "feat" -> {
                    val d = dev(args[1]) ?: return out("ERROR dispositivo ${args[1]}")
                    val fid = java.lang.Long.decode(args[2]).toInt()
                    val ota = dev("ota.BYDAutoOtaDevice")
                    val before = BydReflect.call(ota, "getLFDoorLockState")
                    val r = BydReflect.setFeature(d, fid, args[3].toInt())
                    Thread.sleep(2500)
                    out("OK feat ${args[2]}=${args[3]} set=$r cierre antes=$before despues=${BydReflect.call(ota, "getLFDoorLockState")}")
                }
                "lock" -> {
                    val v = args.getOrNull(1)?.toIntOrNull() ?: 2
                    val body = dev("bodywork.BYDAutoBodyworkDevice")
                    val ota = dev("ota.BYDAutoOtaDevice")
                    val before = BydReflect.call(ota, "getLFDoorLockState")
                    val r = BydReflect.setFeature(body, FID_LOCK, v)
                    Thread.sleep(2500)
                    out("OK lock($v) set=$r antes=$before despues=${BydReflect.call(ota, "getLFDoorLockState")}")
                }
                "flash" -> {
                    val light = dev("light.BYDAutoLightDevice")
                    val r1 = BydReflect.setFeature(light, FID_TURN_SET, 1)
                    val r2 = BydReflect.setFeature(light, FID_DOUBLE_FLASH, 1)
                    Thread.sleep(1500)
                    val st = BydReflect.call(light, "getDoubleFlashLightState")
                    Thread.sleep(3000)
                    BydReflect.setFeature(light, FID_TURN_SET, 0); BydReflect.setFeature(light, FID_DOUBLE_FLASH, 0)
                    out("OK flash turn=$r1 double=$r2 estado=$st")
                }
                else -> out("ERROR orden desconocida: ${args.joinToString(" ")}")
            }
        } catch (e: Throwable) {
            out("ERROR ${e.javaClass.simpleName}: ${e.message}")
        }
        System.exit(0)
    }

    private fun out(s: String) { println(s); System.out.flush() }

    /** Un Context del sistema sin ser una app (como hacen las herramientas que corren con app_process). */
    private fun systemContext(): Context {
        if (Looper.getMainLooper() == null) @Suppress("DEPRECATION") Looper.prepareMainLooper()
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }
}
