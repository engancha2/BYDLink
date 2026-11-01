package com.bydlink.carapp.camera

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.bydlink.carapp.car.BydPermissionContext
import com.bydlink.carapp.car.BydReflect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * DUEÑO ÚNICO del dispositivo de cámara BMM en este proceso (plan §3.0).
 *
 * - Consumidores con nombre (sentry, live, jpeg, photo, clip): abre si hay al menos uno y el coche
 *   está PARADO; cierra cuando no queda ninguno. Nunca con R o circulando: lo comprueba cada 300 ms
 *   (función `isParked` de MainService + lectura directa de marcha/velocidad del SDK BYD) y suelta
 *   la cámara al instante para no interferir con la cámara de marcha atrás / 360 del coche.
 * - Todo lo de BMM corre en el hilo "bmm-cam". Los frames llegan por un hilo nativo y se reparten a
 *   los [Tap], que tienen que ser rápidos (recortan/escalan lo suyo y siguen en su propio hilo).
 * - Ante errores/expropiación (eventos 1000/1002/1008/1010) cierra y reintenta con espera creciente (5 s → 60 s).
 * - Solo abre si el test en proceso aparte salió OK con esta versión (lo decide CameraCommands con [block]).
 * - Marca anti-crash: si el proceso muere con la cámara abierta se apunta; 2 veces en < 2 min la desactivan (ver [CamPrefs]).
 */
class CameraHub(private val ctx: Context, private val isParked: () -> Boolean) {

    interface Tap { fun onFrame(f: Frame) }

    /** Un frame. [data] es el buffer del jar: válido SOLO durante onFrame. [view] = vista activada. */
    class Frame(val data: ByteArray, val g: FrameGeom, val view: Int, val ts: Long, val seq: Long, val sinceFirstMs: Long)

    companion object {
        const val SENTRY = "sentry"; const val LIVE = "live"; const val JPEG = "jpeg"
        const val PHOTO = "photo"; const val CLIP = "clip"; const val REC = "rec"; const val SCREEN = "screen"
        /** La dashcam: el único consumidor que puede tener la cámara abierta en marcha (si [driveAllowed]). */
        const val DASHCAM = "dashcam"
        /**
         * En marcha: se abre a partir de 15 km/h y se suelta por debajo de 8, así está cerrada antes de poder meter la R
         * (el número de marcha del SDK aún no está calibrado en el Surf: no se usa para decidir; solo se mira que no sea R).
         */
        private const val DRIVE_OPEN_KMH = 15f
        private const val DRIVE_KEEP_KMH = 8f
        private const val TICK_MS = 300L
        private const val SENTRY_SETTLE_MS = 20_000L
    }

    private val ht = HandlerThread("bmm-cam").apply { start() }
    private val h = Handler(ht.looper)
    private val consumers = ConcurrentHashMap<String, Long>()
    private val taps = CopyOnWriteArrayList<Tap>()
    private val guard = GearGuard(ctx)
    private val frameLock = Object()

    @Volatile var profile: CamProfile = CamProfile.load(ctx); private set
    /** Si no es null la cámara no se abre (y se cierra): "testing" | "pending_test" | "test_failed" | "disabled". */
    @Volatile var block: String? = null
        set(v) { field = v; poke() }
    /** Se llama (desde cualquier hilo) cuando cambia algo publicable. */
    @Volatile var onChange: (() -> Unit)? = null

    @Volatile var state = "idle"; private set
    @Volatile var errorCode: String? = null; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var target: BmmInfo.Target? = null; private set
    @Volatile var openedApi: String? = null; private set
    @Volatile var geom: FrameGeom? = null; private set
    @Volatile var fps = 0.0; private set
    @Volatile var frames = 0L; private set
    @Volatile var firstFrame: String? = null; private set
    @Volatile var activeView = 0; private set
    /** Dashcam en marcha permitida por el usuario (experimental). */
    @Volatile var driveAllowed = false
        set(v) { field = v; poke() }
    /** La cámara está abierta en marcha (solo para la dashcam): el centinela no analiza. */
    @Volatile var drivingOpen = false; private set
    /** Vista que piden los consumidores (solo cuenta en modo canales; en modo tira es siempre la del perfil). */
    @Volatile var wantedView = 0
        set(v) { field = v; poke() }

