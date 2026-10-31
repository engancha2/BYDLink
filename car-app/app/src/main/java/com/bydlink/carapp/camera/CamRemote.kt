package com.bydlink.carapp.camera

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/*
 * Cámara "remota": foto y directo JPEG servidos desde el proceso aparte `:camtest`.
 * Se usa cuando la cámara del proceso principal está desactivada para esta versión porque el
 * proceso murió con ella abierta (2 veces en < 2 min), pero el test en proceso aparte sí salió bien.
 * Si la librería nativa peta, muere `:camtest`, nunca MainService. Intercambio por ficheros en
 * `files/camtest/`: photo.jpg + photo.json; live{0,1,2}.jpg + live.json; live.cam (cámara pedida).
 */

/** Lado `:camtest`: abre la cámara con el perfil, saca la foto o va dejando frames del directo. */
class RemoteWorker(private val ctx: Context, private val dir: File, private val profile: CamProfile) {

    private class Grab(val camera: String, val maxW: Int) { val latch = CountDownLatch(1); @Volatile var out: Rendered? = null }
    @Volatile private var grab: Grab? = null
    @Volatile private var firstAt = 0L
    private val iT: Class<*> = Int::class.javaPrimitiveType!!

    private val listener = object : BmmCamera.Listener {
        override fun onPreview(data: ByteArray?, w: Int, h: Int, fmt: Int, size: Int, view: Int, ts: Long) {
            if (data == null) return
            val now = SystemClock.elapsedRealtime()
            if (firstAt == 0L) firstAt = now
            val g0 = grab ?: return
            if (now - firstAt < 700) return
            val n = if (size in 1..data.size) size else data.size
            val g = FrameGeom.deduce(w, h, fmt, n, profile.grid, profile.nv12) ?: return
            val r = render(data, g, g0.camera, g0.maxW) ?: return
            g0.out = r; grab = null; g0.latch.countDown()
        }
        override fun onEvent(type: Int, a1: Int, a2: Int) {}
    }

    private fun render(data: ByteArray, g: FrameGeom, name: String, maxW: Int): Rendered? {
        if (name == "mosaic") {
            val tiles = CamProfile.NAMES.map { n -> profile.cams[n]?.tile ?: -1 }
            val w = maxW and 3.inv(); val h = (Yuv.fit(g.tileW, g.tileH, w / 2).second * 2) and 3.inv()
            return Rendered(Yuv.mosaic(data, g, tiles, w, h), w, h)
        }
        val r = profile.cams[name] ?: return null
        val (w, h) = Yuv.fit(g.tileW, g.tileH, maxW)
        return Rendered(Yuv.tile(data, g, r.tile, w, h), w, h)
    }

    private fun open(view: Int): BmmCamera {
        val jni = BmmRuntime.loadJni(ctx)
        if (jni != "ok") throw CamError("jni", "No se pudo cargar la librería de cámara del coche ($jni).")
        val t = BmmInfo.resolve(ctx, profile)
        val c = when (profile.api) {
            "avm", "normal" -> BmmCamera.open(ctx, profile.api, t.id)
            else -> try { BmmCamera.open(ctx, "avm", t.id) }
                    catch (e: CamError) { if (e.code == "open_refused") BmmCamera.open(ctx, "normal", t.id) else throw e }
        }
        c.setCallbacks(listener)
        if (profile.previewW > 0 && profile.previewH > 0) c.setPreviewSize(profile.previewW, profile.previewH)
        c.enable(if (c.isAvm) view else 0)
        c.start()
        if (profile.restart) { c.stop(); c.start() }
        return c
    }

    private fun take(camera: String, maxW: Int, timeoutMs: Long): Rendered? {
        val g = Grab(camera, maxW); grab = g
        return if (g.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) g.out else null.also { grab = null }
    }

    private fun write(name: String, bytes: ByteArray) {
        val tmp = File(dir, "$name.tmp"); tmp.writeBytes(bytes)
        val f = File(dir, name); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun photo(camera: String, maxW: Int, quality: Int) {
        dir.mkdirs()
        val view = profile.cams[camera]?.view ?: 0
        var c: BmmCamera? = null
        try {
            c = open(view)
            val r = take(camera, maxW, 8_000) ?: throw CamError("no_frames", "No han llegado imágenes de la cámara a tiempo.")
            val jpg = Yuv.jpeg(r.nv21, r.w, r.h, quality)
            write("photo.jpg", jpg)
            write("photo.json", JSONObject().put("ok", true).put("camera", camera).put("w", r.w).put("h", r.h).put("bytes", jpg.size).toString().toByteArray())
        } catch (e: Throwable) {
            val code = (e as? CamError)?.code ?: "error"
            write("photo.json", JSONObject().put("ok", false).put("code", code).put("error", e.message ?: BmmRuntime.rootCause(e)).toString().toByteArray())
        } finally {
            c?.closeFully(view, null)
        }
    }

    fun live(camera0: String, fps: Double, maxMs: Long) {
        dir.mkdirs()
        var camera = camera0
        var view = profile.cams[camera]?.view ?: 0
        val c = open(view)
        var seq = 0L
        try {
            val end = SystemClock.elapsedRealtime() + maxMs
            val period = (1000.0 / fps.coerceIn(0.2, 2.0)).toLong()
            while (SystemClock.elapsedRealtime() < end) {
                val t0 = SystemClock.elapsedRealtime()
                // El proceso principal reescribe live.cam cada 2 s: si deja de hacerlo (murió), se para solo.
                val camFile = File(dir, "live.cam")
                if (System.currentTimeMillis() - camFile.lastModified() > 30_000) break
                val want = try { camFile.readText().trim() } catch (e: Throwable) { camera }
                if (want.isNotEmpty() && want != camera) {
                    camera = want
                    val nv = profile.cams[want]?.view ?: view
                    if (nv != view && c.isAvm) { c.tryCall("disablePreviewCallback", arrayOf(iT), view); c.tryCall("enablePreviewCallback", arrayOf(iT), nv); view = nv }
                }
                val r = take(camera, 480, 3_000)
                if (r != null) {
                    seq++
                    val jpg = Yuv.jpegMax(r.nv21, r.w, r.h, 45_000, 55)
                    val file = "live${seq % 3}.jpg"
                    write(file, jpg)
                    write("live.json", JSONObject().put("seq", seq).put("file", file).put("camera", camera).put("w", r.w).put("h", r.h).toString().toByteArray())
                }
                val left = period - (SystemClock.elapsedRealtime() - t0)
                if (left > 0) Thread.sleep(left)
            }
        } finally {
            c.closeFully(view, null)
        }
    }
}

/** Lado del proceso principal: lanza `:camtest` en modo foto/directo y lo mata si el coche deja de estar parado. */
class RemoteCam(private val ctx: Context, private val parked: () -> Boolean) {
    private val dir = File(ctx.filesDir, "camtest")
    private val procName = ctx.packageName + ":camtest"
    @Volatile var mode: String? = null; private set
    @Volatile private var guard: Thread? = null

