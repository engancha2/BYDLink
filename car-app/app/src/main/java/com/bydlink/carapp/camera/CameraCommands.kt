package com.bydlink.carapp.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import com.bydlink.carapp.App
import com.bydlink.carapp.BuildConfigCompat
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Todo lo de cámara que ve MainService, en una clase (para que la mezcla con el resto sea mínima):
 * comandos del móvil, centinela, test automático una vez por versión y publicación de `cars/{id}/cameras`.
 *
 * Uso desde MainService:
 *   val cam = CameraCommands(ctx) { coche parado? }
 *   cam.setSentry(on, sensibilidad)                       // al arrancar y cuando cambia la config
 *   if (cam.dispatch(action, payload) { ok, data -> client.respond(id, ok, data) }) return
 *   cam.shutdown()                                         // en onDestroy
 * Contrato con el móvil: protocol/camara.md.
 */
class CameraCommands(ctx: Context, private val isParked: () -> Boolean) {

    companion object {
        val ACTIONS = setOf(
            "camTest", "runCamTest", "takePhoto", "snapshot",
            "startLiveJpeg", "switchLiveJpeg", "stopLiveJpeg",
            "startLiveCam", "switchLiveCam", "stopLiveCam",
            "getCamStatus", "setCamProfile", "camReset", "dumpCamLog",
            "recordVideo", "stopVideo", "listVideos", "deleteVideo", "keepVideo", "getVideo", "setVideoConfig", "getVideoStatus"
        )
        /** Lo más que se manda al móvil por la nube (un tramo de 3 min en calidad normal son ~30 MB). */
        private const val MAX_SEND = 60L * 1024 * 1024
        private const val CHUNK = 512 * 1024
    }

    private val app = ctx.applicationContext
    private val root: DatabaseReference = App.db.getReference("cars").child(BuildConfigCompat.carId(app))
    val hub = CameraHub(app, isParked)
    private val views = CamViews(hub)
    private val sched = Executors.newScheduledThreadPool(1) { r -> Thread(r, "cam-sched").apply { isDaemon = true } }
    private val exec = Executors.newFixedThreadPool(2) { r -> Thread(r, "cam-cmd").apply { isDaemon = true } }
    private val clips = ClipRecorder(app, hub, views)
    private val sentry = SentryMonitor(app, hub, views, root, sched, clips)
    private val remote = RemoteCam(app) { hub.parkedNow() }
    private val jpeg = LiveJpeg(hub, views, root, sched, remote)
    private val rtc = LiveStreamer(app, hub, views, sched)
    private val runner = CamTestRunner(app, hub, root) { onTestDone(it) }
    private val dashcam = Dashcam(app, hub, clips)
    private val sendExec = Executors.newSingleThreadExecutor { r -> Thread(r, "cam-video-send").apply { isDaemon = true } }
    private val screenExec = Executors.newSingleThreadExecutor { r -> Thread(r, "cam-screen").apply { isDaemon = true } }
    private val versionCode = BuildConfigCompat.versionCode(app)

    @Volatile private var started = false
    @Volatile private var publishQueued = false
    private var lastPublish = 0L
    private var parkedSince = 0L
    private var lastLogUpload = 0L
    private var lastLogChanges = -1L
    @Volatile private var videoQueued = false
    private var lastVideoPublish = 0L
    private var lastVideoList = ""
    @Volatile private var sending: String? = null
    private var screenTap: CameraHub.Tap? = null

    init {
        hub.onChange = { schedulePublish() }
        jpeg.onStop = { schedulePublish() }
        rtc.onStopped = { hub.release(CameraHub.LIVE); schedulePublish() }
        clips.onChange = { scheduleVideoPublish(); schedulePublish() }
    }

    // ---------------------------------------------------------------- API para MainService

    /** Centinela on/off y sensibilidad (1..10). También arranca el módulo la primera vez. */
    fun setSentry(on: Boolean, sensitivity: Int) {
        ensureStarted()
        sentry.configure(on && BmmRuntime.findJar() != null, sensitivity)
        schedulePublish()
    }