    @Volatile private var cam: BmmCamera? = null
    private var reader: ImageReader? = null
    private var openedAt = 0L
    @Volatile private var firstFrameAt = 0L
    @Volatile private var lastFrameAt = 0L
    private var lastKickAt = 0L
    private var deathsCleared = false
    private var failures = 0
    private var nextOpenAt = 0L
    private var parkedSince = 0L
    private var fpsWinStart = 0L
    private var fpsWinFrames = 0
    @Volatile private var gKey: String? = null
    @Volatile private var gVal: FrameGeom? = null

    private val tickR = Runnable { tick() }

    // ---------------- API ----------------
    fun acquire(who: String) {
        if (consumers.put(who, SystemClock.elapsedRealtime()) == null) CamLog.i("consumidor +$who (${consumers.keys})")
        poke()
    }

    fun release(who: String) {
        if (consumers.remove(who) != null) { CamLog.i("consumidor -$who (${consumers.keys})"); poke() }
    }

    fun consumerList(): List<String> = consumers.keys.sorted()
    fun addTap(t: Tap) { if (!taps.contains(t)) taps.add(t) }
    fun removeTap(t: Tap) { taps.remove(t) }
    fun isOpen() = cam != null
    fun streaming() = cam != null && firstFrameAt != 0L && SystemClock.elapsedRealtime() - lastFrameAt < 3000

    /** ¿Coche parado? (P/0 km/h según MainService y, si se puede leer, según el SDK ahora mismo). */
    fun parkedNow(): Boolean = (try { isParked() } catch (e: Throwable) { false }) && guard.ok()

    fun setProfile(p: CamProfile) {
        profile = p; CamProfile.save(ctx, p)
        h.post { gKey = null; if (cam != null) close("perfil nuevo"); failures = 0; nextOpenAt = 0; tick() }
        onChange?.invoke()
    }

    fun retryNow() = h.post { failures = 0; nextOpenAt = 0; tick() }

