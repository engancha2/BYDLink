package com.bydlink.carapp.camera

import android.content.Context
import com.bydlink.carapp.car.BmmCameraLoader
import java.lang.reflect.Proxy

/**
 * Abre una cámara del coche (framework BMM, por reflexión) y entrega los frames de preview
 * (YUV / NV21) a un listener — para el centinela y para el streaming en vivo.
 *
 * El callback de preview (IPreviewCallback) se implementa con un Proxy dinámico: su firma
 * exacta varía entre modelos, así que capturamos cualquier invocación y tomamos el byte[]
 * (el frame). Robusto y model-agnostic.
 */
class CameraController(ctx: Context) {

    private val loader = BmmCameraLoader(ctx)
    private var camera: Any? = null
    private var camClass: Class<*>? = null

    var onFrame: ((frame: ByteArray, width: Int, height: Int) -> Unit)? = null
    var width = 1280
    var height = 960
    var currentCamera = "front"; private set

    fun available(): Boolean = loader.available() != null
    fun jarPath(): String? = loader.available()

    /** Carga el jar, abre la cámara [tag] y arranca el preview con callback. */
    fun start(tag: String = "front"): Boolean {
        if (!loader.load()) return false
        currentCamera = tag
        val cls = loader.clazz("android.hardware.AVMCamera")
            ?: loader.clazz("android.hardware.NormalCamera") ?: return false
        camClass = cls
        return try {
            val id = cameraId(tag)
            camera = cls.getMethod("open", Int::class.javaPrimitiveType).invoke(null, id)
            runCatching {
                cls.getMethod("setPreviewSize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(camera, width, height)
            }
            runCatching { cls.getMethod("setCameraFps", Int::class.javaPrimitiveType).invoke(camera, 15) }
            installPreviewCallback(cls)
            cls.getMethod("startPreview").invoke(camera)
            true
        } catch (e: Throwable) { e.printStackTrace(); false }
    }

    fun switchTo(tag: String) { stop(); start(tag) }

    fun stop() {
        val c = camera ?: return
        runCatching { c.javaClass.getMethod("stopPreview").invoke(c) }
        runCatching { c.javaClass.getMethod("close").invoke(c) }
        camera = null
    }

    // Orden/ids de cámara a confirmar por coche (front/right/rear/left en SA8155P = 0/1/2/3).
    private fun cameraId(tag: String) = when (tag) {
        "front" -> 0; "right" -> 1; "back" -> 2; "left" -> 3; else -> 0
    }

    private fun installPreviewCallback(cls: Class<*>) {
        val cbIface = cls.declaredClasses.firstOrNull { it.simpleName.contains("PreviewCallback") }
            ?: runCatching { loader.clazz("android.hardware.AVMCamera\$IPreviewCallback") }.getOrNull()
            ?: return
        val proxy = Proxy.newProxyInstance(cbIface.classLoader, arrayOf(cbIface)) { _, _, args ->
            val frame = args?.firstOrNull { it is ByteArray } as? ByteArray
            if (frame != null) try { onFrame?.invoke(frame, width, height) } catch (e: Throwable) {}
            null
        }
        runCatching { cls.getMethod("setPreviewCallback", cbIface).invoke(camera, proxy) }
        runCatching { cls.getMethod("enablePreviewCallback").invoke(camera) }
    }
}
