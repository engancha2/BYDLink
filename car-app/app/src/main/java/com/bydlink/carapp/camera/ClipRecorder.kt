package com.bydlink.carapp.camera

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Clip MP4 corto de un evento del centinela: la cámara que disparó, 640×480, 10 fps, H.264 (MediaCodec)
 * en MP4 (MediaMuxer). Va en `/sdcard/BYDLink/Sentry/AAAA-MM/DD/HHmmss_camara.mp4` si se puede escribir
 * ahí; si no (almacenamiento acotado de Android 10+), en `Android/data/com.bydlink.carapp/files/Sentry/AAAA-MM/DD/`.
 * Sin pre-buffer (empieza al detectar). Se borran los de más de 14 días y se limita a 1,5 GB.
 */
class ClipRecorder(private val ctx: Context, private val hub: CameraHub, private val views: CamViews) : CameraHub.Tap {

    companion object {
        private const val FPS = 10
        private const val MAX_BYTES = 1_500L * 1024 * 1024
        private const val KEEP_DAYS = 14

        /** Id corto del clip a partir de su ruta: "AAAA-MM/DD/HHmmss_cam". */
        fun idOf(path: String): String {
            val f = File(path)
            return "${f.parentFile?.parentFile?.name}/${f.parentFile?.name}/${f.nameWithoutExtension}"
        }
    }

    private class Job(val camera: String, val file: File, val until: Long) {
        val q = ArrayBlockingQueue<Pair<Rendered, Long>>(4)
        @Volatile var lastFrame = 0L
    }

    @Volatile private var job: Job? = null

    fun busy() = job != null

    /** Empieza a grabar [seconds] s de [camera]. Devuelve la ruta del fichero o null si no se puede. */
    @Synchronized fun record(camera: String, seconds: Int): String? {
        if (job != null) return null
        val file = newFile(camera) ?: return null
        val j = Job(camera, file, SystemClock.elapsedRealtime() + seconds * 1000L)
        job = j
        hub.addTap(this); hub.acquire(CameraHub.CLIP)
        Thread({ encode(j) }, "cam-clip").apply { isDaemon = true }.start()
        CamLog.i("clip: grabando $camera ${seconds}s -> ${file.path}")
        return file.path
    }

