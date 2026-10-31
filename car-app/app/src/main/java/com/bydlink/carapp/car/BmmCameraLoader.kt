package com.bydlink.carapp.car

import android.content.Context
import android.view.Surface
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Carga el framework de cámara BMM de BYD por REFLEXIÓN y abre las cámaras del coche,
 * exactamente como hace Electro (su `BmmCameraLoader`) y lo confirman SentryEV/SealNav.
 *
 * Por qué así (y no la API Panorama): `android.hardware.NormalCamera` / `AVMCamera` viven
 * en `bmmcamera.jar`, que NO está en el classpath de la app. Se cargan con un DexClassLoader
 * apuntando al jar del sistema. Es MODEL-AGNOSTIC porque ese jar existe en casi todas las
 * cabeceras BYD; sólo cambia la partición, por eso probamos varias rutas (como SealNav).
 *
 * Pipeline (ver INGENIERIA_ELECTRO.md): preview YUV -> MediaCodec H.264 -> WebRTC/mp4.
 * Las firmas exactas de los métodos se confirman en el coche con byd-probe.
 */
class BmmCameraLoader(private val ctx: Context) {

    companion object {
        val JAR_PATHS = listOf(
            "/system/framework/bmmcamera.jar",
            "/system_ext/framework/bmmcamera.jar",
            "/product/framework/bmmcamera.jar",
            "/odm/framework/bmmcamera.jar",
            "/vendor/framework/bmmcamera.jar",
            "/system/framework/avmcamera.jar",
            "/system/framework/bydcamera.jar"
        )
    }

    private var loader: ClassLoader? = null
    private var jarPath: String? = null

    /** Primera ruta de jar que existe en este coche, o null si no hay cámaras BMM. */
    fun available(): String? = JAR_PATHS.firstOrNull { File(it).exists() }

    /** Carga el jar de cámara. Devuelve true si lo encontró y cargó. */
    fun load(): Boolean {
        val jar = available() ?: return false
        jarPath = jar
        val opt = File(ctx.cacheDir, "bmm_opt").apply { mkdirs() }
        return try {
            loader = DexClassLoader(jar, opt.absolutePath, null, ctx.classLoader)
            true
        } catch (e: Throwable) { e.printStackTrace(); false }
    }

    fun loadedFrom(): String? = jarPath

    /** Carga una clase del jar BMM por nombre (para la cámara, por reflexión). */
    fun clazz(name: String): Class<*>? = cls(name)

    /** Nº de cámaras del coche. NormalCamera.getCameraNumbers() (estático). */
    fun cameraNumbers(): Int {
        val cls = cls("android.hardware.NormalCamera") ?: return 0
        return try { (cls.getMethod("getCameraNumbers").invoke(null) as? Int) ?: 0 }
        catch (e: Throwable) { e.printStackTrace(); 0 }
    }

    /**
     * Abre la cámara AVM (vista 360 / 4 cámaras combinadas) y manda el preview a [surface].
     * Devuelve el objeto cámara (para luego parar con [stop]) o null si falla.
     * Firmas por reflexión — AJUSTAR con byd-probe en el coche real.
     */
    fun openAvm(cameraId: Int, surface: Surface, width: Int = 1280, height: Int = 960): Any? {
        val cls = cls("android.hardware.AVMCamera") ?: return null
        return try {
            val cam = cls.getMethod("open", Int::class.javaPrimitiveType).invoke(null, cameraId)
            runCatching { cls.getMethod("setPreviewSize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(cam, width, height) }
            runCatching { cls.getMethod("setCameraFps", Int::class.javaPrimitiveType).invoke(cam, 30) }
            cls.getMethod("setPreviewSurface", Surface::class.java).invoke(cam, surface)
            cls.getMethod("startPreview").invoke(cam)
            cam
        } catch (e: Throwable) { e.printStackTrace(); null }
    }

    fun stop(cam: Any?) {
        if (cam == null) return
        runCatching { cam.javaClass.getMethod("stopPreview").invoke(cam) }
        runCatching { cam.javaClass.getMethod("close").invoke(cam) }
    }

    private fun cls(name: String) = try { loader?.loadClass(name) } catch (e: Throwable) { null }
}
