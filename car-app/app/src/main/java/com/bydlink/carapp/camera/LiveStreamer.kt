package com.bydlink.carapp.camera

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import org.webrtc.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Vídeo en directo coche→móvil por WebRTC. El COCHE es el que responde: el móvil deja su `offer` en
 * `cars/{id}/signaling/{session}` y aquí se contesta con una pista de vídeo hecha con los frames de la cámara.
 *
 * Arreglos del plan (S5, S6, P7): cada frame se recorta y escala DENTRO del callback (640×480, ≤15 fps) a un
 * buffer propio, así no hay tearing por el buffer reutilizado del jar ni anchos de 5120 px; cola de
 * candidatos hasta tener la descripción remota; listeners que se quitan al parar; `onCapturerStarted`;
 * límite de bitrate; TURN opcional desde `cars/{id}/iceConfig`; estado ICE/PC en `signaling/{session}/car`.
 * Se para solo si no conecta en 30 s, si se cae 15 s, a los 10 min o si el coche deja de estar parado.
 */
class LiveStreamer(
    private val ctx: Context,
    private val hub: CameraHub,
    private val views: CamViews,
    private val sched: ScheduledExecutorService
) : CameraHub.Tap {

    companion object {
        @Volatile private var initialized = false
        private const val MAX_MS = 10 * 60_000L
        private const val W = 640
    }

    private val lock = Any()
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var sender: RtpSender? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var sig: DatabaseReference? = null
    private var offerL: ValueEventListener? = null
    private var iceL: ChildEventListener? = null
    private val pendingIce = ArrayList<IceCandidate>()
    private var remoteSet = false
    private var answered = false
    private var watch: ScheduledFuture<*>? = null
    private val cands = HashMap<String, Int>()
    @Volatile private var iceState = "new"
    @Volatile private var pcState = "new"
    @Volatile private var pair: String? = null
    private var startedAt = 0L
    private var connectedAt = 0L
    private var lostAt = 0L
    @Volatile private var srcRef: VideoSource? = null          // lectura sin cerrojo desde el hilo de la cámara
    private val pushExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "webrtc-push") }
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastPush = 0L
    @Volatile private var pushed = 0L

    @Volatile var running = false; private set
    @Volatile var camera = "front"
    @Volatile var session: String? = null; private set
    var onStopped: ((String) -> Unit)? = null

    /** Arranca una sesión. Lanza CamError("webrtc") si no se puede preparar. */
    fun start(sigRef: DatabaseReference, iceConfigRef: DatabaseReference, sessionId: String, cam: String) {
        stop("nueva sesión")
        val servers = iceServers(iceConfigRef)
        synchronized(lock) {
            try { ensureFactory() } catch (e: Throwable) { throw CamError("webrtc", "No se pudo iniciar WebRTC en el coche: ${BmmRuntime.rootCause(e)}") }
            val f = factory ?: throw CamError("webrtc", "WebRTC no disponible en el coche.")
            camera = cam; session = sessionId; sig = sigRef
            remoteSet = false; answered = false; pendingIce.clear(); cands.clear()
            iceState = "new"; pcState = "new"; pair = null
            startedAt = SystemClock.elapsedRealtime(); connectedAt = 0; lostAt = 0; pushed = 0

            val src = f.createVideoSource(false)
            src.capturerObserver.onCapturerStarted(true)
            // Sin adaptOutputFormat: los frames ya salen escalados de aquí (≤ 640 px) y con 4:3 fijo recortaría las cámaras 16:9.
            source = src; srcRef = src
            track = f.createVideoTrack("cam0", src)

            val cfg = PeerConnection.RTCConfiguration(servers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            val p = f.createPeerConnection(cfg, observer) ?: throw CamError("webrtc", "No se pudo crear la conexión WebRTC.")
            pc = p
            sender = p.addTrack(track, listOf("cam"))
            running = true
        }
        hub.addTap(this)
        writeCar(null)

        val ol = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val sdp = s.child("sdp").getValue(String::class.java) ?: return
                onOffer(sdp)
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        val il = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, p: String?) {
                val c = s.value as? Map<*, *> ?: return
                val cand = IceCandidate(c["sdpMid"] as? String, (c["sdpMLineIndex"] as? Number)?.toInt() ?: 0, c["candidate"] as? String ?: return)
                val now = synchronized(lock) { if (remoteSet) pc else { pendingIce += cand; null } }
                now?.addIceCandidate(cand)          // fuera del cerrojo: WebRTC llama a sus propios hilos
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }
        offerL = ol; iceL = il
        sigRef.child("offer").addValueEventListener(ol)
        sigRef.child("ice/phone").addChildEventListener(il)
        watch = sched.scheduleWithFixedDelay({ check() }, 2, 2, TimeUnit.SECONDS)
        CamLog.i("WebRTC: sesión $sessionId ($cam), ${servers.size} servidores ICE")
    }

    fun stop(reason: String) {
        val wasRunning: Boolean
        var oldPc: PeerConnection? = null
        var oldTrack: VideoTrack? = null
        var oldSrc: VideoSource? = null
        synchronized(lock) {
            wasRunning = running
            running = false
            hub.removeTap(this)
            watch?.cancel(false); watch = null
            sig?.let { s ->
                offerL?.let { s.child("offer").removeEventListener(it) }
                iceL?.let { s.child("ice/phone").removeEventListener(it) }
            }
            offerL = null; iceL = null
            oldPc = pc; oldTrack = track; oldSrc = source
            pc = null; sender = null; track = null; source = null; srcRef = null
        }
        // Fuera del cerrojo: close/dispose esperan a los hilos de WebRTC (que también usan el cerrojo).
        try { oldPc?.close() } catch (e: Throwable) {}
        try { oldPc?.dispose() } catch (e: Throwable) {}
        try { oldTrack?.dispose() } catch (e: Throwable) {}
        try { oldSrc?.dispose() } catch (e: Throwable) {}
        if (wasRunning) {
            CamLog.i("WebRTC: parado ($reason, $pushed frames)")
            writeCar(reason)
            sig = null
            try { onStopped?.invoke(reason) } catch (e: Throwable) {}
        }
    }

    // ---------------- frames (hilo nativo) ----------------
    override fun onFrame(f: CameraHub.Frame) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPush < 66) return
        if (connectedAt == 0L && pushed > 30) return      // sin conexión aún: no gastar CPU de más
        val g = f.g
        val cam = camera
        val buffer: VideoFrame.Buffer = try {
            val ref = hub.profile.cams[cam]
            if (cam != "mosaic" && ref != null && ref.view == f.view && g.kind == YuvKind.SEMI) {
                // Rápido: recorte+escala nativo (libyuv) desde la tira; el resultado es memoria propia.
                val k = if (g.validTile(ref.tile)) ref.tile else 0
                val (ow, oh) = Yuv.fit(g.tileW, g.tileH, W)
                val nv = NV21Buffer(f.data, g.w, g.h, null)
                val out = nv.cropAndScale(g.tileX(k), g.tileY(k), g.tileW, g.tileH, ow, oh)
                nv.release()
                if (g.vuFirst) out else swapUv(out)
            } else {
                val r = views.render(f, cam, W) ?: return
                NV21Buffer(r.nv21, r.w, r.h, null)
            }
        } catch (e: Throwable) { CamLog.limited("rtcbuf", 15_000, "WebRTC: no se pudo preparar el frame: $e"); return }
        lastPush = now
        val src = srcRef
        // Si el codificador aún está con el frame anterior, este se descarta (la cámara no espera nunca).
        if (src == null || !running || !inFlight.compareAndSet(false, true)) { buffer.release(); return }
        val vf = VideoFrame(buffer, 0, SystemClock.elapsedRealtimeNanos())
        try {
            pushExec.execute {
                try { if (running && srcRef === src) src.capturerObserver.onFrameCaptured(vf) } catch (e: Throwable) {}
                finally { vf.release(); inFlight.set(false) }
            }
        } catch (e: Throwable) { vf.release(); inFlight.set(false); return }
        pushed++
    }

    private fun swapUv(b: VideoFrame.Buffer): VideoFrame.Buffer {
        val i = b.toI420() ?: return b
        b.release()
        return JavaI420Buffer.wrap(i.width, i.height, i.dataY, i.strideY, i.dataV, i.strideV, i.dataU, i.strideU) { i.release() }
    }

    // ---------------- señalización ----------------
    private fun onOffer(sdp: String) {
        val p: PeerConnection
        synchronized(lock) {
            if (answered || !running) return
            answered = true
            p = pc ?: return
        }
        p.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() {
                val pend = synchronized(lock) {
                    remoteSet = true
                    val l = pendingIce.toList(); pendingIce.clear(); l
                }
                pend.forEach { p.addIceCandidate(it) }
                p.createAnswer(object : Sdp() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        p.setLocalDescription(object : Sdp() {
                            override fun onSetSuccess() {
                                sig?.child("answer")?.setValue(mapOf("type" to "answer", "sdp" to desc.description))
                                limitBitrate()
                            }
                            override fun onSetFailure(err: String?) { fail("setLocalDescription: $err") }
                        }, desc)
                    }
                    override fun onCreateFailure(err: String?) { fail("createAnswer: $err") }
                }, MediaConstraints())
            }
            override fun onSetFailure(err: String?) { fail("setRemoteDescription: $err") }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun limitBitrate() {
        try {
            val s = sender ?: return
            val prm = s.parameters
            prm.degradationPreference = RtpParameters.DegradationPreference.BALANCED
            prm.encodings.forEach { e -> e.maxBitrateBps = 600_000; e.minBitrateBps = 150_000; e.maxFramerate = 15 }
            s.setParameters(prm)
        } catch (e: Throwable) { CamLog.w("WebRTC: no se pudo limitar el bitrate", e) }
    }

    private fun fail(msg: String) {
        CamLog.w("WebRTC: $msg")
        sched.execute { stop("error: $msg") }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            val type = Regex(" typ (\\w+)").find(c.sdp)?.groupValues?.get(1) ?: "?"
            synchronized(lock) { cands[type] = (cands[type] ?: 0) + 1 }
            sig?.child("ice/car")?.push()?.setValue(mapOf("sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex, "candidate" to c.sdp))
        }
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
            iceState = s?.name?.lowercase() ?: "?"
            CamLog.i("WebRTC: ICE $iceState")
            writeCar(null)
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
            pcState = s?.name?.lowercase() ?: "?"
            val now = SystemClock.elapsedRealtime()
            when (s) {
                PeerConnection.PeerConnectionState.CONNECTED -> { if (connectedAt == 0L) connectedAt = now; lostAt = 0; readPair() }
                PeerConnection.PeerConnectionState.DISCONNECTED -> if (lostAt == 0L) lostAt = now
                else -> {}
            }
            CamLog.i("WebRTC: conexión $pcState")
            writeCar(null)
        }
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) { writeCar(null) }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    /** Par de candidatos elegido (host/srflx/relay) para saber si va directo o por TURN. */
    private fun readPair() {
        val p = synchronized(lock) { pc } ?: return
        try {
            p.getStats { rep ->
                val m = rep.statsMap
                val cp = m.values.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
                    ?: m.values.firstOrNull { it.type == "candidate-pair" && it.members["state"] == "succeeded" }
                if (cp != null) {
                    val l = m[cp.members["localCandidateId"] as? String]?.members?.get("candidateType")
                    val r = m[cp.members["remoteCandidateId"] as? String]?.members?.get("candidateType")
                    pair = "$l/$r"
                    CamLog.i("WebRTC: conectado por $pair")
                    writeCar(null)
                }
            }
        } catch (e: Throwable) {}
    }

    private fun check() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        when {
            connectedAt == 0L && now - startedAt > 30_000 -> stop("ice_timeout")
            iceState == "failed" || pcState == "failed" -> stop("ice_failed")
            lostAt != 0L && now - lostAt > 15_000 -> stop("ice_lost")
            now - startedAt > MAX_MS -> stop("max_10min")
            hub.state == "not_parked" -> stop("not_parked")
            hub.block != null -> stop(hub.block ?: "bloqueada")
        }
    }

    private fun writeCar(stopped: String?) {
        val s = sig ?: return
        val c = synchronized(lock) { HashMap(cands) }
        s.child("car").setValue(mapOf(
            "ice" to iceState, "pc" to pcState, "pair" to pair, "cands" to c, "camera" to camera,
            "frames" to pushed, "stopped" to stopped, "ts" to ServerValue.TIMESTAMP
        ))
    }

    /** STUN públicos + TURN de `cars/{id}/iceConfig` = {iceServers:[{urls, username, credential}]} si existe. */
    private fun iceServers(ref: DatabaseReference): List<PeerConnection.IceServer> {
        val list = arrayListOf(
            PeerConnection.IceServer.builder(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")).createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        )
        try {
            val snap = Tasks.await(ref.get(), 3, TimeUnit.SECONDS)
            for (s in snap.child("iceServers").children) {
                val urls = when (val u = s.child("urls").value) {
                    is String -> listOf(u)
                    is List<*> -> u.mapNotNull { it as? String }
                    else -> emptyList()
                }
                if (urls.isEmpty()) continue
                val b = PeerConnection.IceServer.builder(urls)
                s.child("username").getValue(String::class.java)?.let { b.setUsername(it) }
                s.child("credential").getValue(String::class.java)?.let { b.setPassword(it) }
                list += b.createIceServer()
            }
        } catch (e: Throwable) { /* sin iceConfig: solo STUN */ }
        return list
    }

    private fun ensureFactory() {
        if (factory != null) return
        if (!initialized) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
            initialized = true
        }
        val e = egl ?: EglBase.create().also { egl = it }
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(err: String?) {}
        override fun onSetFailure(err: String?) {}
    }
}