    override fun onFrame(f: CameraHub.Frame) {
        val j = job ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - j.lastFrame < 1000L / FPS) return
        val r = views.render(f, j.camera, 640) ?: return
        j.lastFrame = now
        j.q.offer(r to now)      // si el codificador va atrasado, se descarta el frame
    }

    private fun encode(j: Job) {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxStarted = false
        var track = -1
        var written = 0
        try {
            val first = j.q.poll(6, TimeUnit.SECONDS) ?: throw IllegalStateException("no llegan frames")
            val w = first.first.w; val h = first.first.h
            val c = MediaCodec.createEncoderByType("video/avc"); codec = c
            val caps = c.codecInfo.getCapabilitiesForType("video/avc")
            val flex = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            val semi = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            val cf = when {
                caps.colorFormats.contains(flex) -> flex
                caps.colorFormats.contains(semi) -> semi
                else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            }
            val fmt = MediaFormat.createVideoFormat("video/avc", w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, cf)
                setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            val mx = MediaMuxer(j.file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); muxer = mx
            val info = MediaCodec.BufferInfo()
            val t0 = first.second
            var next: Pair<Rendered, Long>? = first

            fun drain(untilEos: Boolean): Boolean {
                val stopAt = SystemClock.elapsedRealtime() + 3000
                while (true) {
                    val oi = c.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
                    when {
                        oi == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos || SystemClock.elapsedRealtime() > stopAt) return false
                        oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mx.addTrack(c.outputFormat); mx.start(); muxStarted = true }
                        oi >= 0 -> {
                            val ob = c.getOutputBuffer(oi)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (ob != null && info.size > 0 && muxStarted) {
                                ob.position(info.offset); ob.limit(info.offset + info.size)
                                mx.writeSampleData(track, ob, info); written++
                            }
                            c.releaseOutputBuffer(oi, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                        }
                    }
                }
            }

            while (true) {
                val item = next ?: j.q.poll(200, TimeUnit.MILLISECONDS)
                next = null
                val end = SystemClock.elapsedRealtime() >= j.until || !hub.isOpen()
                if (item != null && item.first.w == w && item.first.h == h) {
                    val ii = c.dequeueInputBuffer(20_000)
                    if (ii >= 0) {
                        fill(c, ii, item.first, cf)
                        c.queueInputBuffer(ii, 0, w * h * 3 / 2, (item.second - t0) * 1000, 0)
                    }
                }
                drain(false)
                if (end) {
                    val ii = c.dequeueInputBuffer(50_000)
                    if (ii >= 0) c.queueInputBuffer(ii, 0, 0, (SystemClock.elapsedRealtime() - t0) * 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    drain(true)
                    break
                }
            }
            CamLog.i("clip: terminado ${j.file.name} ($written muestras, ${j.file.length() / 1024} KB)")
        } catch (e: Throwable) {
            CamLog.w("clip: fallo al grabar ${j.file.name}", e)
        } finally {
            try { codec?.stop() } catch (e: Throwable) {}
            try { codec?.release() } catch (e: Throwable) {}
            try { if (muxStarted) muxer?.stop() } catch (e: Throwable) {}
            try { muxer?.release() } catch (e: Throwable) {}
            if (written == 0) j.file.delete()
            hub.removeTap(this); hub.release(CameraHub.CLIP)
            job = null
        }
    }

    /** Copia un NV21 a la entrada del codificador (Image flexible, NV12 o I420). */
    private fun fill(c: MediaCodec, idx: Int, r: Rendered, cf: Int) {
        val w = r.w; val h = r.h; val src = r.nv21; val ySize = w * h
        val img = if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) c.getInputImage(idx) else null
        if (img != null) {
            val py = img.planes[0]; val pu = img.planes[1]; val pv = img.planes[2]
            val by = py.buffer; val rsY = py.rowStride
            if (rsY == w && py.pixelStride == 1) { by.position(0); by.put(src, 0, ySize) }
            else for (y in 0 until h) { by.position(y * rsY); by.put(src, y * w, w) }
            val bu = pu.buffer; val bv = pv.buffer
            val rsU = pu.rowStride; val psU = pu.pixelStride; val rsV = pv.rowStride; val psV = pv.pixelStride
            for (y in 0 until h / 2) {
                val s = ySize + y * w
                for (x in 0 until w / 2) {
                    bv.put(y * rsV + x * psV, src[s + 2 * x])        // NV21: V primero
                    bu.put(y * rsU + x * psU, src[s + 2 * x + 1])
                }
            }
            return
        }
        val buf = c.getInputBuffer(idx) ?: return
        buf.clear()
        buf.put(src, 0, ySize)
        if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
            for (i in 0 until ySize / 4) { buf.put(src[ySize + 2 * i + 1]); buf.put(src[ySize + 2 * i]) }   // NV12: U,V
        } else {
            for (i in 0 until ySize / 4) buf.put(src[ySize + 2 * i + 1])   // I420: plano U
            for (i in 0 until ySize / 4) buf.put(src[ySize + 2 * i])       // plano V
        }
    }

    private fun newFile(camera: String): File? {
        val now = Date()
        val sub = SimpleDateFormat("yyyy-MM", Locale.US).format(now) + "/" + SimpleDateFormat("dd", Locale.US).format(now)
        val name = SimpleDateFormat("HHmmss", Locale.US).format(now) + "_$camera.mp4"
        for (base in bases()) {
            try {
                val d = File(base, sub)
                if (!d.exists() && !d.mkdirs()) continue
                val probe = File(d, ".w"); probe.writeText(""); probe.delete()
                cleanup(base)
                return File(d, name)
            } catch (e: Throwable) { /* siguiente */ }
        }
        CamLog.w("clip: no hay dónde guardar")
        return null
    }

    private fun bases(): List<File> = listOfNotNull(
        File("/sdcard/BYDLink/Sentry"),
        ctx.getExternalFilesDir("Sentry"),
        File(ctx.filesDir, "Sentry")
    )

    /** Borra clips viejos y, si pasan de 1,5 GB, los más antiguos. */
    private fun cleanup(base: File) {
        try {
            val files = base.walkTopDown().filter { it.isFile && it.name.endsWith(".mp4") }.sortedBy { it.lastModified() }.toMutableList()
            val limit = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
            files.filter { it.lastModified() < limit }.forEach { it.delete(); files.remove(it) }
            var total = files.sumOf { it.length() }
            while (total > MAX_BYTES && files.isNotEmpty()) { val f = files.removeAt(0); total -= f.length(); f.delete() }
        } catch (e: Throwable) {}
    }
}
