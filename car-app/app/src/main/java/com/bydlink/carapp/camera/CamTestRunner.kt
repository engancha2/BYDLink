package com.bydlink.carapp.camera

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.car.CarShell
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** JSON (org.json) → Map/List para Firebase, saneando las claves (Firebase no admite . # $ [ ] /). */
object Json {
    private val BAD = Regex("[.#$\\[\\]/]")
    fun toMap(o: JSONObject): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        o.keys().forEach { k -> m[BAD.replace(k, "_").ifEmpty { "_" }] = conv(o.opt(k)) }
        return m
    }
    fun conv(v: Any?): Any? = when (v) {
        is JSONObject -> toMap(v)
        is JSONArray -> (0 until v.length()).map { conv(v.opt(it)) }
        JSONObject.NULL -> null
        is Double -> if (v.isNaN() || v.isInfinite()) null else v
        is Float -> if (v.isNaN() || v.isInfinite()) null else v.toDouble()
        else -> v
    }
}

/**
 * Lanza el test de cámara en el proceso `:camtest`, lo vigila (si el coche deja de estar parado lo mata;
 * si el proceso muere sin resultado es un crash nativo) y sube el resultado a `cars/{id}/diag/cam`
 * junto con el logcat relevante. Mientras dura, el [CameraHub] tiene la cámara bloqueada ("testing").
 */
