package com.bydlink.carapp.camera

import android.content.Context
import android.view.Surface
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Envoltorio por reflexión de `android.hardware.AVMCamera` / `NormalCamera` (bmmcamera.jar), con las
 * firmas REALES del Surf (sondeo) y del jar desensamblado:
 *  - AVM: `enablePreviewCallback(int)`, `onPreview(AVMCamera, byte[], w, h, fmt, size, viewIndex, long ts)`.
 *  - Normal: `enablePreviewCallback()`, `onPreview(NormalCamera, byte[], w, h, fmt, size, long ts)`.
 * El byte[] del callback es SIEMPRE el mismo buffer directo y el jar lo limpia al volver: quien lo use
 * tiene que copiar/convertir dentro del callback. Los callbacks llegan desde un hilo nativo.
 */
class BmmCamera private constructor(
    val api: String, val id: Int, private val cls: Class<*>, private val obj: Any, val viaPrivate: Boolean
) {
    interface Listener {
        fun onPreview(data: ByteArray?, w: Int, h: Int, fmt: Int, size: Int, view: Int, ts: Long)
        fun onEvent(type: Int, a1: Int, a2: Int)
    }

    val isAvm: Boolean get() = api == "avm"
    private val iT: Class<*> = Int::class.javaPrimitiveType!!
    private val surfT: Class<*> = Surface::class.java
    private val pvI: Class<*> by lazy { Class.forName("${cls.name}\$IPreviewCallback", false, cls.classLoader) }
    private val evI: Class<*> by lazy { Class.forName("${cls.name}\$IEventCallback", false, cls.classLoader) }

    /** Llama a un método; las excepciones del jar salen como su causa real. */
    fun call(name: String, types: Array<Class<*>>, vararg args: Any?): Any? = try {
        cls.getMethod(name, *types).invoke(obj, *args)
    } catch (e: InvocationTargetException) { throw e.targetException ?: e }

    /** Como [call] pero nunca lanza: devuelve el resultado en texto ("true", "false", "EX: …"). Para el test. */
    fun tryCall(name: String, types: Array<Class<*>>, vararg args: Any?): String = try {
        "${call(name, types, *args)}"
    } catch (e: Throwable) { "EX: " + BmmRuntime.rootCause(e) }

    fun setCallbacks(l: Listener) {
        val ev = Proxy.newProxyInstance(evI.classLoader, arrayOf(evI), Cb { name, a ->
            if (name == "onEvent" && a.size >= 4) l.onEvent(a[1] as Int, a[2] as Int, a[3] as Int)
        })
        val pv = Proxy.newProxyInstance(pvI.classLoader, arrayOf(pvI), Cb { name, a ->
            if (name == "onPreview" && a.size >= 7) {
                val data = a[1] as? ByteArray
                if (a.size >= 8) l.onPreview(data, a[2] as Int, a[3] as Int, a[4] as Int, a[5] as Int, a[6] as Int, (a[7] as Number).toLong())
                else l.onPreview(data, a[2] as Int, a[3] as Int, a[4] as Int, a[5] as Int, 0, (a[6] as Number).toLong())
            }
        })
        call("setEventCallback", arrayOf(evI), ev)
        call("setPreviewCallback", arrayOf(pvI), pv)
    }

    fun clearCallbacks() {
        runCatching { call("setPreviewCallback", arrayOf(pvI), *arrayOf<Any?>(null)) }
        runCatching { call("setEventCallback", arrayOf(evI), *arrayOf<Any?>(null)) }
    }

    fun setPreviewSize(w: Int, h: Int): Boolean? = call("setPreviewSize", arrayOf(iT, iT), w, h) as? Boolean
    fun previewW(): Int? = runCatching { call("getPreviewWidth", arrayOf()) as? Int }.getOrNull()
    fun previewH(): Int? = runCatching { call("getPreviewHeight", arrayOf()) as? Int }.getOrNull()
    fun setFps(fps: Int): Boolean? = call("setCameraFps", arrayOf(iT), fps) as? Boolean
    fun isPreview(): Boolean? = runCatching { call("isPreview", arrayOf()) as? Boolean }.getOrNull()

    fun enable(view: Int): Boolean? =
        (if (isAvm) call("enablePreviewCallback", arrayOf(iT), view) else call("enablePreviewCallback", arrayOf())) as? Boolean
    fun disable(view: Int): Boolean? =
        (if (isAvm) call("disablePreviewCallback", arrayOf(iT), view) else call("disablePreviewCallback", arrayOf())) as? Boolean

    fun addSurface(s: Surface, view: Int): Boolean? =
        (if (isAvm) call("addPreviewSurface", arrayOf(surfT, iT), s, view) else call("addPreviewSurface", arrayOf(surfT), s)) as? Boolean
    fun setSurface(s: Surface, view: Int): Boolean? =
        (if (isAvm) call("setPreviewSurface", arrayOf(surfT, iT), s, view) else call("setPreviewSurface", arrayOf(surfT), s)) as? Boolean
    fun rmSurface(s: Surface, view: Int): Boolean? =
        (if (isAvm) call("rmPreviewSurface", arrayOf(surfT, iT), s, view) else call("rmPreviewSurface", arrayOf(surfT), s)) as? Boolean

    fun start(): Boolean? = call("startPreview", arrayOf()) as? Boolean
    fun stop(): Boolean? = call("stopPreview", arrayOf()) as? Boolean
    fun close() { call("close", arrayOf()) }

    /**
     * Cierre en el orden de BYDCameraRecorder; cada paso por separado (si uno falla, se sigue).
     * Devuelve los fallos (vacío si todo fue bien).
     */
    fun closeFully(view: Int, surface: Surface?): List<String> {
        val errs = ArrayList<String>()
        fun step(n: String, f: () -> Unit) = try { f() } catch (e: Throwable) { errs += "$n: ${BmmRuntime.rootCause(e)}" }
        surface?.let { s -> step("rmPreviewSurface") { rmSurface(s, view) } }
        step("disablePreviewCallback") { disable(view) }
        step("callbacks=null") { clearCallbacks() }
        step("stopPreview") { stop() }
        step("close") { close() }
        return errs
    }

    /** InvocationHandler que nunca deja escapar una excepción hacia el código nativo (lo tumbaría). */
    private class Cb(val onCall: (String, Array<out Any?>) -> Unit) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            when (method.name) {
                "hashCode" -> return System.identityHashCode(proxy)
                "equals" -> return proxy === args?.getOrNull(0)
                "toString" -> return "BydLinkCb@" + Integer.toHexString(System.identityHashCode(proxy))
            }
            try { onCall(method.name, args ?: emptyArray()) }
            catch (t: Throwable) { CamLog.limited("cb", 10_000, "excepción dentro del callback de cámara: $t") }
            return null
        }
    }

    companion object {
        fun className(api: String) = if (api == "normal") "android.hardware.NormalCamera" else "android.hardware.AVMCamera"

        /**
         * `XCamera.open(id)`. El jar devuelve null (sin excepción) si el id no es válido o el servicio no lo da.
         * Con [allowPrivate] prueba además el constructor privado + `open()` privado (lo que hacen SentryEV/BYDMate).
         */
        fun open(ctx: Context, api: String, id: Int, allowPrivate: Boolean = false): BmmCamera {
            val cls = BmmRuntime.cls(ctx, className(api))
            val iT = Int::class.javaPrimitiveType!!
            var o: Any? = try { cls.getMethod("open", iT).invoke(null, id) } catch (e: InvocationTargetException) { throw e.targetException ?: e }
            var priv = false
            if (o == null && allowPrivate) {
                val c = cls.getDeclaredConstructor(iT).apply { isAccessible = true }.newInstance(id)
                val ok = cls.getDeclaredMethod("open").apply { isAccessible = true }.invoke(c) == true
                if (ok) { o = c; priv = true }
            }
            if (o == null) throw CamError("open_refused",
                "El coche no deja abrir la cámara (id $id, $api). Puede que otra app la esté usando o que el id no sea válido.")
            return BmmCamera(api, id, cls, o, priv)
        }
    }
}