    /**
     * Espera a que lleguen frames (máx. [timeoutMs]). Devuelve true si llegan; false si no, o si el hub
     * ya sabe que no puede (no parado, bloqueado, error tras abrir).
     */
    fun awaitFrames(timeoutMs: Long, minSinceFirstMs: Long = 0): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        poke()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            val now = SystemClock.elapsedRealtime()
            if (cam != null && firstFrameAt != 0L && now - lastFrameAt < 1500 && now - firstFrameAt >= minSinceFirstMs) return true
            if (state == "not_parked" || block != null) return false
            if ((state == "error" || state == "unavailable") && now - t0 > 1500) return false
            synchronized(frameLock) { frameLock.wait(150) }
        }
        return false
    }

    /** Espera a que la cámara quede cerrada (p. ej. antes del test en otro proceso). */
    fun awaitClosed(timeoutMs: Long): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        poke()
        while (cam != null && SystemClock.elapsedRealtime() - t0 < timeoutMs) Thread.sleep(100)
        return cam == null
    }

    fun shutdown() {
        h.post { close("servicio parado"); ht.quitSafely() }
    }

    fun poke() { h.removeCallbacks(tickR); h.post(tickR) }

    // ---------------- Bucle (hilo bmm-cam) ----------------
    private fun tick() {
        h.removeCallbacks(tickR)
        val want = consumers.isNotEmpty()
        val c = cam
        val now = SystemClock.elapsedRealtime()
        if (want || c != null) {
            val svcParked = try { isParked() } catch (e: Throwable) { false }
            // Marcha y velocidad del SDK: si el servicio dice parado (confirmar) o si la dashcam en marcha las necesita.
            val needSdk = svcParked || (driveAllowed && consumers.containsKey(DASHCAM))
            val parkedNow = needSdk && guard.ok() && svcParked
            if (!parkedNow) parkedSince = 0 else if (parkedSince == 0L) parkedSince = now
            // En marcha solo con la dashcam pedida y permitida, en D y con velocidad (nunca en R ni casi parado).
            val drive = !parkedNow && driveAllowed && consumers.containsKey(DASHCAM) &&
                com.bydlink.carapp.service.StateHub.state?.gear != "R" &&
                (guard.lastSpeed ?: 0f) >= (if (drivingOpen) DRIVE_KEEP_KMH else DRIVE_OPEN_KMH)
            val parked = parkedNow || drive
            drivingOpen = drive
            val blk = block
            if (c != null) {
                when {
                    !parked -> { close("el coche no está parado (marcha ${guard.lastGear}, ${guard.lastSpeed} km/h)"); setState("not_parked") }
                    blk != null -> { close("bloqueada: $blk"); setState(blk) }
                    !want -> { close("sin consumidores"); setState("idle") }
                    else -> { watchdog(c, now); applyView(c) }
                }
            } else {
                when {
                    !parked -> setState("not_parked")
                    blk != null -> setState(blk)
                    // Solo centinela/dashcam: esperar 20 s aparcado (la cámara del coche suele seguir encendida al aparcar).
                    parkedNow && consumers.keys.all { it == SENTRY || it == DASHCAM } && now - parkedSince < SENTRY_SETTLE_MS -> setState("settling")
                    now >= nextOpenAt -> open()
                    else -> if (state != "error" && state != "unavailable") setState("retry")
                }
            }
        } else if (state != "idle" && state != "unavailable") setState("idle")
        if (consumers.isNotEmpty() || cam != null) h.postDelayed(tickR, TICK_MS)
    }

    private fun open() {
        val p = profile
        setState("opening")
        try {
            if (BmmRuntime.findJar() == null) throw CamError.noBmm()
            CamPrefs.markCamOpen(ctx, true)          // desde aquí puede petar código nativo
            val jni = BmmRuntime.loadJni(ctx)
            if (jni != "ok") throw CamError("jni", "No se pudo cargar la librería de cámara del coche ($jni).")
            val t = BmmInfo.resolve(ctx, p); target = t
            val t0 = SystemClock.elapsedRealtime()
            val c = when (p.api) {
                "avm", "normal" -> BmmCamera.open(ctx, p.api, t.id)
                else -> try { BmmCamera.open(ctx, "avm", t.id) }
                        catch (e: CamError) { if (e.code == "open_refused") BmmCamera.open(ctx, "normal", t.id) else throw e }
            }
            openedAt = SystemClock.elapsedRealtime(); firstFrameAt = 0; lastFrameAt = 0; lastKickAt = 0; deathsCleared = false
            gKey = null; errorCode = null; lastError = null
            cam = c; openedApi = c.api
            CamLog.i("abierta ${c.api} id=${t.id} tag=${t.tag} (${SystemClock.elapsedRealtime() - t0} ms)")
            c.setCallbacks(listener)
            if (p.previewW > 0 && p.previewH > 0) CamLog.i("setPreviewSize(${p.previewW},${p.previewH}) = ${c.setPreviewSize(p.previewW, p.previewH)}")
            CamLog.i("tamaño de preview que dice el jar: ${c.previewW()}x${c.previewH()}")
            activeView = if (!c.isAvm) 0 else if (p.channels && p.cams.values.any { it.view == wantedView }) wantedView
                         else p.cams.values.firstOrNull()?.view ?: 0
            CamLog.i("enablePreviewCallback($activeView) = ${c.enable(activeView)}")
            if (p.surface) attachSurface(c)
            CamLog.i("startPreview = ${c.start()}")
            if (p.restart) CamLog.i("reinicio stop/start = ${c.stop()}/${c.start()}")
            openedAt = SystemClock.elapsedRealtime()
            if (firstFrameAt == 0L) setState("starting")
        } catch (e: CamError) {
            fail(e.code, e.message ?: e.code)
        } catch (e: Throwable) {
            val root = BmmRuntime.rootCause(e)
            val code = if (e is ExceptionInInitializerError || e is UnsatisfiedLinkError || e is NoClassDefFoundError) "jni" else "error"
            fail(code, "Fallo al abrir la cámara: $root")
        }
    }

    private fun attachSurface(c: BmmCamera) {
        try {
            val r = ImageReader.newInstance(maxOf(c.previewW() ?: 1280, 2), maxOf(c.previewH() ?: 960, 2), PixelFormat.RGBA_8888, 2)
            r.setOnImageAvailableListener({ rr -> try { rr.acquireLatestImage()?.close() } catch (e: Throwable) {} }, h)
            reader = r
            CamLog.i("Surface índice 0: add=${c.addSurface(r.surface, 0)} set=${c.setSurface(r.surface, 0)}")
        } catch (e: Throwable) { CamLog.w("no se pudo enganchar la Surface", e) }
    }

    private fun watchdog(c: BmmCamera, now: Long) {
        if (firstFrameAt == 0L) {
            val since = now - openedAt
            if (since > 4_000 && lastKickAt == 0L) kick(c, now, "no llega el primer frame")
            if (since > 11_000) fail("no_frames", "La cámara se abrió pero no llegan imágenes.")
        } else {
            val gap = now - lastFrameAt
            if (gap > 4_000 && lastKickAt < lastFrameAt) kick(c, now, "se han parado los frames")
            if (gap > 11_000) fail("no_frames", "Se han dejado de recibir imágenes de la cámara.")
            if (now - firstFrameAt > 60_000) failures = 0
            if (now - firstFrameAt > 600_000 && !deathsCleared) { deathsCleared = true; CamPrefs.clearDeaths(ctx) }   // 10 min sana
        }
    }

    private fun kick(c: BmmCamera, now: Long, why: String) {
        lastKickAt = now
        CamLog.w("$why: stopPreview/startPreview")
        try { c.stop(); c.start() } catch (e: Throwable) { CamLog.w("reinicio de preview falló", e) }
    }

    private fun applyView(c: BmmCamera) {
        if (!c.isAvm || !profile.channels) return
        val v = wantedView
        if (v == activeView || profile.cams.values.none { it.view == v }) return
        try {
            CamLog.i("cambio de vista $activeView -> $v: disable=${c.disable(activeView)} enable=${c.enable(v)}")
            activeView = v; gKey = null
            if (profile.restart) { c.stop(); c.start() }
            onChange?.invoke()
        } catch (e: Throwable) { CamLog.w("no se pudo cambiar de vista", e) }
    }

    private fun onEvent(type: Int, a1: Int, a2: Int) {
        CamLog.i("evento BMM $type ($a1, $a2)")
        when (type) {
            1002, 1010 -> fail("error", "El servicio de cámara del coche se ha reiniciado (evento $type).")
            1000, 1007 -> if (!streaming()) fail("error", "La cámara del coche ha dado un error (evento $type).")
            1008 -> fail("busy", "El coche ha reclamado la cámara (la está usando su propia app).", minDelay = 20_000)
            1006 -> fail("no_permission", "El coche no da permiso de cámara a BYDLink (evento 1006).", minDelay = 60_000)
            1009 -> if (cam == null) nextOpenAt = minOf(nextOpenAt, SystemClock.elapsedRealtime() + 1000)
        }
    }

    private fun fail(code: String, msg: String, minDelay: Long = 0) {
        close("error: $code")
        CamPrefs.markCamOpen(ctx, false)
        failures++
        val delay = when (code) {
            "no_bmm", "jni" -> 10 * 60_000L
            else -> maxOf(minDelay, minOf(60_000L, 5_000L shl (failures - 1).coerceIn(0, 4)))
        }
        nextOpenAt = SystemClock.elapsedRealtime() + delay
        errorCode = code; lastError = msg
        CamLog.w("cámara: $msg [$code] (reintento en ${delay / 1000} s)")
        setState(if (code == "no_bmm") "unavailable" else "error")
    }

    private fun close(reason: String) {
        val c = cam ?: return
        cam = null
        CamLog.i("cerrando cámara: $reason")
        val errs = c.closeFully(activeView, reader?.surface)
        if (errs.isNotEmpty()) CamLog.w("al cerrar: $errs")
        try { reader?.close() } catch (e: Throwable) {}
        reader = null
        geom = null; fps = 0.0; firstFrameAt = 0
        CamPrefs.markCamOpen(ctx, false)
        onChange?.invoke()
    }

    private fun setState(s: String) {
        if (state == s) return
        state = s
        onChange?.invoke()
    }

    // ---------------- Frames (hilo nativo) ----------------
    private val onFirst = Runnable { setState("streaming") }

    private val listener = object : BmmCamera.Listener {
        override fun onPreview(data: ByteArray?, w: Int, h: Int, fmt: Int, size: Int, view: Int, ts: Long) {
            if (data == null || cam == null) return
            val now = SystemClock.elapsedRealtime()
            frames++
            lastFrameAt = now
            if (fpsWinStart == 0L) fpsWinStart = now
            fpsWinFrames++
            if (now - fpsWinStart >= 10_000) {
                fps = fpsWinFrames * 1000.0 / (now - fpsWinStart)
                fpsWinStart = now; fpsWinFrames = 0
            }
            val n = if (size in 1..data.size) size else data.size
            val g = geomFor(w, h, fmt, n)
            if (firstFrameAt == 0L) {
                firstFrameAt = now
                firstFrame = "w=$w h=$h fmt=$fmt dataSize=$size bufLen=${data.size} view=$view ts=$ts -> ${g ?: "NO interpretable"}"
                CamLog.i("primer frame: $firstFrame (${now - openedAt} ms)")
                this@CameraHub.h.post(onFirst)
            }
            if (g == null) { CamLog.limited("geom", 30_000, "frame no interpretable: w=$w h=$h fmt=$fmt n=$n"); return }
            geom = g
            val f = Frame(data, g, activeView, ts, frames, now - firstFrameAt)
            for (t in taps) {
                try { t.onFrame(f) } catch (e: Throwable) { CamLog.limited("tap", 10_000, "fallo en un consumidor de frames: $e") }
            }
            synchronized(frameLock) { frameLock.notifyAll() }
        }

        override fun onEvent(type: Int, a1: Int, a2: Int) {
            if (type != 1001) h.post { this@CameraHub.onEvent(type, a1, a2) }
        }
    }

    private fun geomFor(w: Int, h: Int, fmt: Int, n: Int): FrameGeom? {
        val p = profile
        val key = "$w/$h/$fmt/$n/${p.grid}/${p.nv12}"
        if (key == gKey) return gVal
        val g = FrameGeom.deduce(w, h, fmt, n, p.grid, p.nv12)
        gVal = g; gKey = key
        CamLog.i("geometría: $key -> $g")
        return g
    }

    /** Lectura directa de marcha y velocidad (SDK BYD) para soltar la cámara sin esperar a la telemetría. */
    private class GearGuard(ctx: Context) {
        private val pctx by lazy { BydPermissionContext(ctx.applicationContext) }
        private val gearbox by lazy { BydReflect.device(pctx, "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice") }
        private val speed by lazy { BydReflect.device(pctx, "android.hardware.bydauto.speed.BYDAutoSpeedDevice") }
        @Volatile var lastGear: Int? = null; private set
        @Volatile var lastSpeed: Float? = null; private set

        /** false solo si el SDK dice claramente D(1)/N(2)/R(4) o velocidad ≥ 1 km/h (mismo criterio que BydGateway). */
        fun ok(): Boolean {
            val g = try { BydReflect.callInt(gearbox, "getCurrentGear") } catch (e: Throwable) { null }
            val s = try { BydReflect.callFloat(speed, "getCurrentSpeed") } catch (e: Throwable) { null }?.takeIf { it in 0f..400f }
            lastGear = g; lastSpeed = s
            return g !in setOf(1, 2, 4) && (s == null || s < 1f)
        }
    }
}

