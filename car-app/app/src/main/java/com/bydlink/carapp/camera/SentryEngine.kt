package com.bydlink.carapp.camera

import android.content.Context
import android.os.SystemClock
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow

/**
 * Detección de movimiento por cámara (plan §3.6), en Kotlin puro y ligera:
 *  - entrada: luminancia reducida 160×120 de cada cámara (ya recortada en el callback, sin copiar el frame);
 *  - bloques de 8×8 (20×15), fondo con media móvil y ruido estimado por bloque;
 *  - dispara si cambia una proporción de bloques que depende de la sensibilidad (6 % … 0,5 %) en 2 de 3 análisis;
 *  - si cambia más de la mitad del cuadro (faros, nubes, la pantalla se apaga) se reinicia el fondo y no dispara.
 */
class SentryEngine {
    @Volatile var sensitivity = 5

    private class St(n: Int) {
        val bg = FloatArray(n); val noise = FloatArray(n) { 2f }
        var frames = 0; var lastTs = 0L
        val hist = BooleanArray(3); var hi = 0
    }

    private val st = HashMap<String, St>()

    fun reset() = synchronized(st) { st.clear() }

    /** Devuelve la puntuación (fracción de bloques cambiados) si hay que avisar; null si no. */
    fun process(cam: String, y: ByteArray, w: Int, h: Int, ts: Long): Float? = synchronized(st) {
        val bw = w / 8; val bh = h / 8; val n = bw * bh
        val m = FloatArray(n)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var sum = 0
            val x0 = bx * 8; val y0 = by * 8
            for (yy in y0 until y0 + 8) { val row = yy * w; for (xx in x0 until x0 + 8) sum += y[row + xx].toInt() and 255 }
            m[by * bw + bx] = sum / 64f
        }
        val s = st[cam]
        if (s == null || s.bg.size != n || ts - s.lastTs > 3000) {
            st[cam] = St(n).also { System.arraycopy(m, 0, it.bg, 0, n); it.lastTs = ts; it.frames = 1 }
            return@synchronized null
        }
        s.lastTs = ts; s.frames++
        val sens = sensitivity.coerceIn(1, 10)
        val areaFrac = 0.06 * (0.5 / 6.0).pow((sens - 1) / 9.0)
        val k = 3.6f - 0.16f * sens
        val minAbs = 16f - sens
        var changed = 0
        var meanM = 0f; var meanB = 0f
        val ch = BooleanArray(n)
        for (i in 0 until n) {
            val d = abs(m[i] - s.bg[i])
            meanM += m[i]; meanB += s.bg[i]
            if (d > maxOf(minAbs, k * s.noise[i])) { ch[i] = true; changed++ }
        }
        val frac = changed.toFloat() / n
        // Cambio global de luz: reiniciar el fondo.
        if (frac > 0.5f || abs(meanM - meanB) / n > 25f) {
            System.arraycopy(m, 0, s.bg, 0, n); s.hist.fill(false)
            return@synchronized null
        }
        for (i in 0 until n) {
            val d = abs(m[i] - s.bg[i])
            if (ch[i]) s.bg[i] += 0.02f * (m[i] - s.bg[i])
            else { s.bg[i] += 0.05f * (m[i] - s.bg[i]); s.noise[i] = maxOf(1.5f, s.noise[i] * 0.95f + d * 0.05f) }
        }
        val cand = frac >= areaFrac && changed >= 2
        s.hist[s.hi] = cand; s.hi = (s.hi + 1) % 3
        if (s.frames < 8 || !cand) return@synchronized null    // calentando o sin movimiento
        if (s.hist.count { it } >= 2) { s.hist.fill(false); frac } else null
    }
}

/**
 * Centinela con la cámara: consumidor "sentry" del hub (solo abre con el coche parado).
 * En cada evento escribe la miniatura en `cars/{id}/sentryThumbs/{key}` = {ts, camera, jpeg} y el evento,
 * ligero, en `cars/{id}/sentryEvents/{key}` = {ts, camera, videoId, hasThumb, score, clip?} con la MISMA key.
 * Conserva solo las últimas 50 miniaturas. Espera mínima de 30 s entre eventos y como mucho 20 por hora.
 */