    /**
     * Si [action] es de cámara, la ejecuta en un hilo propio (sin bloquear el hilo principal) y llama a
     * [respond] con (ok, data). En error, data = {error: mensaje en español, code}. Devuelve false si no es suya.
     */
    fun dispatch(action: String, payload: Map<String, Any?>, respond: (Boolean, Map<String, Any?>) -> Unit): Boolean {
        if (action !in ACTIONS) return false
        exec.execute {
            try { respond(true, handle(action, payload) ?: emptyMap()) }
            catch (e: CamError) { CamLog.w("comando $action: ${e.message} [${e.code}]"); respond(false, mapOf("error" to e.message, "code" to e.code)) }
            catch (e: Throwable) { CamLog.e("comando $action", e); respond(false, mapOf("error" to (e.message ?: "Error de cámara"), "code" to "error")) }
        }
        return true
    }

    /**
     * Ejecuta un comando de cámara (bloqueante, hasta ~10 s). Devuelve null si no es de cámara;
     * lanza [CamError] (mensaje en español) si falla.
     */
    fun handle(action: String, p: Map<String, Any?>): Map<String, Any?>? {
        if (action !in ACTIONS) return null
        ensureStarted()
        return when (action) {
            "camTest", "runCamTest" -> {
                if (!hub.parkedNow()) throw CamError.notParked()
                if (jpeg.remoteMode) jpeg.stop("test")
                if (remote.busy()) throw CamError("busy", "La cámara se está usando; vuelve a intentarlo en unos segundos.")
                if (!runner.start("manual")) throw CamError("busy", "Ya hay un test de cámara en marcha.")
                schedulePublish()
                mapOf("started" to true, "etaSec" to 75, "path" to "diag/cam")
            }
            "takePhoto", "snapshot" -> takePhoto(p)
            "startLiveJpeg" -> startJpeg(p)
            "switchLiveJpeg" -> {
                val c = views.check(p["camera"] as? String)
                if (!jpeg.active) throw CamError("bad_args", "El directo foto a foto no está en marcha.")
                jpeg.switchTo(c); mapOf("camera" to c)
            }
            "stopLiveJpeg" -> { jpeg.stop("móvil"); emptyMap() }
            "startLiveCam" -> startRtc(p)
            "switchLiveCam" -> {
                val c = views.check(p["camera"] as? String)
                rtc.camera = c; views.want(c); jpeg.switchTo(c)
                schedulePublish(); mapOf("camera" to c)
            }
            "stopLiveCam" -> { rtc.stop("móvil"); hub.release(CameraHub.LIVE); emptyMap() }
            "getCamStatus" -> status(false)
            "setCamProfile" -> {
                val np = if (p["reset"] == true) CamProfile() else hub.profile.merged(p)
                hub.setProfile(np)
                CamLog.i("perfil de cámara cambiado a mano: ${np.toJson()}")
                mapOf("profile" to np.toMap())
            }
            "camReset" -> {
                CamPrefs.setDisabled(app, versionCode, null); CamPrefs.clearDeaths(app)
                if (p["profile"] == true) hub.setProfile(CamProfile())
                if (p["retest"] == true) CamPrefs.resetTest(app)
                hub.block = computeBlock(); hub.retryNow()
                schedulePublish()
                mapOf("block" to hub.block, "state" to hub.state)
            }
            "dumpCamLog" -> { uploadLog(); mapOf("path" to "diag/camlog") }
            "recordVideo" -> recordVideo(p)
            "stopVideo" -> { clips.stop(VideoStore.MANUAL); mapOf("stopped" to true) }
            "listVideos" -> mapOf("videos" to VideoStore.list(app, ((p["limit"] as? Number)?.toInt() ?: 100).coerceIn(1, 300)).map { it.toMap() })
            "deleteVideo" -> {
                val id = p["id"] as? String ?: throw CamError("bad_args", "Falta el vídeo.")
                if (!VideoStore.delete(app, id)) throw CamError("not_found", "Ese vídeo ya no está en el coche.")
                scheduleVideoPublish(); mapOf("deleted" to id)
            }
            "keepVideo" -> {
                val id = p["id"] as? String ?: throw CamError("bad_args", "Falta el vídeo.")
                val nid = VideoStore.keep(app, id) ?: throw CamError("not_found", "No se pudo guardar ese vídeo.")
                scheduleVideoPublish(); mapOf("id" to nid)
            }
            "getVideo" -> sendVideo(p)
            "setVideoConfig" -> { setVideoConfig(p); videoStatus(false) }
            "getVideoStatus" -> videoStatus(false)
            else -> null
        }
    }