/**
 * Estado persistente de la cámara (solo lo usa el proceso principal):
 *  - marca "cámara abierta": si el proceso muere con ella puesta, al volver se apunta una muerte;
 *    dos muertes seguidas en menos de 2 min (misma versión) desactivan la cámara para esta versión;
 *  - resultado del test por versionCode (la cámara del proceso principal solo se abre si es OK_FRAMES).
 */
object CamPrefs {
    private fun sp(ctx: Context) = CamProfile.prefs(ctx)
    @Volatile private var openMark: Boolean? = null

    /** commit() (síncrono): tiene que estar en disco ANTES de la llamada nativa que podría tumbar el proceso. */
    fun markCamOpen(ctx: Context, open: Boolean) {
        if (openMark == open) return
        openMark = open
        val e = sp(ctx).edit()
        if (open) e.putLong("camOpenSince", System.currentTimeMillis()) else e.remove("camOpenSince")
        e.commit()
    }

    /**
     * Al arrancar el proceso: si quedó la marca, murió con la cámara abierta. Apunta la muerte y devuelve
     * las muertes recientes de esta versión (ms), o null si no hubo.
     */
    fun recordDeathOnStart(ctx: Context, version: Long): List<Long>? {
        val s = sp(ctx)
        if (!s.contains("camOpenSince")) return null
        val list = deaths(ctx, version).toMutableList()
        list += System.currentTimeMillis()
        while (list.size > 5) list.removeAt(0)
        s.edit().remove("camOpenSince").putLong("deathsVersion", version)
            .putString("deaths", list.joinToString(",")).commit()
        openMark = false
        return list
    }