    fun busy() = mode != null

    private fun pid(): Int? = try {
        ctx.getSystemService(ActivityManager::class.java).runningAppProcesses?.firstOrNull { it.processName == procName }?.pid
    } catch (e: Throwable) { null }

    fun kill(): Boolean { val p = pid() ?: return false; android.os.Process.killProcess(p); return true }

    private fun launch(extras: Intent.() -> Unit) {
        if (kill()) Thread.sleep(500)     // que muera del todo el anterior antes de arrancar otro
        dir.mkdirs()
        val i = Intent(ctx, CamTestService::class.java).apply(extras)
        try { ctx.startService(i) } catch (e: Throwable) {
            mode = null
            throw CamError("error", "No se pudo arrancar el proceso de cámara: ${BmmRuntime.rootCause(e)}")
        }
        if (guard == null) guard = Thread({
            while (mode != null) {
                if (!parked()) { CamLog.w("cámara remota: el coche ya no está parado, se corta"); kill(); mode = null; break }
                Thread.sleep(300)
            }
            guard = null
        }, "cam-remote-guard").apply { isDaemon = true; start() }
    }

    /** Foto desde `:camtest` (bloqueante, ≤ 11 s). Si el directo remoto va, devuelve su último frame. */
    fun photo(camera: String, maxW: Int, quality: Int, profile: CamProfile): Triple<ByteArray, Int, Int> {
        if (mode == "live") {
            val (meta, jpg) = latest(0) ?: throw CamError("no_frames", "Aún no hay imagen del directo.")
            return Triple(jpg, meta.optInt("w"), meta.optInt("h"))
        }
        if (mode != null) throw CamError("busy", "La cámara ya se está usando.")
        listOf("photo.json", "photo.jpg").forEach { File(dir, it).delete() }
        mode = "photo"
        try {
            launch {
                putExtra("mode", "photo"); putExtra("camera", camera); putExtra("w", maxW)
                putExtra("quality", quality); putExtra("profile", profile.toJson().toString())
            }
            val t0 = SystemClock.elapsedRealtime()
            val jf = File(dir, "photo.json")
            while (SystemClock.elapsedRealtime() - t0 < 11_000) {
                Thread.sleep(150)
                if (mode == null) throw CamError.notParked()
                if (!jf.exists()) continue
                val j = JSONObject(jf.readText())
                if (!j.optBoolean("ok")) throw CamError(j.optString("code", "error"), j.optString("error", "Error de cámara."))
                return Triple(File(dir, "photo.jpg").readBytes(), j.optInt("w"), j.optInt("h"))
            }
            kill()
            throw CamError("no_frames", "La cámara no respondió a tiempo.")
        } finally {
            if (mode == "photo") mode = null
        }
    }

    fun startLive(camera: String, fps: Double, profile: CamProfile, maxMs: Long) {
        if (mode == "live") { switchLive(camera); return }
        if (mode != null) throw CamError("busy", "La cámara ya se está usando.")
        listOf("live.json", "live0.jpg", "live1.jpg", "live2.jpg").forEach { File(dir, it).delete() }
        dir.mkdirs(); File(dir, "live.cam").writeText(camera)
        mode = "live"
        launch {
            putExtra("mode", "live"); putExtra("camera", camera); putExtra("fps", fps)
            putExtra("maxMs", maxMs); putExtra("profile", profile.toJson().toString())
        }
    }

    fun switchLive(camera: String) {
        try { val t = File(dir, "live.cam.tmp"); t.writeText(camera); t.renameTo(File(dir, "live.cam")) } catch (e: Throwable) {}
    }

    fun stopLive() {
        if (mode != "live") return
        mode = null
        kill()
    }

    /** Último frame del directo remoto si su seq es mayor que [afterSeq]: (meta, jpeg). */
    fun latest(afterSeq: Long): Pair<JSONObject, ByteArray>? = try {
        val j = JSONObject(File(dir, "live.json").readText())
        if (j.optLong("seq") <= afterSeq) null else j to File(dir, j.getString("file")).readBytes()
    } catch (e: Throwable) { null }
}