    fun shutdown() {
        try { jpeg.stop("servicio parado") } catch (e: Throwable) {}
        try { rtc.stop("servicio parado") } catch (e: Throwable) {}
        try { sentry.configure(false, 5) } catch (e: Throwable) {}
        hub.shutdown()
        sched.shutdown(); exec.shutdown()
    }

    // ---------------------------------------------------------------- comandos

    private fun takePhoto(p: Map<String, Any?>): Map<String, Any?> {
        val mode = requireUsable(remoteOk = true)
        val cam = views.check(p["camera"] as? String)
        val maxW = ((p["w"] as? Number)?.toInt() ?: 640).coerceIn(160, 1280)
        val quality = ((p["quality"] as? Number)?.toInt() ?: 70).coerceIn(30, 90)
        if (mode == "remote") {
            val (jpg, w, h) = remote.photo(cam, maxW, quality, hub.profile)
            root.child("camera/photo").setValue(mapOf(
                "ts" to ServerValue.TIMESTAMP, "camera" to cam, "w" to w, "h" to h, "jpeg" to Yuv.b64(jpg), "bytes" to jpg.size, "remote" to true))
            CamLog.i("foto (proceso aparte) $cam ${w}x$h ${jpg.size / 1024} KB")
            return mapOf("camera" to cam, "w" to w, "h" to h, "bytes" to jpg.size, "path" to "camera/photo", "remote" to true)
        }
        views.want(cam)
        val done = CountDownLatch(1)
        val shot = AtomicReference<Rendered?>(null)
        val tap = object : CameraHub.Tap {
            override fun onFrame(f: CameraHub.Frame) {
                if (done.count == 0L || f.sinceFirstMs < 700) return
                val r = views.render(f, cam, maxW) ?: return
                if (shot.compareAndSet(null, r)) done.countDown()
            }
        }
        hub.addTap(tap); hub.acquire(CameraHub.PHOTO)
        try {
            val t0 = SystemClock.elapsedRealtime()
            while (!done.await(200, TimeUnit.MILLISECONDS)) {
                val el = SystemClock.elapsedRealtime() - t0
                if (el > 9_000 || hub.state == "not_parked" || hub.block != null) throw hubFailure()
                if ((hub.state == "error" || hub.state == "unavailable") && el > 1_200) throw hubFailure()
            }
            val r = shot.get() ?: throw hubFailure()
            val jpg = Yuv.jpeg(r.nv21, r.w, r.h, quality)
            root.child("camera/photo").setValue(mapOf(
                "ts" to ServerValue.TIMESTAMP, "camera" to cam, "w" to r.w, "h" to r.h, "jpeg" to Yuv.b64(jpg), "bytes" to jpg.size))
            CamLog.i("foto $cam ${r.w}x${r.h} ${jpg.size / 1024} KB")
            return mapOf("camera" to cam, "w" to r.w, "h" to r.h, "bytes" to jpg.size, "path" to "camera/photo")
        } finally {
            hub.removeTap(tap); hub.release(CameraHub.PHOTO)
        }
    }

    private fun startJpeg(p: Map<String, Any?>): Map<String, Any?> {
        val mode = requireUsable(remoteOk = true)
        val cam = views.check(p["camera"] as? String)
        val fps = (p["fps"] as? Number)?.toDouble() ?: 1.0
        if (mode == "remote") {
            jpeg.start(cam, fps, hub.profile)
            val t0 = SystemClock.elapsedRealtime()
            while (!jpeg.hasFrame()) {
                if (SystemClock.elapsedRealtime() - t0 > 10_000 || !jpeg.active) {
                    jpeg.stop("sin_frames")
                    throw if (!hub.parkedNow()) CamError.notParked() else CamError("no_frames", "No han llegado imágenes de la cámara a tiempo.")
                }
                Thread.sleep(200)
            }
        } else {
            jpeg.start(cam, fps)
            if (!hub.awaitFrames(8_000, 400)) { val e = hubFailure(); jpeg.stop("sin_frames"); throw e }
        }
        schedulePublish()
        return mapOf(
            "camera" to cam, "fps" to jpeg.fps, "path" to "camera/live", "viewerPath" to "camera/viewer",
            "heartbeatSec" to LiveJpeg.HEARTBEAT_MS / 1000, "maxSec" to LiveJpeg.MAX_MS / 1000,
            "views" to views.names(), "layout" to hub.geom?.toMap(), "remote" to (mode == "remote")
        )
    }

