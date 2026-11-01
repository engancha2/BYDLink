package com.bydlink.carapp.camera

import android.content.Context

/**
 * Grabación continua (dashcam) en tramos de unos minutos, en bucle: cuando se llena el espacio elegido se borran los
 * tramos más viejos (ver [VideoStore.cleanup]). Modos:
 *  - "off": nada;
 *  - "parked": aparcado (vigilancia continua, además de los clips del centinela);
 *  - "drive": en marcha (experimental: la cámara solo se usa por encima de 15 km/h, ver [CameraHub]);
 *  - "always": las dos cosas.
 * La configuración vive en las preferencias `video` del coche (la cambian el móvil y el lanzador con setVideoConfig).
 */
class Dashcam(private val ctx: Context, private val hub: CameraHub, private val clips: ClipRecorder) {

    companion object {
        val MODES = listOf("off", "parked", "drive", "always")
    }

    @Volatile var mode = "off"; private set
    @Volatile private var holding = false

    /** Lee la configuración y se pone en marcha (o se para). */
    @Synchronized fun configure() {
        val sp = VideoStore.prefs(ctx)
        mode = sp.getString("mode", "off")?.takeIf { it in MODES } ?: "off"
        hub.driveAllowed = mode == "drive" || mode == "always"
        tick()
    }

    /** Cada pocos segundos (CameraCommands) y al acabar cada tramo. */
    @Synchronized fun tick() {
        val parked = hub.parkedNow()
        val want = when (mode) { "parked" -> parked; "drive" -> !parked; "always" -> true; else -> false }
        if (want != holding) {
            holding = want
            if (want) { hub.acquire(CameraHub.DASHCAM); CamLog.i("dashcam: en marcha (modo $mode)") }
            else { hub.release(CameraHub.DASHCAM); clips.stop(VideoStore.DASHCAM); CamLog.i("dashcam: parada (modo $mode)") }
        }
        if (holding && hub.streaming() && !clips.busy(VideoStore.DASHCAM)) {
            val sp = VideoStore.prefs(ctx)
            val seg = sp.getInt("segMin", 3).coerceIn(1, 10) * 60
            val cam = hub.profile.cams.keys.firstOrNull() ?: "back"
            clips.record(cam, seg, VideoStore.DASHCAM, ClipRecorder.Quality.of(sp.getString("quality", "normal")),
                consumer = null, stamp = sp.getBoolean("stamp", true)) { Thread { try { tick() } catch (e: Throwable) {} }.start() }
        }
    }

    fun recording() = clips.busy(VideoStore.DASHCAM)
    fun holding() = holding
}
