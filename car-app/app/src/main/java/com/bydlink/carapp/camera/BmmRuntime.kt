package com.bydlink.carapp.camera

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Error de cámara con un código estable (para el móvil) y un mensaje en español (para la persona).
 * Códigos: no_bmm, jni, no_camera, open_refused, busy, testing, test_failed, no_frames, not_parked, disabled,
 * bad_camera, bad_args, webrtc, upload, timeout, error.
 */
class CamError(val code: String, msg: String, cause: Throwable? = null) : Exception(msg, cause) {
    companion object {
        fun noBmm() = CamError("no_bmm", "Este coche no tiene el sistema de cámaras BYD (bmmcamera.jar).")
        fun notParked() = CamError("not_parked", "Por seguridad, la cámara solo se usa con el coche parado (marcha P y 0 km/h).")
        fun testing() = CamError("testing", "La cámara se está comprobando, prueba en un minuto.")
    }
}

/**
 * Carga `bmmcamera.jar` UNA SOLA VEZ por proceso (fallo S3 del plan).
 *
 * ART ata `libbmmcamera_jni.so` al primer classloader que la carga: un segundo `DexClassLoader`
 * del mismo jar ya no puede usarla y la cámara deja de abrir. Por eso todo el código (hub, test,
 * Prober, pantalla de diagnóstico vía [com.bydlink.carapp.car.BmmCameraLoader]) pasa por aquí.
 *
 * Librería nativa: primero SIN ruta de libs (como BYDMate/BYDCameraRecorder: si BYD la marcó como pública,
 * se carga en el espacio del sistema sin duplicar libbinder & cía). Solo si eso falla al enlazar se crea un
 * segundo loader CON la ruta de libs del sistema (como SealNav). Es seguro porque en el primero la
 * librería no llegó a cargarse. [mode] dice cuál funcionó: boot | dex | dex+libs.
 */
object BmmRuntime {
    val JARS = listOf(
        "/system/framework/bmmcamera.jar",
        "/system_ext/framework/bmmcamera.jar",
        "/product/framework/bmmcamera.jar",
        "/vendor/framework/bmmcamera.jar",
        "/odm/framework/bmmcamera.jar",
        "/system/framework/avmcamera.jar",
        "/system/framework/bydcamera.jar"
    )

    @Volatile private var cl: ClassLoader? = null
    /** Ruta del jar cargado (o null si aún no / no hay). */
    @Volatile var jarPath: String? = null; private set
    /** "boot" (clases ya en el classpath del sistema), "dex" (jar sin ruta de libs) o "dex+libs". */
    @Volatile var mode: String? = null; private set
    @Volatile private var jniResult: String? = null

    /** Primer jar de cámara que existe en este coche (o null: no hay cámaras BMM, p. ej. el emulador). */
    fun findJar(): String? = JARS.firstOrNull { File(it).canRead() } ?: JARS.firstOrNull { File(it).exists() }

    fun libPath(): String =
        if (android.os.Process.is64Bit()) "/system/lib64:/vendor/lib64:/odm/lib64:/system_ext/lib64:/product/lib64"
        else "/system/lib:/vendor/lib:/odm/lib:/system_ext/lib:/product/lib"

    /** El classloader único. Lanza [CamError] `no_bmm` si no hay jar. */
    @Synchronized fun loader(ctx: Context): ClassLoader {
        cl?.let { return it }
        val app = ctx.applicationContext.classLoader
        // Si el firmware ya expone las clases en el classpath del sistema, no hace falta otro loader.
        val boot = try { Class.forName("android.hardware.BmmCameraInfo", false, app); true } catch (e: Throwable) { false }
        if (boot) {
            cl = app; mode = "boot"; jarPath = findJar() ?: "(classpath)"
            CamLog.i("bmm: clases ya en el classpath del sistema")
            return app
        }
        val jar = findJar() ?: throw CamError.noBmm()
        val loader = DexClassLoader(jar, ctx.applicationContext.codeCacheDir.absolutePath, null, app)
        cl = loader; mode = "dex"; jarPath = jar
        CamLog.i("bmm: jar cargado una vez: $jar")
        return loader
    }

    fun loaderOrNull(ctx: Context): ClassLoader? = try { loader(ctx) } catch (e: Throwable) { null }

    fun cls(ctx: Context, name: String): Class<*> = Class.forName(name, false, loader(ctx))

    fun clsOrNull(ctx: Context, name: String): Class<*>? = try { cls(ctx, name) } catch (e: Throwable) { null }

    /**
     * Fuerza la carga de libbmmcamera_jni (inicializa JNILoader). Devuelve "ok" o la causa raíz literal.
     * El resultado se recuerda: una lib nativa no se puede volver a intentar en el mismo proceso.
     */
    @Synchronized fun loadJni(ctx: Context): String {
        jniResult?.let { return it }
        var r = try {
            Class.forName("android.hardware.JNILoader", true, loader(ctx)); "ok"
        } catch (e: CamError) {
            return e.message ?: e.code   // sin jar: no se memoriza (no se ha tocado nada nativo)
        } catch (e: Throwable) {
            rootCause(e)
        }
        CamLog.i("bmm: carga de libbmmcamera_jni ($mode) -> $r")
        if (r != "ok" && mode == "dex" && !r.contains("already opened")) {
            // Segundo intento con la ruta de libs del sistema y un loader nuevo (la lib no llegó a cargarse).
            val jar = jarPath ?: findJar()
            if (jar != null) {
                val l2 = DexClassLoader(jar, ctx.applicationContext.codeCacheDir.absolutePath, libPath(), ctx.applicationContext.classLoader)
                val r2 = try { Class.forName("android.hardware.JNILoader", true, l2); "ok" } catch (e: Throwable) { rootCause(e) }
                CamLog.i("bmm: carga de libbmmcamera_jni (dex+libs) -> $r2")
                if (r2 == "ok") { cl = l2; mode = "dex+libs"; r = "ok" }
                else r = "sin ruta: $r | con ruta: $r2"
            }
        }
        jniResult = r
        return r
    }

    fun rootCause(e: Throwable): String {
        var t: Throwable = e
        while (t.cause != null && t.cause !== t) t = t.cause!!
        return "${t.javaClass.simpleName}: ${t.message}"
    }
}