    private fun startRtc(p: Map<String, Any?>): Map<String, Any?> {
        requireUsable(remoteOk = false)
        val session = (p["session"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw CamError("bad_args", "Falta la sesión de vídeo (session).")
        val cam = views.check(p["camera"] as? String)
        views.want(cam)
        rtc.start(root.child("signaling").child(session), root.child("iceConfig"), session, cam)
        hub.acquire(CameraHub.LIVE)
        if (!hub.awaitFrames(7_000)) {
            val e = hubFailure()
            rtc.stop("sin_frames"); hub.release(CameraHub.LIVE)
            throw e
        }
        schedulePublish()
        return mapOf("camera" to cam, "session" to session, "views" to views.names(), "layout" to hub.geom?.toMap())
    }

    /**
     * Comprueba que se puede usar la cámara ahora. Devuelve "local" (proceso principal) o "remote"
     * (proceso aparte, solo si [remoteOk] y la cámara del proceso principal está desactivada por crash).
     */
    private fun requireUsable(remoteOk: Boolean): String {
        if (BmmRuntime.findJar() == null) throw CamError.noBmm()
        if (!hub.parkedNow()) throw CamError.notParked()
        when (hub.block) {
            null -> {}
            "testing" -> throw CamError.testing()
            "pending_test" -> {
                if (!remote.busy()) runner.start("primer uso")
                schedulePublish()
                throw CamError.testing()
            }
            "test_failed" -> {
                if (needTest(2 * 60_000L) && !remote.busy() && runner.start("petición del móvil")) { schedulePublish(); throw CamError.testing() }
                throw CamError("test_failed", "La prueba de la cámara no salió bien en este coche (${CamPrefs.testVerdict(app)}). " +
                    "Por seguridad no se usa hasta que una prueba salga bien: manda «camTest» para repetirla.")
            }
            "disabled" -> {
                if (remoteOk && CamPrefs.testOk(app, versionCode)) return "remote"
                throw CamError("disabled", "La cámara del servicio principal está desactivada en esta versión porque la app se cerró de golpe " +
                    "con ella abierta. Usa la foto o el directo foto a foto; «camReset» la vuelve a activar.")
            }
            else -> throw CamError(hub.block!!, "La cámara está bloqueada (${hub.block}).")
        }
        if (hub.state == "error" || hub.state == "unavailable" || hub.state == "retry") hub.retryNow()
        return "local"
    }

    private fun hubFailure(): CamError = when {
        hub.state == "not_parked" -> CamError.notParked()
        hub.block == "testing" -> CamError.testing()
        hub.block != null -> CamError(hub.block!!, "La cámara está bloqueada (${hub.block}).")
        hub.errorCode != null -> CamError(hub.errorCode!!, hub.lastError ?: "Error de cámara.")
        else -> CamError("no_frames", "No han llegado imágenes de la cámara a tiempo.")
    }

    // ---------------------------------------------------------------- arranque, test automático, publicación

    @Synchronized private fun ensureStarted() {
        if (started) return
        started = true
        // ¿Murió el proceso con la cámara abierta? 2 veces en < 2 min (o 3 en < 15 min) → desactivar en esta versión.
        CamPrefs.recordDeathOnStart(app, versionCode)?.let { d ->
            CamLog.w("el proceso murió con la cámara abierta (${d.size} vez/veces recientes en esta versión)")
            val loop2 = d.size >= 2 && d[d.size - 1] - d[d.size - 2] <= 120_000
            val loop3 = d.size >= 3 && d[d.size - 1] - d[d.size - 3] <= 15 * 60_000
            if ((loop2 || loop3) && CamPrefs.disabledFor(app, versionCode) == null) {
                CamPrefs.setDisabled(app, versionCode, "main_crash_loop")
                CamLog.e("cámara del proceso principal DESACTIVADA para la versión $versionCode (muertes: $d)")
                reportMainCrash(d)
            }
        }
        hub.block = computeBlock()
        CamLog.i("cámara: jar=${BmmRuntime.findJar()} perfil=${hub.profile.source} bloqueo=${hub.block} test=${CamPrefs.testVerdict(app)}@${CamPrefs.testVersion(app)} (versión $versionCode)")
        sched.scheduleWithFixedDelay({ try { periodic() } catch (e: Throwable) { CamLog.w("cámara: fallo periódico", e) } }, 5, 5, TimeUnit.SECONDS)
        sched.execute { try { dashcam.configure() } catch (e: Throwable) { CamLog.w("dashcam: no arranca", e) } }
        schedulePublish()
        scheduleVideoPublish()
    }

    /** Veredictos que no se repiten solos (los demás se reintentan, como mucho 5 veces por versión). */
    private val finalVerdicts = setOf("OK_FRAMES", "CRASHED", "NO_JAR", "JNI_FAIL", "NO_VALID_CAMERA")

    /** ¿Toca test? Siempre la primera vez con cada versión; si no salió bien, otra vez pasado [minGapMs]. */
    private fun needTest(minGapMs: Long): Boolean {
        if (CamPrefs.testVersion(app) != versionCode) return true
        val v = CamPrefs.testVerdict(app) ?: return true
        if (v in finalVerdicts) return false
        return CamPrefs.testRuns(app) < 5 && System.currentTimeMillis() - CamPrefs.testTs(app) > minGapMs
    }

    /**
     * La cámara del proceso principal SOLO se abre si el test en proceso aparte salió OK con esta versión
     * y no se ha desactivado por muertes del proceso con la cámara abierta.
     */
    private fun computeBlock(): String? = when {
        runner.running -> "testing"
        BmmRuntime.findJar() == null -> null                     // sin cámaras: el hub dirá "unavailable"
        CamPrefs.disabledFor(app, versionCode) != null -> "disabled"
        CamPrefs.testOk(app, versionCode) -> null
        CamPrefs.testVersion(app) != versionCode -> "pending_test"
        else -> "test_failed"
    }

    private fun reportMainCrash(deaths: List<Long>) {
        try {
            root.child("diag/cam/mainCrash").setValue(mapOf(
                "ts" to ServerValue.TIMESTAMP, "versionCode" to versionCode, "reason" to "main_crash_loop",
                "deaths" to deaths, "msg" to "El servicio principal murió con la cámara abierta varias veces seguidas: " +
                    "la cámara del proceso principal queda desactivada en esta versión (foto y directo JPEG pasan al proceso aparte)."))
        } catch (e: Throwable) { CamLog.w("no se pudo subir mainCrash", e) }
    }

    private fun periodic() {
        val now = SystemClock.elapsedRealtime()
        // Test automático: una vez por versión de la app, con el coche parado al menos 30 s.
        if (!runner.running && !remote.busy() && needTest(20 * 60_000L)) {
            if (hub.parkedNow()) { if (parkedSince == 0L) parkedSince = now } else parkedSince = 0
            if (parkedSince != 0L && now - parkedSince >= 30_000 && runner.start("auto (versión $versionCode)")) schedulePublish()
        }
        // Log de cámara a Firebase cada 10 s mientras se usa.
        if ((hub.isOpen() || runner.running) && CamLog.changes != lastLogChanges && now - lastLogUpload >= 10_000) uploadLog()
        if (hub.isOpen() && now - lastPublish > 60_000) schedulePublish()
        dashcam.tick()
        if (now - lastVideoPublish > (if (clips.active().isNotEmpty()) 60_000L else 300_000L)) scheduleVideoPublish()
    }

    // ---------------------------------------------------------------- vídeos (grabar, guardar, mandar al móvil)

    /** Grabación a mano: [p].seconds s (0 = hasta parar, como mucho 30 min) en el soporte elegido. */
    private fun recordVideo(p: Map<String, Any?>): Map<String, Any?> {
        if (!(hub.drivingOpen && hub.streaming())) requireUsable(remoteOk = false)
        val cam = views.check(p["camera"] as? String)
        val secs = ((p["seconds"] as? Number)?.toInt() ?: 60).let { if (it <= 0) 1800 else it }.coerceIn(5, 1800)
        if (clips.busy(VideoStore.MANUAL)) return mapOf("recording" to true, "already" to true)
        views.want(cam)
        val sp = VideoStore.prefs(app)
        val path = clips.record(cam, secs, VideoStore.MANUAL, ClipRecorder.Quality.of(sp.getString("quality", "normal")),
            CameraHub.REC, sp.getBoolean("stamp", true))
            ?: throw CamError("no_space", "No hay dónde guardar el vídeo (¿memoria llena o USB quitado?).")
        if (!hub.awaitFrames(9_000)) { clips.stop(VideoStore.MANUAL); throw hubFailure() }
        return mapOf("recording" to true, "camera" to cam, "seconds" to secs, "id" to VideoStore.idOf(app, File(path)),
            "store" to VideoStore.current(app)?.label)
    }

    /** Manda un vídeo al móvil por la nube, en trozos (`cars/{id}/videoDl`); el móvil lo junta y lo reproduce. */
    private fun sendVideo(p: Map<String, Any?>): Map<String, Any?> {
        val id = p["id"] as? String ?: throw CamError("bad_args", "Falta el vídeo.")
        val f = VideoStore.find(app, id) ?: throw CamError("not_found", "Ese vídeo ya no está en el coche.")
        val len = f.length()
        if (len > MAX_SEND) throw CamError("too_big", "Ese vídeo ocupa ${len / 1048576} MB: es mucho para mandarlo por datos (máximo 60 MB). " +
            "Puedes verlo en la pantalla del coche o copiarlo de la memoria.")
        if (sending != null) throw CamError("busy", "Ya se está mandando otro vídeo al móvil; espera a que termine.")
        val req = (p["req"] as? String)?.takeIf { it.isNotBlank() } ?: "v${System.currentTimeMillis()}"
        val n = ((len + CHUNK - 1) / CHUNK).toInt()
        val ref = root.child("videoDl")
        sending = req
        sendExec.execute {
            try {
                Tasks.await(ref.setValue(mapOf("req" to req, "id" to id, "bytes" to len, "n" to n, "done" to 0,
                    "state" to "uploading", "ts" to ServerValue.TIMESTAMP)), 30, TimeUnit.SECONDS)
                f.inputStream().use { inp ->
                    val buf = ByteArray(CHUNK)
                    for (i in 0 until n) {
                        var got = 0
                        while (got < CHUNK) { val r = inp.read(buf, got, CHUNK - got); if (r < 0) break; got += r }
                        Tasks.await(ref.child("parts").child("$i").setValue(Base64.encodeToString(buf, 0, got, Base64.NO_WRAP)), 120, TimeUnit.SECONDS)
                        ref.child("done").setValue(i + 1)
                    }
                }
                ref.child("state").setValue("ready")
                CamLog.i("vídeo: mandado $id al móvil (${len / 1024} KB en $n trozos)")
            } catch (e: Throwable) {
                CamLog.w("vídeo: no se pudo mandar $id", e)
                ref.updateChildren(mapOf("state" to "error", "error" to "No se pudo mandar el vídeo (¿sin cobertura?)."))
            } finally {
                sending = null
                // Si el móvil no lo borra, fuera a los 15 min (no dejar decenas de MB en la nube).
                sched.schedule({
                    ref.child("req").get().addOnSuccessListener { if (it.getValue(String::class.java) == req) ref.removeValue() }
                }, 15, TimeUnit.MINUTES)
            }
        }
        return mapOf("path" to "videoDl", "req" to req, "bytes" to len, "parts" to n)
    }

    private fun setVideoConfig(p: Map<String, Any?>) {
        (p["store"] as? String)?.let { VideoStore.select(app, it) }
        (p["quotaGb"] as? Number)?.let { VideoStore.setQuotaGb(app, it.toInt()) }
        val ed = VideoStore.prefs(app).edit()
        (p["mode"] as? String)?.takeIf { it in Dashcam.MODES }?.let { ed.putString("mode", it) }
        (p["quality"] as? String)?.takeIf { it == "normal" || it == "alta" }?.let { ed.putString("quality", it) }
        (p["stamp"] as? Boolean)?.let { ed.putBoolean("stamp", it) }
        (p["segMin"] as? Number)?.let { ed.putInt("segMin", it.toInt().coerceIn(1, 10)) }
        ed.commit()
        CamLog.i("vídeo: configuración $p")
        dashcam.configure()
        sched.execute {
            VideoStore.volumes(app, fresh = true)
            VideoStore.current(app)?.let { VideoStore.cleanup(app, it) }
            scheduleVideoPublish()
        }
    }

    /** Estado de los vídeos: soportes, el elegido, límite, dashcam y lo que se está grabando (`cars/{id}/video`). */
    fun videoStatus(serverTs: Boolean = true): Map<String, Any?> {
        val sp = VideoStore.prefs(app)
        val cur = VideoStore.current(app)
        return mapOf(
            "store" to VideoStore.selectedId(app), "current" to cur?.id, "missing" to VideoStore.missing(app),
            "quotaGb" to VideoStore.quotaGb(app), "quotaBytes" to cur?.let { VideoStore.quotaBytes(app, it) },
            "mode" to dashcam.mode, "quality" to (sp.getString("quality", "normal") ?: "normal"),
            "stamp" to sp.getBoolean("stamp", true), "segMin" to sp.getInt("segMin", 3),
            "volumes" to VideoStore.volumes(app).map { it.toMap(VideoStore.used(it)) },
            "recording" to clips.active().map { it.toMap() },
            "dashcam" to mapOf("holding" to dashcam.holding(), "recording" to dashcam.recording(), "driving" to hub.drivingOpen),
            "usable" to (hub.block == null && BmmRuntime.findJar() != null), "sending" to sending,
            "ts" to if (serverTs) ServerValue.TIMESTAMP else System.currentTimeMillis()
        )
    }

    fun recordings() = clips.active()

    private fun scheduleVideoPublish() {
        if (videoQueued) return
        videoQueued = true
        try {
            sched.schedule({
                videoQueued = false; lastVideoPublish = SystemClock.elapsedRealtime()
                try {
                    root.child("video").setValue(videoStatus(true))
                    val items = VideoStore.list(app, 150).map { it.toMap() }
                    val key = items.joinToString { "${it["id"]}:${it["bytes"]}" }
                    if (key != lastVideoList) {
                        lastVideoList = key
                        root.child("videos").setValue(mapOf("ts" to ServerValue.TIMESTAMP, "items" to items))
                    }
                } catch (e: Throwable) { CamLog.w("no se pudo publicar video", e) }
            }, 2, TimeUnit.SECONDS)
        } catch (e: Throwable) { videoQueued = false }
    }

    /**
     * Imagen en directo para la pantalla del coche (página Cámara del lanzador), unas 6 por segundo. null = parar.
     * Devuelve un texto si ahora no se puede (coche en marcha, cámara en prueba…).
     */
    @Synchronized fun screenPreview(cb: ((Bitmap) -> Unit)?): String? {
        screenTap?.let { hub.removeTap(it); hub.release(CameraHub.SCREEN) }
        screenTap = null
        if (cb == null) return null
        ensureStarted()
        try { if (!hub.drivingOpen) requireUsable(remoteOk = false) } catch (e: CamError) { return e.message }
        val cam = hub.profile.cams.keys.firstOrNull() ?: "back"
        val busy = AtomicBoolean(false)
        var last = 0L
        val t = object : CameraHub.Tap {
            override fun onFrame(f: CameraHub.Frame) {
                val now = SystemClock.elapsedRealtime()
                if (now - last < 150 || !busy.compareAndSet(false, true)) return
                val r = views.render(f, cam, 640)
                if (r == null) { busy.set(false); return }
                last = now
                screenExec.execute {
                    try { val jpg = Yuv.jpeg(r.nv21, r.w, r.h, 80); BitmapFactory.decodeByteArray(jpg, 0, jpg.size)?.let(cb) }
                    catch (e: Throwable) {} finally { busy.set(false) }
                }
            }
        }
        screenTap = t; hub.addTap(t); hub.acquire(CameraHub.SCREEN)
        return null
    }

    fun screenActive() = screenTap != null

    /** Cómo está la cámara, en palabras para la pantalla del coche ("" si se está viendo). */
    fun screenState(): String = when {
        hub.streaming() -> ""
        BmmRuntime.findJar() == null -> "Este coche no tiene el sistema de cámaras BYD."
        hub.state == "not_parked" -> "Por seguridad, la cámara solo se ve con el coche parado."
        hub.block == "testing" || hub.block == "pending_test" -> "La cámara se está comprobando (tarda un minuto con el coche parado)."
        hub.block != null -> "La cámara no está disponible ahora (${hub.block})."
        hub.state == "error" || hub.state == "unavailable" -> hub.lastError ?: "Error de cámara."
        else -> "Abriendo la cámara…"
    }

    private fun onTestDone(result: JSONObject) {
        val v = result.optString("verdict", "ERROR")
        CamPrefs.setTestDone(app, versionCode, v)      // por versionCode: solo OK_FRAMES deja abrir la cámara aquí
        if (v == "OK_FRAMES") {
            val prop = result.optJSONObject("proposed")
            if (prop != null && hub.profile.source != "manual") {
                val np = CamProfile.fromJson(prop)
                CamLog.i("test: perfil propuesto aplicado: ${np.toJson()}")
                hub.setProfile(np)
            }
        }
        hub.block = computeBlock()
        hub.retryNow()
        uploadLog()
        schedulePublish()
    }

    private fun uploadLog() {
        lastLogUpload = SystemClock.elapsedRealtime(); lastLogChanges = CamLog.changes
        root.child("diag/camlog").setValue(mapOf("ts" to ServerValue.TIMESTAMP, "text" to CamLog.text()))
    }

    private fun schedulePublish() {
        if (publishQueued) return
        publishQueued = true
        val wait = (1_500 - (SystemClock.elapsedRealtime() - lastPublish)).coerceIn(0, 1_500)
        try {
            sched.schedule({
                publishQueued = false; lastPublish = SystemClock.elapsedRealtime()
                try { root.child("cameras").setValue(status(true)) } catch (e: Throwable) { CamLog.w("no se pudo publicar cameras", e) }
            }, wait, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) { publishQueued = false }
    }

    /** Lo que se publica en `cars/{id}/cameras` (y lo que devuelve getCamStatus). */
    private fun status(serverTs: Boolean): Map<String, Any?> {
        val p = hub.profile
        val t = hub.target
        val names = views.names()
        val jar = BmmRuntime.findJar()
        return mapOf(
            "available" to (jar != null), "jar" to jar,
            "kind" to t?.kind, "tag" to t?.tag, "id" to t?.id, "api" to hub.openedApi,
            "state" to hub.state, "code" to hub.errorCode, "lastError" to hub.lastError,
            "parked" to (try { isParked() } catch (e: Throwable) { false }),
            "consumers" to hub.consumerList(), "fps" to Math.round(hub.fps * 10) / 10.0, "frames" to hub.frames,
            "firstFrame" to hub.firstFrame, "layout" to hub.geom?.toMap(),
            "views" to names, "mode" to if (p.channels) "channels" else "strip", "activeView" to hub.activeView,
            "map" to p.cams.mapValues { mapOf("view" to it.value.view, "tile" to it.value.tile) },
            "profile" to p.toMap(),
            "sentry" to mapOf("on" to sentry.on, "events" to sentry.events),
            "live" to mapOf("jpeg" to jpeg.active, "jpegCamera" to jpeg.camera, "webrtc" to rtc.running, "session" to rtc.session),
            "test" to mapOf("verdict" to CamPrefs.testVerdict(app), "versionCode" to CamPrefs.testVersion(app),
                "ts" to CamPrefs.testTs(app), "runs" to CamPrefs.testRuns(app), "running" to runner.running,
                "ok" to CamPrefs.testOk(app, versionCode)),
            "block" to hub.block, "disabled" to CamPrefs.disabledFor(app, versionCode),
            "deaths" to CamPrefs.deaths(app, versionCode), "remote" to remote.mode, "versionCode" to versionCode,
            "count" to names.size,
            "list" to names.map { mapOf("id" to it, "tag" to t?.tag, "name" to (CamProfile.ES[it] ?: it)) },
            "ts" to if (serverTs) ServerValue.TIMESTAMP else System.currentTimeMillis()
        )
    }
}
