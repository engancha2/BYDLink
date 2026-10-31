package com.bydlink.carapp.camera

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Servicio del test de cámara, en su PROPIO proceso (`:camtest`, ver manifiesto): si la librería nativa
 * de BYD peta, muere este proceso y no el servicio principal. También sirve foto/directo JPEG
 * ("cámara remota", [RemoteWorker]) cuando la cámara del proceso principal está desactivada. No usa Firebase: escribe
 * `files/camtest/progress.json` (último paso alcanzado) y `files/camtest/result.json`, y el proceso
 * principal ([CamTestRunner]) los sube. Al terminar se mata a sí mismo para soltar del todo la cámara.
 */
class CamTestService : Service() {
    @Volatile private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_NOT_STICKY
        started = true
        val budget = intent?.getLongExtra("budgetMs", 55_000L) ?: 55_000L
        val mode = intent?.getStringExtra("mode") ?: "test"
        Thread({
            try {
                val dir = File(filesDir, "camtest")
                when (mode) {
                    "photo", "live" -> {
                        val prof = try { CamProfile.fromJson(JSONObject(intent?.getStringExtra("profile") ?: "{}")) } catch (e: Throwable) { CamProfile() }
                        val w = RemoteWorker(applicationContext, dir, prof)
                        val cam = intent?.getStringExtra("camera") ?: "front"
                        if (mode == "photo") w.photo(cam, intent?.getIntExtra("w", 640) ?: 640, intent?.getIntExtra("quality", 70) ?: 70)
                        else w.live(cam, intent?.getDoubleExtra("fps", 1.0) ?: 1.0, intent?.getLongExtra("maxMs", 190_000L) ?: 190_000L)
                    }
                    else -> CamTest(applicationContext, dir, budget).run()
                }
            }
            catch (t: Throwable) { Log.e("BYDLink", "[cam] proceso de cámara ($mode): fallo", t) }
            finally {
                stopSelf()
                Handler(mainLooper).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
            }
        }, "camtest").start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * Prueba TODO lo de la cámara BMM (plan §4.2) y deja un JSON con resultados, huellas y miniaturas.
 * Se puede ejecutar en cualquier proceso, pero lo que toca código nativo solo debe correr en `:camtest`.
 */
class CamTest(private val ctx: Context, private val dir: File, private val budgetMs: Long = 55_000) {

    private val t0 = SystemClock.elapsedRealtime()
    private val res = JSONObject()
    private val thumbs = JSONObject()
    private val iT: Class<*> = Int::class.javaPrimitiveType!!
    private var thumbRunDone = false
    private val cbThread = HandlerThread("camtest-cb").apply { start() }

    private fun el() = SystemClock.elapsedRealtime() - t0
    private fun left() = budgetMs - el()

    private fun progress(step: String) {
        Log.i("BYDLink", "[cam] test: $step")
        res.put("lastStep", step)
        write("progress.json", JSONObject().put("step", step).put("t", el()).toString())
    }

