package com.bydlink.carapp.keys

import android.content.Context
import android.hardware.bydauto.BYDAutoEventValue
import android.hardware.bydauto.audio.AbsBYDAutoAudioListener
import android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener
import com.bydlink.carapp.car.BydPermissionContext
import com.bydlink.carapp.car.BydReflect

/**
 * Oyentes del SDK BYD para los botones: el framework del coche avisa por `onDataEventChanged(featureId, valor)`.
 * Se heredan las clases del framework (en el APK solo van los stubs de compilación): en un Android sin SDK BYD, cargar
 * esta clase falla y [WheelKeys] lo trata como "no hay SDK". Todo lo demás va por reflexión, por si cambian las firmas.
 */
internal object SdkKeyListeners {
    private val keep = ArrayList<Any>()   // el SDK guarda referencias débiles en algunos modelos

    /** Devuelve "ok" si al menos un oyente quedó puesto, o el motivo. */
    fun register(ctx: Context, cb: (src: String, fid: Int, value: Int) -> Unit): String {
        val pctx = BydPermissionContext(ctx.applicationContext)
        val out = ArrayList<String>()
        val inst = BydReflect.device(pctx, "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
        if (inst != null) {
            val l = object : AbsBYDAutoInstrumentListener() {
                override fun onDataEventChanged(eventType: Int, value: BYDAutoEventValue?) {
                    if (eventType == WheelKeys.FID_INPUT_KEYCODE) cb("sdk", eventType, value?.intValue ?: 0)
                }
            }
            out += if (reg(inst, AbsBYDAutoInstrumentListener::class.java, l, intArrayOf(WheelKeys.FID_INPUT_KEYCODE))) "cuadro" else "cuadro×"
        }
        val audio = BydReflect.device(pctx, "android.hardware.bydauto.audio.BYDAutoAudioDevice")
        if (audio != null) {
            val l = object : AbsBYDAutoAudioListener() {
                override fun onDataEventChanged(eventType: Int, value: BYDAutoEventValue?) {
                    if (eventType == WheelKeys.FID_VOICE_KEY || eventType == WheelKeys.FID_CUSTOM_PLAY)
                        cb(if (eventType == WheelKeys.FID_VOICE_KEY) "sdk-voz" else "sdk", eventType, value?.intValue ?: 0)
                }
            }
            out += if (reg(audio, AbsBYDAutoAudioListener::class.java, l, intArrayOf(WheelKeys.FID_VOICE_KEY, WheelKeys.FID_CUSTOM_PLAY))) "audio" else "audio×"
        }
        return when {
            out.isEmpty() -> "sin SDK"
            out.any { !it.endsWith("×") } -> "ok"
            else -> "no deja (${out.joinToString()})"
        }
    }

    private fun reg(dev: Any, type: Class<*>, l: Any, fids: IntArray): Boolean = try {
        dev.javaClass.getMethod("registerListener", type, IntArray::class.java).invoke(dev, l, fids)
        keep += l; true
    } catch (e: Throwable) {
        try { dev.javaClass.getMethod("registerListener", type).invoke(dev, l); keep += l; true } catch (e2: Throwable) { false }
    }
}
