package com.bydlink.carapp.camera

import android.os.SystemClock
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Directo "foto a foto" por Firebase (funciona en cualquier red que llegue a Firebase, sin TURN).
 * El coche SOBRESCRIBE `cars/{id}/camera/live` = {ts, seq, camera, w, h, jpeg} a 1–2 fps (~480×360).
 * Se para solo si el móvil no renueva `cars/{id}/camera/viewer` en 20 s, a los 3 min, o si el coche
 * deja de estar parado. Estado en `cars/{id}/camera/liveState`.
 * No acumula envíos: hasta que Firebase confirma un frame no se manda el siguiente.
 */
class LiveJpeg(
    private val hub: CameraHub,
    private val views: CamViews,
    private val root: DatabaseReference,
    private val sched: ScheduledExecutorService,
    private val remote: RemoteCam
) : CameraHub.Tap {

    companion object {
        const val HEARTBEAT_MS = 20_000L
        const val MAX_MS = 180_000L
    }

    @Volatile var active = false; private set
    @Volatile var camera = "front"; private set
    @Volatile var fps = 1.0; private set
    @Volatile private var lastBeat = 0L
    @Volatile private var deadline = 0L
    @Volatile private var lastSent = 0L
    @Volatile private var busy = false
    @Volatile private var busySince = 0L
    @Volatile private var seq = 0L
    @Volatile private var sent = 0L
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "cam-jpeg").apply { isDaemon = true } }
    private var watch: ScheduledFuture<*>? = null
    private var poll: ScheduledFuture<*>? = null
    /** true = los frames vienen del proceso aparte (cámara del proceso principal desactivada). */
    @Volatile var remoteMode = false; private set
    @Volatile private var remoteSeq = 0L
    @Volatile private var lastRemoteAt = 0L
    private var viewerL: ValueEventListener? = null
    var onStop: ((String) -> Unit)? = null

    /** Arranca o renueva (otra llamada mientras va = cambia cámara/fps y reinicia los 3 min). */
    @Synchronized fun start(cam: String, wantFps: Double, remoteProfile: CamProfile? = null) {
        if (active && remoteMode != (remoteProfile != null)) stop("cambio de modo")
        camera = cam
        fps = wantFps.coerceIn(0.2, 2.0)
        val now = SystemClock.elapsedRealtime()
        lastBeat = now; deadline = now + MAX_MS
        if (remoteProfile != null) remote.startLive(cam, fps, remoteProfile, 10 * 60_000L) else views.want(cam)
        if (!active) {
            active = true; busy = false; lastSent = 0
            remoteMode = remoteProfile != null
            if (remoteMode) {
                remoteSeq = 0; lastRemoteAt = now
                poll = sched.scheduleWithFixedDelay({ pollRemote() }, 250, 250, TimeUnit.MILLISECONDS)
            } else { hub.addTap(this); hub.acquire(CameraHub.JPEG) }
            val l = object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { lastBeat = SystemClock.elapsedRealtime() }
                override fun onCancelled(e: DatabaseError) {}
            }
            viewerL = l
            root.child("camera/viewer").addValueEventListener(l)
            watch = sched.scheduleWithFixedDelay({ check() }, 2, 2, TimeUnit.SECONDS)
            CamLog.i("directo JPEG: empieza ($cam, $fps fps)")
        }
        writeState("on", null)
    }

    @Synchronized fun switchTo(cam: String) {
        if (!active) return
        camera = cam; lastBeat = SystemClock.elapsedRealtime()
        if (remoteMode) remote.switchLive(cam) else views.want(cam)
        writeState("on", null)
    }

    @Synchronized fun stop(reason: String) {
        if (!active) return
        active = false
        if (remoteMode) { poll?.cancel(false); poll = null; remote.stopLive() }
        else { hub.removeTap(this); hub.release(CameraHub.JPEG) }
        viewerL?.let { root.child("camera/viewer").removeEventListener(it) }; viewerL = null
        watch?.cancel(false); watch = null
        CamLog.i("directo JPEG: parado ($reason, $sent frames)")
        writeState("off", reason)
        try { onStop?.invoke(reason) } catch (e: Throwable) {}
    }

    private fun check() {
        if (!active) return
        val now = SystemClock.elapsedRealtime()
        when {
            now - lastBeat > HEARTBEAT_MS -> stop("sin_latido")
            now > deadline -> stop("max_3min")
            remoteMode && !remote.busy() -> stop("not_parked")            // el vigilante mató el proceso
            remoteMode && now - lastRemoteAt > 15_000 -> stop("sin_frames")
            remoteMode -> remote.switchLive(camera)                       // latido para el proceso aparte
            !remoteMode && hub.state == "not_parked" -> stop("not_parked")
            !remoteMode && hub.block != null -> stop(hub.block ?: "bloqueada")
        }
    }

    /** ¿Ha llegado ya algún frame? (para responder al móvil con verdad). */
    fun hasFrame(): Boolean = if (remoteMode) remoteSeq > 0 || remote.latest(0) != null else sent > 0 || busy

    private fun pollRemote() {
        if (!active || !remoteMode) return
        val now = SystemClock.elapsedRealtime()
        if (busy) { if (now - busySince > 15_000) busy = false else return }
        val (meta, jpg) = remote.latest(remoteSeq) ?: return
        remoteSeq = meta.optLong("seq"); lastRemoteAt = now
        busy = true; busySince = now
        val s = ++seq
        root.child("camera/live").setValue(mapOf(
            "ts" to ServerValue.TIMESTAMP, "seq" to s, "camera" to meta.optString("camera", camera),
            "w" to meta.optInt("w"), "h" to meta.optInt("h"), "jpeg" to Yuv.b64(jpg), "remote" to true
        )).addOnCompleteListener { busy = false; sent++ }
    }

    private fun writeState(state: String, reason: String?) {
        root.child("camera/liveState").setValue(mapOf(
            "state" to state, "camera" to camera, "fps" to fps, "reason" to reason, "seq" to seq,
            "maxSec" to MAX_MS / 1000, "heartbeatSec" to HEARTBEAT_MS / 1000, "ts" to ServerValue.TIMESTAMP
        ))
    }

    override fun onFrame(f: CameraHub.Frame) {
        if (!active) return
        val now = SystemClock.elapsedRealtime()
        if (busy) { if (now - busySince > 15_000) busy = false else return }
        if (now - lastSent < (1000.0 / fps).toLong()) return
        if (f.sinceFirstMs < 400) return       // los primeros frames tras abrir salen oscuros
        val cam = camera
        val r = views.render(f, cam, 480) ?: return
        lastSent = now; busy = true; busySince = now
        val s = ++seq
        worker.execute {
            try {
                val jpg = Yuv.jpegMax(r.nv21, r.w, r.h, 45_000, 55)
                root.child("camera/live").setValue(mapOf(
                    "ts" to ServerValue.TIMESTAMP, "seq" to s, "camera" to cam, "w" to r.w, "h" to r.h, "jpeg" to Yuv.b64(jpg)
                )).addOnCompleteListener { busy = false; sent++ }
            } catch (e: Throwable) {
                busy = false
                CamLog.limited("jpeglive", 15_000, "directo JPEG: fallo al codificar/enviar: $e")
            }
        }
    }
}