    fun deaths(ctx: Context, version: Long): List<Long> {
        val s = sp(ctx)
        if (s.getLong("deathsVersion", -1) != version) return emptyList()
        return s.getString("deaths", "")!!.split(",").mapNotNull { it.toLongOrNull() }
    }

    fun clearDeaths(ctx: Context) {
        val s = sp(ctx)
        if (s.contains("deaths")) s.edit().remove("deaths").apply()
    }

    /** Motivo de desactivación de la cámara del proceso principal para ESTA versión (o null). */
    fun disabledFor(ctx: Context, version: Long): String? {
        val s = sp(ctx)
        return if (s.getLong("disabledVersion", -1) == version) s.getString("disabled", null) else null
    }
    fun setDisabled(ctx: Context, version: Long, why: String?) {
        val e = sp(ctx).edit()
        if (why == null) e.remove("disabled").remove("disabledVersion")
        else e.putString("disabled", why).putLong("disabledVersion", version)
        e.commit()
    }

    fun testVersion(ctx: Context) = sp(ctx).getLong("testVersion", -1)
    fun testVerdict(ctx: Context) = sp(ctx).getString("testVerdict", null)
    fun testTs(ctx: Context) = sp(ctx).getLong("testTs", 0)
    /** Nº de tests hechos con esta versión (para no repetir sin fin). */
    fun testRuns(ctx: Context) = sp(ctx).getInt("testRuns", 0)
    fun testOk(ctx: Context, version: Long) = testVersion(ctx) == version && testVerdict(ctx) == "OK_FRAMES"
    fun setTestDone(ctx: Context, version: Long, verdict: String) {
        val runs = if (testVersion(ctx) == version) testRuns(ctx) + 1 else 1
        sp(ctx).edit().putLong("testVersion", version).putString("testVerdict", verdict)
            .putLong("testTs", System.currentTimeMillis()).putInt("testRuns", runs).commit()
    }
    fun resetTest(ctx: Context) = sp(ctx).edit().putLong("testVersion", -1).putInt("testRuns", 0).commit()
}