    private fun write(name: String, text: String) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp"); tmp.writeText(text)
            if (!tmp.renameTo(File(dir, name))) { File(dir, name).delete(); tmp.renameTo(File(dir, name)) }
        } catch (e: Throwable) { Log.w("BYDLink", "[cam] test: no se pudo escribir $name", e) }
    }

    fun run(): JSONObject {
        res.put("ts", System.currentTimeMillis())
        res.put("process", processName())
        var verdict: String; var msg: String
        try {
            progress("env"); res.put("env", env())
            progress("jar"); val jar = jarInfo(); res.put("jar", jar)
            if (!jar.optBoolean("found")) {
                verdict = "NO_JAR"; msg = "Este coche (o el emulador) no tiene bmmcamera.jar: no hay cámaras BYD que probar."
                res.put("camera2", camera2())
                return finish(verdict, msg, null)
            }
            progress("props"); res.put("props", props())
            progress("libs"); res.put("libs", libs())
            progress("classes"); val classes = classes(); res.put("classes", classes)
            progress("info"); val info = info(); res.put("info", info)
            progress("others"); res.put("others", others())
            progress("camera2"); res.put("camera2", camera2())

            progress("jni")
            val jni = BmmRuntime.loadJni(ctx)
            classes.put("jni", jni)
            classes.put("jniMode", BmmRuntime.mode ?: "?")
            if (jni != "ok") return finish("JNI_FAIL", "No carga libbmmcamera_jni: $jni", null)

            val target = BmmInfo.resolve(ctx, CamProfile())
            res.put("target", JSONObject(target.toMap()))
            val id = target.id
            val runs = JSONArray(); res.put("runs", runs)

            // A: lo mínimo (sin setPreviewSize), índice 0; si no hay frames, A′ = stop/start.
            val a = run("A", "avm", id, null, false, false, 4000, restartIfNone = true, sweep = false)
            runs.put(a)
            var best: JSONObject? = a.takeIf { frames(it) > 0 }
            val avmOpens = a.optString("open").startsWith("ok")
            if (best == null && avmOpens) {
                for ((n, size, surf) in listOf(Triple("B", 1280 to 960, false), Triple("C", 5120 to 960, false), Triple("D", null, true))) {
                    if (left() < 8000) break
                    val r = run(n, "avm", id, size, surf, false, 3500, restartIfNone = false, sweep = false)
                    runs.put(r)
                    if (frames(r) > 0) { best = r; break }
                }
            }
            if (!avmOpens && left() > 8000) {
                val f = run("F", "normal", id, null, false, false, 4000, restartIfNone = true, sweep = false)
                runs.put(f); if (frames(f) > 0) best = f
                if (best == null && left() > 8000) {
                    val pr = run("P", "avm", id, null, false, true, 3500, restartIfNone = true, sweep = false)
                    runs.put(pr); if (frames(pr) > 0) best = pr
                }
            }
            // Canales 1..5 con la mejor configuración AVM (o la A si no hubo frames, por si solo van los canales).
            var channels = JSONArray()
            val baseForSweep = best?.takeIf { it.optString("api") == "avm" } ?: a.takeIf { avmOpens }
            if (baseForSweep != null && left() > 9000) {
                val size = baseForSweep.optJSONObject("config")?.let { c -> if (c.has("previewW")) c.getInt("previewW") to c.getInt("previewH") else null }
                val sw = run("S", "avm", id, size, baseForSweep.optJSONObject("config")?.optBoolean("surface") == true, false,
                    1500, restartIfNone = false, sweep = true)
                channels = sw.optJSONArray("channels") ?: JSONArray()
                sw.remove("channels"); runs.put(sw)
            }
            res.put("channels", channels)

            val chOk = (0 until channels.length()).map { channels.getJSONObject(it) }.filter { it.optInt("frames") > 0 }
            val proposed = propose(best, chOk, target)
            when {
                best != null || chOk.isNotEmpty() -> { verdict = "OK_FRAMES"; msg = "Llegan imágenes de la cámara." }
                (0 until runs.length()).none { runs.getJSONObject(it).optString("open").startsWith("ok") } -> {
                    val noTags = info.optInt("numbers", 0) == 0 && (info.optJSONArray("validTags")?.length() ?: 0) == 0
                    val busy = (0 until runs.length()).any { r -> runs.getJSONObject(r).optJSONArray("events")?.toString()?.contains("1008") == true }
                    verdict = when { noTags -> "NO_VALID_CAMERA"; busy -> "BUSY"; else -> "OPEN_NULL" }
                    msg = when (verdict) {
                        "NO_VALID_CAMERA" -> "El coche no declara ninguna cámara (vehicle.config.cam_sort vacío)."
                        "BUSY" -> "La cámara está ocupada por otra app del coche."
                        else -> "El coche no deja abrir la cámara (open devuelve null)."
                    }
                }
                else -> { verdict = "NO_FRAMES"; msg = "La cámara abre pero no llega ningún frame." }
            }
            return finish(verdict, msg, proposed)
        } catch (t: Throwable) {
            res.put("exception", BmmRuntime.rootCause(t))
            return finish("ERROR", "El test falló: ${BmmRuntime.rootCause(t)}", null)
        } finally {
            cbThread.quitSafely()
        }
    }

    private fun finish(verdict: String, msg: String, proposed: CamProfile?): JSONObject {
        res.put("verdict", verdict); res.put("verdictMsg", msg)
        res.put("durationMs", el())
        if (proposed != null) res.put("proposed", proposed.toJson())
        res.put("thumbs", thumbs)
        progress("fin:$verdict")
        write("result.json", res.toString())
        return res
    }

    private fun frames(r: JSONObject) = r.optInt("frames", 0)

    // ---------------------------------------------------------------- pruebas con la cámara

    /** Recoge frames de una fase. El buffer se copia UNA vez (a ~1 s del primer frame) para analizarlo fuera. */
    private inner class Collector(val startAt: Long = SystemClock.elapsedRealtime()) : BmmCamera.Listener {
        @Volatile var frames = 0
        @Volatile var firstMs = -1L
        var meta: JSONObject? = null
        val events = JSONArray()
        @Volatile var capture: ByteArray? = null
        @Volatile var capMeta: IntArray? = null
        private var firstAt = 0L

        override fun onPreview(data: ByteArray?, w: Int, h: Int, fmt: Int, size: Int, view: Int, ts: Long) {
            if (data == null) return
            frames++
            val now = SystemClock.elapsedRealtime()
            if (firstMs < 0) {
                firstMs = now - startAt; firstAt = now
                meta = JSONObject().put("w", w).put("h", h).put("fmt", fmt).put("dataSize", size)
                    .put("bufLen", data.size).put("view", view).put("ts", ts)
            }
            if (capture == null && frames >= 3 && now - firstAt >= 900) {
                val n = if (size in 1..data.size) size else data.size
                capMeta = intArrayOf(w, h, fmt, n, view)
                capture = data.copyOf(n)
            }
        }

        override fun onEvent(type: Int, a1: Int, a2: Int) {
            if (type == 1001) return
            synchronized(events) {
                if (events.length() < 40) events.put(JSONObject().put("ms", SystemClock.elapsedRealtime() - startAt).put("type", type).put("a1", a1).put("a2", a2))
            }
        }

        fun toJson(o: JSONObject) {
            val dur = (SystemClock.elapsedRealtime() - startAt).coerceAtLeast(1)
            o.put("frames", frames).put("firstFrameMs", firstMs)
            o.put("fps", if (firstMs >= 0 && frames > 1) Math.round((frames - 1) * 10000.0 / (dur - firstMs).coerceAtLeast(1)) / 10.0 else 0.0)
            meta?.let { o.put("meta", it) }
            synchronized(events) { o.put("events", JSONArray(events.toString())) }
        }
    }

    /** Reparte los callbacks al recolector de la fase actual. */
    private class Switch : BmmCamera.Listener {
        @Volatile var cur: BmmCamera.Listener? = null
        override fun onPreview(data: ByteArray?, w: Int, h: Int, fmt: Int, size: Int, view: Int, ts: Long) { cur?.onPreview(data, w, h, fmt, size, view, ts) }
        override fun onEvent(type: Int, a1: Int, a2: Int) { cur?.onEvent(type, a1, a2) }
    }

    private fun sleepBudget(ms: Long) { Thread.sleep(minOf(ms, (left() - 3000).coerceAtLeast(0))) }

    private fun run(
        name: String, api: String, id: Int, size: Pair<Int, Int>?, surface: Boolean, allowPrivate: Boolean,
        waitMs: Long, restartIfNone: Boolean, sweep: Boolean
    ): JSONObject {
        val r = JSONObject().put("name", name).put("api", api).put("id", id)
        val cfg = JSONObject().put("surface", surface).put("private", allowPrivate)
        size?.let { cfg.put("previewW", it.first).put("previewH", it.second) }
        r.put("config", cfg)
        val calls = JSONObject(); r.put("calls", calls)
        val sw = Switch()
        var c: BmmCamera? = null
        var reader: ImageReader? = null
        var view = 0
        try {
            progress("run $name: open")
            val ts = SystemClock.elapsedRealtime()
            val cam = try { BmmCamera.open(ctx, api, id, allowPrivate) }
                catch (e: CamError) { r.put("open", "null").put("openMs", SystemClock.elapsedRealtime() - ts); return r }
                catch (e: Throwable) { r.put("open", "EX: " + BmmRuntime.rootCause(e)).put("openMs", SystemClock.elapsedRealtime() - ts); return r }
            c = cam
            r.put("open", if (cam.viaPrivate) "ok(privado)" else "ok").put("openMs", SystemClock.elapsedRealtime() - ts)
            var col = Collector(); sw.cur = col
            progress("run $name: callbacks")
            calls.put("callbacks", try { cam.setCallbacks(sw); "ok" } catch (e: Throwable) { "EX: " + BmmRuntime.rootCause(e) })
            if (size != null) calls.put("setPreviewSize", cam.tryCall("setPreviewSize", arrayOf(iT, iT), size.first, size.second))
            r.put("pw", cam.previewW() ?: -1).put("ph", cam.previewH() ?: -1)
            progress("run $name: enablePreviewCallback")
            calls.put("enablePreviewCallback", if (cam.isAvm) cam.tryCall("enablePreviewCallback", arrayOf(iT), 0) else cam.tryCall("enablePreviewCallback", arrayOf()))
            if (surface) {
                try {
                    val rr = ImageReader.newInstance(maxOf(2, cam.previewW() ?: 1280), maxOf(2, cam.previewH() ?: 960), PixelFormat.RGBA_8888, 2)
                    rr.setOnImageAvailableListener({ x -> try { x.acquireLatestImage()?.close() } catch (e: Throwable) {} }, Handler(cbThread.looper))
                    reader = rr
                    calls.put("addPreviewSurface", try { "${cam.addSurface(rr.surface, 0)}" } catch (e: Throwable) { "EX: " + BmmRuntime.rootCause(e) })
                    calls.put("setPreviewSurface", try { "${cam.setSurface(rr.surface, 0)}" } catch (e: Throwable) { "EX: " + BmmRuntime.rootCause(e) })
                } catch (e: Throwable) { calls.put("surface", "EX: " + BmmRuntime.rootCause(e)) }
            }
            progress("run $name: startPreview")
            col = Collector(); sw.cur = col
            calls.put("startPreview", cam.tryCall("startPreview", arrayOf()))
            sleepBudget(waitMs)
            if (col.frames == 0 && restartIfNone && left() > 6000) {
                progress("run $name: stop/start")
                calls.put("restart", cam.tryCall("stopPreview", arrayOf()) + "/" + cam.tryCall("startPreview", arrayOf()))
                r.put("restarted", true)
                sleepBudget(3000)
            }
            col.toJson(r)
            analyze(name, col, r, wholeThumbs = true)
            if (sweep && cam.isAvm) {
                val chs = JSONArray()
                var prev = 0
                for (v in 1..5) {
                    if (left() < 4500) break
                    progress("canal $v")
                    val ch = JSONObject().put("view", v)
                    ch.put("disable", cam.tryCall("disablePreviewCallback", arrayOf(iT), prev))
                    ch.put("enable", cam.tryCall("enablePreviewCallback", arrayOf(iT), v))
                    view = v; prev = v
                    val cc = Collector(); sw.cur = cc
                    ch.put("restart", cam.tryCall("stopPreview", arrayOf()) + "/" + cam.tryCall("startPreview", arrayOf()))
                    sleepBudget(2500)
                    cc.toJson(ch)
                    analyze("ch$v", cc, ch, wholeThumbs = false)
                    chs.put(ch)
                }
                r.put("channels", chs)
            }
        } catch (t: Throwable) {
            r.put("exception", BmmRuntime.rootCause(t))
        } finally {
            sw.cur = null
            if (c != null) {
                progress("run $name: close")
                val errs = c.closeFully(view, reader?.surface)
                if (errs.isNotEmpty()) r.put("closeErrors", JSONArray(errs))
            }
            try { reader?.close() } catch (e: Throwable) {}
        }
        return r
    }

    /** Geometría, huellas por trozo y miniaturas del frame capturado. */
    private fun analyze(key: String, col: Collector, out: JSONObject, wholeThumbs: Boolean) {
        val data = col.capture ?: return
        val m = col.capMeta ?: return
        val g = FrameGeom.deduce(m[0], m[1], m[2], m[3], "auto", false)
        if (g == null) { out.put("geom", "no interpretable (w=${m[0]} h=${m[1]} fmt=${m[2]} n=${m[3]})"); return }
        out.put("geom", JSONObject(g.toMap()))
        val fp = JSONObject(); val luma = JSONObject()
        for (k in 0 until g.tiles) { fp.put("t$k", Yuv.fingerprint(data, g, k)); luma.put("t$k", Yuv.meanLuma(data, g, k)) }
        out.put("fp", fp); out.put("meanLuma", luma)
        val names = JSONArray()
        fun thumb(name: String, nv: ByteArray, w: Int, h: Int) {
            try { thumbs.put(name, Yuv.b64(Yuv.jpegMax(nv, w, h, 14_000, 60))); names.put(name) } catch (e: Throwable) { out.put("thumbErr", "$e") }
        }
        if (wholeThumbs && thumbRunDone) return     // miniaturas completas solo de la primera fase con frames
        if (wholeThumbs) {
            thumbRunDone = true
            for (k in 0 until g.tiles) {
                val (w, h) = Yuv.fit(g.tileW, g.tileH, 320)
                thumb("${key}_t$k", Yuv.tile(data, g, k, w, h), w, h)
            }
            if (g.tiles > 1) {
                val (w, h) = Yuv.fit(g.w, g.h, 640)
                thumb("${key}_full", Yuv.whole(data, g, w, h), w, h)
            }
            // Misma imagen interpretando el croma al revés (NV12): la que tenga colores naturales manda.
            FrameGeom.deduce(m[0], m[1], m[2], m[3], "auto", true)?.let { g2 ->
                val (w, h) = Yuv.fit(g2.tileW, g2.tileH, 320)
                thumb("${key}_t0_nv12", Yuv.tile(data, g2, 0, w, h), w, h)
            }
        } else {
            val (w, h) = Yuv.fit(g.w, g.h, if (g.tiles > 1) 640 else 320)
            thumb(key, Yuv.whole(data, g, w, h), w, h)
        }
        out.put("thumbs", names)
    }

    /** Perfil propuesto a partir de lo que ha funcionado. */
    private fun propose(best: JSONObject?, chOk: List<JSONObject>, t: BmmInfo.Target): CamProfile? {
        val distinctCh = chOk.filter { it.optInt("view") in 1..4 }.mapNotNull { it.optJSONObject("fp")?.optString("t0") }.distinct().size >= 2
        val chMap = linkedMapOf<String, CamRef>()
        chOk.filter { it.optInt("view") in 1..4 }.forEach { ch ->
            val v = ch.optInt("view"); chMap[CamProfile.NAMES[v - 1]] = CamRef(v, 0)
        }
        if (best == null) {
            if (chMap.isEmpty()) return null
            return CamProfile(api = "avm", cams = chMap, source = "test")
        }
        val cfg = best.optJSONObject("config") ?: JSONObject()
        val tiles = best.optJSONObject("geom")?.optInt("tiles", 1) ?: 1
        // En coches con solo cámara trasera (tag rvs/rear, p. ej. Dolphin Surf) los "canales" 1..4 son la MISMA
        // imagen cortada en 4 tiras (192 px = 768/4): distintas entre sí, pero no son 4 cámaras.
        val bestW = best.optJSONObject("geom")?.optInt("w", 0) ?: 0
        val chW = chOk.firstOrNull { it.optInt("view") in 1..4 }?.optJSONObject("geom")?.optInt("w", 0) ?: 0
        val slices = bestW > 0 && chW > 0 && chW * 4 == bestW
        val rearOnly = t.kind == "single" || t.tag.lowercase() in setOf("rvs", "rear", "rvc")
        val cams: Map<String, CamRef> = when {
            tiles >= 4 -> CamProfile.DEFAULT_CAMS
            rearOnly || slices -> linkedMapOf("back" to CamRef(0, 0))
            tiles == 1 && distinctCh && best.optString("api") == "avm" -> chMap
            tiles in 2..3 -> linkedMapOf<String, CamRef>().apply { for (k in 0 until tiles) put(CamProfile.NAMES[k], CamRef(0, k)) }
            else -> linkedMapOf("front" to CamRef(0, 0))
        }
        return CamProfile(
            api = best.optString("api", "auto"),
            previewW = cfg.optInt("previewW", 0), previewH = cfg.optInt("previewH", 0),
            restart = best.optBoolean("restarted", false), surface = cfg.optBoolean("surface", false),
            cams = cams, source = "test"
        )
    }

    // ---------------------------------------------------------------- información (sin tocar la cámara)

    private fun env() = JSONObject().apply {
        put("sdk", Build.VERSION.SDK_INT); put("release", Build.VERSION.RELEASE)
        put("model", Build.MODEL); put("device", Build.DEVICE); put("product", Build.PRODUCT)
        put("manufacturer", Build.MANUFACTURER); put("brand", Build.BRAND); put("hardware", Build.HARDWARE)
        put("fingerprint", Build.FINGERPRINT); put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        put("is64", android.os.Process.is64Bit()); put("uid", android.os.Process.myUid()); put("pid", android.os.Process.myPid())
        put("targetSdk", ctx.applicationInfo.targetSdkVersion)
        put("appVersion", try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Throwable) { "?" })
        put("cameraPerm", ctx.checkSelfPermission("android.permission.CAMERA") == 0)
        put("panoramaPerm", ctx.checkSelfPermission("android.permission.BYDAUTO_PANORAMA_COMMON") == 0)
        put("readLogs", ctx.checkSelfPermission("android.permission.READ_LOGS") == 0)
    }

    private fun jarInfo(): JSONObject {
        val o = JSONObject()
        val path = BmmRuntime.findJar()
        o.put("found", path != null)
        o.put("candidates", JSONArray(BmmRuntime.JARS.filter { File(it).exists() }))
        if (path != null) {
            val f = File(path)
            o.put("path", path).put("size", f.length())
            try {
                val md = MessageDigest.getInstance("SHA-256")
                f.inputStream().use { s -> val b = ByteArray(65536); while (true) { val n = s.read(b); if (n <= 0) break; md.update(b, 0, n) } }
                o.put("sha256", md.digest().joinToString("") { "%02x".format(it) })
            } catch (e: Throwable) { o.put("sha256", "EX: $e") }
            try { BmmRuntime.loader(ctx); o.put("loader", BmmRuntime.mode) } catch (e: Throwable) { o.put("loader", "EX: " + BmmRuntime.rootCause(e)) }
        }
        return o
    }

    private fun props(): JSONObject {
        val o = JSONObject()
        o.put("cam_sort", BmmInfo.sysProp("vehicle.config.cam_sort") ?: JSONObject.NULL)
        val lines = JSONArray()
        try {
            val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
            val re = Regex("cam|avm|pano|bmm|apa|dvr|vehicle\\.config", RegexOption.IGNORE_CASE)
            p.inputStream.bufferedReader().useLines { seq -> seq.filter { re.containsMatchIn(it) }.take(120).forEach { lines.put(it) } }
        } catch (e: Throwable) { lines.put("EX: $e") }
        o.put("getprop", lines)
        return o
    }

    private fun libs(): JSONObject {
        val o = JSONObject()
        val names = listOf("libbmmcamera_jni.so", "libbmmcamera_client.so", "libbmmcameraservice.so")
        val dirs = listOf("/system/lib64", "/system/lib", "/vendor/lib64", "/vendor/lib", "/system_ext/lib64", "/odm/lib64")
        val found = JSONArray()
        for (d in dirs) for (n in names) if (File(d, n).exists()) found.put("$d/$n")
        o.put("found", found)
        val pub = JSONArray()
        val files = (File("/system/etc").listFiles()?.filter { it.name.startsWith("public.libraries") } ?: emptyList()) +
            listOf(File("/vendor/etc/public.libraries.txt"))
        for (f in files) try { if (f.canRead()) f.readLines().filter { it.contains("bmm", true) }.forEach { pub.put("${f.name}: $it") } } catch (e: Throwable) {}
        o.put("public", pub)
        o.put("libPath", BmmRuntime.libPath())
        try { o.put("sharedLibs", JSONArray(ctx.packageManager.systemSharedLibraryNames?.filter { Regex("bmm|byd|cam", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: emptyList<String>())) } catch (e: Throwable) {}
        return o
    }

    private fun classes(): JSONObject {
        val o = JSONObject()
        val list = listOf("AVMCamera", "AVMCamera\$IPreviewCallback", "AVMCamera\$IEventCallback", "NormalCamera",
            "NormalCamera\$IPreviewCallback", "NormalCamera\$IEventCallback", "BmmCameraInfo", "JNIBMMCamera", "JNILoader")
        for (n in list) {
            val key = n.replace('$', '_')
            try {
                val c = BmmRuntime.cls(ctx, "android.hardware.$n")
                if (n.endsWith("Callback")) o.put(key, c.declaredMethods.joinToString(" | ") { m -> "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})" })
                else o.put(key, "ok")
            } catch (e: Throwable) { o.put(key, "EX: " + BmmRuntime.rootCause(e)) }
        }
        o.put("loaderMode", BmmRuntime.mode ?: "?")
        return o
    }

    private fun info(): JSONObject {
        val o = JSONObject()
        o.put("numbers", BmmInfo.numbers(ctx) ?: -1)
        o.put("validTags", JSONArray(BmmInfo.validTags(ctx)))
        val ids = JSONObject(); BmmInfo.TAGS.forEach { ids.put(it, BmmInfo.idOf(ctx, it)) }; o.put("ids", ids)
        val byIdx = JSONArray()
        for (i in 0..7) byIdx.put(JSONObject().put("i", i).put("tag", BmmInfo.tagOf(ctx, i) ?: JSONObject.NULL).put("valid", BmmInfo.isValid(ctx, i) ?: JSONObject.NULL))
        o.put("byIndex", byIdx)
        return o
    }

    private fun others(): JSONObject {
        val o = JSONObject()
        val pkgs = listOf("com.byd.monitor", "com.sealnav", "br.com.rory.electro", "com.bydmate.app", "com.ggpark.byddashcam", "com.byd.avc")
        val inst = JSONArray()
        for (p in pkgs) try { ctx.packageManager.getPackageInfo(p, 0); inst.put(p) } catch (e: Throwable) {}
        o.put("installed", inst)
        try {
            val re = Regex("dvr|dashcam|sentry|avm|pano|camera", RegexOption.IGNORE_CASE)
            o.put("cameraLike", JSONArray(ctx.packageManager.getInstalledPackages(0).map { it.packageName }.filter { re.containsMatchIn(it) }.take(40)))
        } catch (e: Throwable) {}
        return o
    }

    private fun camera2(): JSONObject {
        val o = JSONObject()
        try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val arr = JSONArray()
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                val j = JSONObject().put("id", id)
                j.put("facing", ch.get(CameraCharacteristics.LENS_FACING) ?: -1)
                j.put("orientation", ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1)
                try {
                    val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    j.put("yuv", JSONArray(map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.take(6)?.map { "$it" } ?: emptyList<String>()))
                } catch (e: Throwable) {}
                arr.put(j)
            }
            o.put("ids", arr)
        } catch (e: Throwable) { o.put("error", BmmRuntime.rootCause(e)) }
        return o
    }

    private fun processName(): String = try { File("/proc/self/cmdline").readText().trim { it <= ' ' } } catch (e: Throwable) { "?" }
}
