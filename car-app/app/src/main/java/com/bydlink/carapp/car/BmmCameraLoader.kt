package com.bydlink.carapp.car

import android.content.Context
import com.bydlink.carapp.camera.BmmInfo
import com.bydlink.carapp.camera.BmmRuntime

/**
 * Acceso de solo lectura al framework de cámara BMM de BYD (`bmmcamera.jar`) para la pantalla de
 * diagnóstico y el Prober. Ya NO crea su propio DexClassLoader: usa el loader ÚNICO del proceso
 * ([BmmRuntime]); un segundo loader del mismo jar dejaría la librería nativa inservible (fallo S3).
 *
 * La cámara de verdad (abrir, frames, centinela, directo) la lleva [com.bydlink.carapp.camera.CameraHub].
 */
class BmmCameraLoader(private val ctx: Context) {

    companion object {
        /** Rutas donde puede estar el jar (las usa también CarShell.findBmmJar). */
        val JAR_PATHS: List<String> get() = BmmRuntime.JARS
    }

    /** Primera ruta de jar que existe en este coche, o null si no hay cámaras BMM. */
    fun available(): String? = BmmRuntime.findJar()

    /** Prepara el loader único. Devuelve true si hay jar y se pudo cargar. */
    fun load(): Boolean = BmmRuntime.loaderOrNull(ctx) != null

    fun loadedFrom(): String? = BmmRuntime.jarPath

    /** Carga una clase del jar BMM por nombre (sin inicializarla: no toca código nativo). */
    fun clazz(name: String): Class<*>? = BmmRuntime.clsOrNull(ctx, name)

    /** Nº de dispositivos BMM (tags en vehicle.config.cam_sort). En el Surf vive en BmmCameraInfo. */
    fun cameraNumbers(): Int {
        BmmInfo.numbers(ctx)?.let { return it }
        for (n in listOf("android.hardware.AVMCamera", "android.hardware.NormalCamera")) {
            val c = clazz(n) ?: continue
            try { return (c.getMethod("getCameraNumbers").invoke(null) as? Int) ?: 0 } catch (e: Throwable) {}
        }
        return 0
    }

    /** Tags válidos (p. ej. pano_h, rear) — solo lectura, no abre la cámara. */
    fun validTags(): List<String> = try { BmmInfo.validTags(ctx) } catch (e: Throwable) { emptyList() }
}