class CamTestRunner(
    private val ctx: Context,
    private val hub: CameraHub,
    private val root: DatabaseReference,
    private val onDone: (JSONObject) -> Unit
) {
    @Volatile var running = false; private set
    @Volatile var startedAt = 0L; private set

    fun start(reason: String): Boolean {
        synchronized(this) { if (running) return false; running = true }
        startedAt = System.currentTimeMillis()
        Thread({
            var result = JSONObject()
            try { result = runBlocking(reason) }
            catch (t: Throwable) { CamLog.e("test: fallo inesperado", t); result.put("verdict", "ERROR").put("verdictMsg", BmmRuntime.rootCause(t)) }
            finally {
                running = false
                try { onDone(result) } catch (t: Throwable) { CamLog.e("test: fallo al aplicar el resultado", t) }
            }
        }, "camtest-runner").start()
        return true
    }

    private fun runBlocking(reason: String): JSONObject {
        CamLog.i("test de cámara: empieza ($reason)")
        hub.block = "testing"
        if (!hub.awaitClosed(5000)) CamLog.w("test: la cámara no se cerró a tiempo")
        val dir = File(ctx.filesDir, "camtest").apply { mkdirs() }
        File(dir, "result.json").delete(); File(dir, "progress.json").delete()
        val t0 = SystemClock.elapsedRealtime()
        val result = if (BmmRuntime.findJar() == null) CamTest(ctx, dir, 10_000).run()   // sin jar no se toca nada nativo
                     else runInOtherProcess(dir)
        result.put("reason", reason)
        result.put("wallMs", SystemClock.elapsedRealtime() - t0)
        result.put("versionCode", BuildConfigCompat.versionCode(ctx))
        result.put("version", BuildConfigCompat.appVersion(ctx))
        result.put("logcat", logcat())
        adbExtras()?.let { result.put("adb", it) }
        CamLog.i("test de cámara: ${result.optString("verdict")} — ${result.optString("verdictMsg")}")
        upload(result)
        return result
    }

    private fun runInOtherProcess(dir: File): JSONObject {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val procName = ctx.packageName + ":camtest"
        fun pid(): Int? = try { am.runningAppProcesses?.firstOrNull { it.processName == procName }?.pid } catch (e: Throwable) { null }
        pid()?.let { android.os.Process.killProcess(it); Thread.sleep(500) }   // uno anterior colgado
        try {
            ctx.startService(Intent(ctx, CamTestService::class.java).putExtra("budgetMs", 55_000L))
        } catch (e: Throwable) {
            return verdict("START_FAIL", "No se pudo arrancar el proceso de prueba: ${BmmRuntime.rootCause(e)}", dir)
        }
        val t0 = SystemClock.elapsedRealtime()
        var seen = false; var gone = 0
        val rf = File(dir, "result.json")
        while (true) {
            Thread.sleep(500)
            if (rf.exists()) return try { JSONObject(rf.readText()) } catch (e: Throwable) { verdict("ERROR", "Resultado ilegible: $e", dir) }
            val p = pid()
            if (p != null) { seen = true; gone = 0 }
            val el = SystemClock.elapsedRealtime() - t0
            if (!hub.parkedNow()) {
                p?.let { android.os.Process.killProcess(it) }
                return verdict("ABORTED_NOT_PARKED", "Test cancelado: el coche ha dejado de estar parado.", dir)
            }
            if (seen && p == null && ++gone >= 3) {
                Thread.sleep(300)
                if (rf.exists()) continue
                return verdict("CRASHED", "El proceso de prueba se cerró de golpe (crash nativo) en el paso: ${lastStep(dir)}", dir)
            }
            if (!seen && el > 10_000) return verdict("START_FAIL", "El proceso de prueba no llegó a arrancar.", dir)
            if (el > 75_000) {
                p?.let { android.os.Process.killProcess(it) }
                return verdict("TIMEOUT", "El test no terminó en 75 s (último paso: ${lastStep(dir)}).", dir)
            }
        }
    }

    private fun lastStep(dir: File): String =
        try { JSONObject(File(dir, "progress.json").readText()).optString("step", "?") } catch (e: Throwable) { "?" }

    private fun verdict(v: String, msg: String, dir: File) = JSONObject()
        .put("ts", System.currentTimeMillis()).put("verdict", v).put("verdictMsg", msg).put("lastStep", lastStep(dir))

    /** Últimas líneas de logcat que tienen que ver con la cámara (incluye el proceso de test y los crash nativos). */
    private fun logcat(): String = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "3000").redirectErrorStream(true).start()
        val re = Regex("AVMCamera|NormalCamera|JNIBMMCamera|BmmCameraInfo|JNILoader|bmmcamera|BMMCam|avc: denied|DEBUG|libc|" +
            "BYDLink|AndroidRuntime|linker|nativeloader|camtest|crash_dump|tombstone", RegexOption.IGNORE_CASE)
        val lines = p.inputStream.bufferedReader().useLines { seq -> seq.filter { re.containsMatchIn(it) }.toList() }
        try { p.destroy() } catch (e: Throwable) {}
        lines.takeLast(400).joinToString("\n")
    } catch (e: Throwable) { "logcat no disponible: $e" }

    /** Si hay ADB local: SELinux, props, apps de cámara y qué app está delante. Con tiempo máximo. */
    private fun adbExtras(): JSONObject? {
        var out: JSONObject? = null
        val th = Thread {
            val sh = CarShell(ctx.filesDir)
            try {
                if (sh.connect(BuildConfigCompat.adbHost(ctx)) && sh.works()) {
                    val o = JSONObject()
                    o.put("getenforce", sh.shell("getenforce")?.trim())
                    o.put("getprop", sh.shell("getprop | grep -iE 'cam|avm|pano|bmm|apa'")?.take(6000))
                    o.put("packages", sh.shell("pm list packages | grep -iE 'cam|dvr|sentry|monitor|sealnav|electro|bydmate|dashcam|avc|pano'")?.take(4000))
                    o.put("top", sh.shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -5")?.take(1000))
                    o.put("libs", sh.shell("ls -l /system/lib64/libbmm* /vendor/lib64/libbmm* /system/lib/libbmm* 2>&1")?.take(2000))
                    o.put("ps", sh.shell("ps -A | grep -iE 'cam|avm|bmm' | head -30")?.take(3000))
                    out = o
                }
            } catch (e: Throwable) { CamLog.w("test: ADB no disponible", e) }
            finally { sh.close() }
        }
        th.isDaemon = true
        th.start(); th.join(15_000)
        return out
    }

    private fun upload(result: JSONObject) {
        try {
            val m = Json.toMap(result).toMutableMap()
            m["uploadedAt"] = ServerValue.TIMESTAMP
            val vc = BuildConfigCompat.versionCode(ctx)
            CamPrefs.disabledFor(ctx, vc)?.let { why ->      // conservar el aviso de crash del proceso principal
                m["mainCrash"] = mapOf("reason" to why, "versionCode" to vc, "deaths" to CamPrefs.deaths(ctx, vc))
            }
            root.child("diag/cam").setValue(m)
                .addOnFailureListener { CamLog.w("test: no se pudo subir diag/cam", it) }
        } catch (e: Throwable) { CamLog.w("test: error al subir", e) }
        // Copia local (para verla por ADB): files/camtest/last.json y, si se puede, almacenamiento externo.
        try { File(File(ctx.filesDir, "camtest"), "last.json").writeText(result.toString(2)) } catch (e: Throwable) {}
        try { ctx.getExternalFilesDir(null)?.let { File(it, "camtest.json").writeText(result.toString(2)) } } catch (e: Throwable) {}
        uploadJar(result)
    }

    /** Sube el bmmcamera.jar del coche (≈50 KB) una vez, para desensamblar la versión EXACTA del Surf. */
    private fun uploadJar(result: JSONObject) {
        try {
            val jar = result.optJSONObject("jar") ?: return
            val path = jar.optString("path").takeIf { it.isNotEmpty() } ?: return
            val sha = jar.optString("sha256")
            val sp = CamProfile.prefs(ctx)
            if (sp.getString("jarUploaded", null) == sha) return
            val f = File(path)
            if (!f.canRead() || f.length() > 2_000_000) return
            root.child("diag/bmmjar").setValue(mapOf("path" to path, "size" to f.length(), "sha256" to sha,
                "b64" to Yuv.b64(f.readBytes()), "ts" to ServerValue.TIMESTAMP))
                .addOnSuccessListener { sp.edit().putString("jarUploaded", sha).apply() }
        } catch (e: Throwable) { CamLog.w("test: no se pudo subir el jar", e) }
    }
}
