package com.bydlink.carapp.camera

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ChildEventListener
import org.webrtc.*

/**
 * Transmite la cámara del coche al móvil por WebRTC (vídeo directo coche→móvil).
 * El COCHE actúa de answerer: el móvil publica su `offer` en Firebase, aquí la respondemos
 * con una pista de vídeo alimentada por los frames NV21 de [CameraController].
 *
 * Señalización en /cars/{id}/signaling/{session}: offer (móvil), answer (coche), ice/phone, ice/car.
 *
 * NOTA: usa org.webrtc (io.getstream:stream-webrtc-android). Es la parte que más conviene
 * verificar/compilar contra el coche; la API de WebRTC es estándar pero puede requerir ajustes.
 */
class LiveStreamer(private val ctx: Context) {

    private val egl: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var sig: DatabaseReference? = null
    private var running = false

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        // TURN opcional si la red no deja P2P.
    )

    fun start(signalRef: DatabaseReference) {
        stop()
        running = true
        sig = signalRef
        ensureFactory()
        val f = factory ?: return

        source = f.createVideoSource(false)
        track = f.createVideoTrack("cam0", source)

        pc = f.createPeerConnection(PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                sig?.child("ice/car")?.push()?.setValue(mapOf("sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex, "candidate" to c.sdp))
            }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {}
        })
        pc?.addTrack(track)

        // Escucha la oferta del móvil → responde.
        sig?.child("offer")?.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val sdp = snap.child("sdp").getValue(String::class.java) ?: return
                val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
                pc?.setRemoteDescription(SimpleSdp(), offer)
                pc?.createAnswer(object : SimpleSdp() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        pc?.setLocalDescription(SimpleSdp(), desc)
                        sig?.child("answer")?.setValue(mapOf("type" to "answer", "sdp" to desc.description))
                    }
                }, MediaConstraints())
            }
            override fun onCancelled(e: DatabaseError) {}
        })
        // Candidatos ICE del móvil.
        sig?.child("ice/phone")?.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, p: String?) {
                val c = s.value as? Map<*, *> ?: return
                pc?.addIceCandidate(IceCandidate(c["sdpMid"] as? String, (c["sdpMLineIndex"] as? Number)?.toInt() ?: 0, c["candidate"] as? String))
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    /** Alimenta un frame NV21 de la cámara a la pista de vídeo. */
    fun pushFrame(nv21: ByteArray, width: Int, height: Int) {
        if (!running) return
        try {
            val buffer = NV21Buffer(nv21, width, height, null)
            val frame = VideoFrame(buffer, 0, System.nanoTime())
            source?.capturerObserver?.onFrameCaptured(frame)
            frame.release()
        } catch (e: Throwable) { /* formato distinto (NV12/I420): se adapta en el coche */ }
    }

    fun stop() {
        running = false
        try { sig?.removeValue() } catch (e: Throwable) {}
        sig = null
        try { pc?.close() } catch (e: Throwable) {}
        pc = null; track = null
        try { source?.dispose() } catch (e: Throwable) {}
        source = null
    }

    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    /** SdpObserver mínimo (sobrescribe onCreateSuccess donde haga falta). */
    private open class SimpleSdp : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(err: String?) {}
        override fun onSetFailure(err: String?) {}
    }
}