class SentryMonitor(
    private val ctx: Context,
    private val hub: CameraHub,
    private val views: CamViews,
    private val root: DatabaseReference,
    private val sched: ScheduledExecutorService,
    private val clips: ClipRecorder?
) : CameraHub.Tap {

    companion object {
        const val GW = 160; const val GH = 120
        const val COOLDOWN_MS = 30_000L
        const val MAX_PER_HOUR = 20
        const val KEEP_THUMBS = 50
    }

    private val engine = SentryEngine()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "cam-sentry").apply { isDaemon = true } }
    @Volatile var on = false; private set
    @Volatile var clipsOn = true
    @Volatile private var analyzing = false
    @Volatile private var lastAnalysis = 0L
    @Volatile private var pending: Pending? = null
    private var lastEventAt = 0L
    private val recent = ArrayDeque<Long>()
    @Volatile var events = 0; private set

    private class Pending(val cam: String, val score: Float, val clip: String?, val at: Long)

    @Synchronized fun configure(enable: Boolean, sensitivity: Int) {
        engine.sensitivity = sensitivity
        if (enable == on) return
        on = enable
        if (enable) { engine.reset(); hub.addTap(this); hub.acquire(CameraHub.SENTRY); CamLog.i("centinela: activado (sensibilidad $sensitivity)") }
        else { hub.removeTap(this); hub.release(CameraHub.SENTRY); CamLog.i("centinela: desactivado") }
    }

    override fun onFrame(f: CameraHub.Frame) {
        if (!on || hub.drivingOpen) return          // con la dashcam en marcha no se vigila (todo se "mueve")
        val now = SystemClock.elapsedRealtime()
        pending?.let { p ->
            val r = views.render(f, p.cam, 320)
            if (r != null) { pending = null; worker.execute { emit(p, r) } }
        }
        if (analyzing || now - lastAnalysis < 250 || f.sinceFirstMs < 1500) return
        lastAnalysis = now
        val p = hub.profile; val g = f.g
        val work = ArrayList<Pair<String, ByteArray>>(4)
        val seenTiles = HashSet<Int>()
        for ((name, ref) in p.cams) {
            if (ref.view != f.view || !g.validTile(ref.tile) || !seenTiles.add(ref.tile)) continue
            val buf = ByteArray(GW * GH)
            Yuv.lumaGrid(f.data, g, g.tileX(ref.tile), g.tileY(ref.tile), g.tileW, g.tileH, GW, GH, buf)
            work += name to buf
        }
        if (work.isEmpty()) return
        analyzing = true
        worker.execute {
            try {
                for ((name, buf) in work) {
                    val score = engine.process(name, buf, GW, GH, now) ?: continue
                    onMotion(name, score); break
                }
            } catch (e: Throwable) { CamLog.limited("sentry", 30_000, "centinela: fallo en el análisis: $e") }
            finally { analyzing = false }
        }
    }

    private fun onMotion(cam: String, score: Float) {
        val now = SystemClock.elapsedRealtime()
        while (recent.isNotEmpty() && now - recent.first() > 3_600_000) recent.removeFirst()
        if (now - lastEventAt < COOLDOWN_MS || recent.size >= MAX_PER_HOUR || pending != null) return
        lastEventAt = now; recent.addLast(now)
        CamLog.i("centinela: movimiento en $cam (score ${"%.3f".format(score)})")
        val clip = if (clipsOn) clips?.record(cam, 15) else null
        val p = Pending(cam, score, clip, now)
        pending = p
        // Si en 3 s no llega un frame para la miniatura (p. ej. se cerró la cámara), se avisa sin ella.
        sched.schedule({ if (pending === p) { pending = null; worker.execute { emit(p, null) } } }, 3, TimeUnit.SECONDS)
    }

    private fun emit(p: Pending, r: Rendered?) {
        try {
            val ref = root.child("sentryEvents").push()
            val key = ref.key ?: return
            var hasThumb = false
            if (r != null) {
                val jpg = Yuv.jpegMax(r.nv21, r.w, r.h, 14_500, 60)
                root.child("sentryThumbs").child(key).setValue(mapOf(
                    "ts" to ServerValue.TIMESTAMP, "camera" to p.cam, "w" to r.w, "h" to r.h, "jpeg" to Yuv.b64(jpg)))
                hasThumb = true
                prune(key)
            }
            val ev = linkedMapOf<String, Any?>(
                "ts" to ServerValue.TIMESTAMP, "camera" to p.cam,
                "videoId" to (p.clip?.let { VideoStore.idOf(ctx, java.io.File(it)) } ?: "v${System.currentTimeMillis()}"),
                "hasThumb" to hasThumb, "score" to Math.round(p.score * 1000) / 1000.0
            )
            p.clip?.let { ev["clip"] = it }
            ref.setValue(ev)
            events++
        } catch (e: Throwable) { CamLog.w("centinela: no se pudo publicar el evento", e) }
    }

    /** Guarda las keys en preferencias y borra de Firebase las miniaturas que pasen de 50. */
    private fun prune(newKey: String) {
        val sp = CamProfile.prefs(ctx)
        val arr = try { JSONArray(sp.getString("thumbKeys", "[]")) } catch (e: Throwable) { JSONArray() }
        val keys = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        keys += newKey
        while (keys.size > KEEP_THUMBS) {
            val old = keys.removeAt(0)
            root.child("sentryThumbs").child(old).removeValue()
        }
        sp.edit().putString("thumbKeys", JSONArray(keys).toString()).apply()
    }
}