/** Descubrimiento con `BmmCameraInfo` (no usa JNI): propiedad `vehicle.config.cam_sort`, tags e ids. */
object BmmInfo {
    val TAGS = listOf("pano_h", "pano_l", "byd_apa", "apa", "rvs", "rear", "front", "rf", "dms", "face", "cargo",
        "d954_h_m", "d954_h_s", "d954_l_m", "d954_l_s")
    private val PANO = listOf("pano_h", "pano_l", "byd_apa", "apa")
    private val SINGLE = listOf("rvs", "rear")

    /** kind: avm4 (360, 4 cámaras por canales) | single (solo trasera) | unknown. */
    data class Target(val id: Int, val tag: String, val kind: String) {
        fun toMap() = mapOf("id" to id, "tag" to tag, "kind" to kind)
    }

    private fun info(ctx: Context) = BmmRuntime.cls(ctx, "android.hardware.BmmCameraInfo")

    fun numbers(ctx: Context): Int? = runCatching { info(ctx).getMethod("getCameraNumbers").invoke(null) as? Int }.getOrNull()
    fun validTags(ctx: Context): List<String> =
        runCatching { (info(ctx).getMethod("getValidCameraTag").invoke(null) as? List<*>)?.map { "$it" } }.getOrNull().orEmpty()
    fun idOf(ctx: Context, tag: String): Int =
        runCatching { info(ctx).getMethod("getCameraId", String::class.java).invoke(null, tag) as? Int }.getOrNull() ?: -1
    fun tagOf(ctx: Context, i: Int): String? =
        runCatching { info(ctx).getMethod("getCameraTag", Int::class.javaPrimitiveType).invoke(null, i) as? String }.getOrNull()
    fun isValid(ctx: Context, i: Int): Boolean? =
        runCatching { info(ctx).getMethod("isValidCamera", Int::class.javaPrimitiveType).invoke(null, i) as? Boolean }.getOrNull()

    fun sysProp(key: String): String? = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as? String
    } catch (e: Throwable) { null }

    fun kindOfTag(tag: String) = when (tag) { in PANO -> "avm4"; in SINGLE -> "single"; else -> "unknown" }

    /** Tag e id a usar: primero panorámica (360), luego cámara única, luego el primer tag válido, si no id 0. */
    fun resolve(ctx: Context, p: CamProfile): Target {
        if (p.cameraId >= 0) return Target(p.cameraId, "manual", if (p.cams.size > 1) "avm4" else "single")
        PANO.forEach { t -> val id = idOf(ctx, t); if (id >= 0) return Target(id, t, "avm4") }
        SINGLE.forEach { t -> val id = idOf(ctx, t); if (id >= 0) return Target(id, t, "single") }
        validTags(ctx).firstOrNull()?.let { t -> val id = idOf(ctx, t); if (id >= 0) return Target(id, t, kindOfTag(t)) }
        return Target(0, "raw", "unknown")
    }
}
