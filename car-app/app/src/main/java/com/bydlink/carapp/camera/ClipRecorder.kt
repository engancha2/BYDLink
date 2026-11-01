package com.bydlink.carapp.camera

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import com.bydlink.carapp.service.StateHub
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Graba vídeo MP4 (H.264 con MediaCodec + MediaMuxer, sin audio) de una cámara. Puede haber una grabación de cada tipo
 * a la vez: clip del centinela, grabación a mano y tramo de la dashcam, cada una con su codificador.
 * Dónde se guarda lo decide [VideoStore] (memoria del coche, SD o USB). Con sello de fecha, hora, velocidad y batería.
 */
class ClipRecorder(private val ctx: Context, private val hub: CameraHub, private val views: CamViews) : CameraHub.Tap {

    /** Calidad: ancho máximo, fotogramas por segundo y bitrate (normal ≈ 10 MB/min, alta ≈ 19 MB/min). */
    class Quality(val maxW: Int, val fps: Int, val bitrate: Int) {
        companion object {
            val SENTRY = Quality(640, 10, 1_200_000)
            val NORMAL = Quality(640, 12, 1_400_000)
            val HIGH = Quality(768, 20, 2_500_000)
            fun of(name: String?) = if (name == "alta") HIGH else NORMAL
        }
    }

    class Job(
        val kind: String, val camera: String, val file: File, @Volatile var until: Long,
        val q: Quality, val consumer: String?, val stamp: Boolean, val onDone: ((File?) -> Unit)?
    ) {
        val queue = ArrayBlockingQueue<Pair<Rendered, Long>>(4)
        @Volatile var nextAt = 0L
        val startedAt = System.currentTimeMillis()
        fun toMap() = mapOf("kind" to kind, "camera" to camera, "since" to startedAt,
            "until" to System.currentTimeMillis() + (until - SystemClock.elapsedRealtime()).coerceAtLeast(0), "file" to file.name)
    }

    private val jobs = CopyOnWriteArrayList<Job>()
    @Volatile var onChange: (() -> Unit)? = null

    fun busy() = busy(VideoStore.SENTRY)
    fun busy(kind: String) = jobs.any { it.kind == kind }
    fun active(): List<Job> = jobs.toList()

    /**
     * Empieza a grabar [seconds] s de [camera]. [consumer] = nombre con el que se pide la cámara al hub (null si ya la tiene
     * pedida quien llama, como la dashcam). Devuelve la ruta del fichero, o null si ya hay una de ese tipo o no hay sitio.
     */
    @Synchronized fun record(
        camera: String, seconds: Int, kind: String = VideoStore.SENTRY, q: Quality = Quality.SENTRY,
        consumer: String? = CameraHub.CLIP, stamp: Boolean = VideoStore.prefs(ctx).getBoolean("stamp", true),
        onDone: ((File?) -> Unit)? = null
    ): String? {
        if (busy(kind)) return null
        val file = VideoStore.newFile(ctx, kind, camera) ?: return null
        val j = Job(kind, camera, file, SystemClock.elapsedRealtime() + seconds * 1000L, q, consumer, stamp, onDone)
        jobs += j
        hub.addTap(this); consumer?.let { hub.acquire(it) }
        Thread({ encode(j) }, "cam-rec-$kind").apply { isDaemon = true }.start()
        CamLog.i("vídeo: grabando $kind $camera ${seconds}s -> ${file.path}")
        onChange?.invoke()
        return file.path
    }

    /** Termina ya las grabaciones de [kind] (todas si es null); el fichero queda bien cerrado. */
    fun stop(kind: String? = null) { jobs.filter { kind == null || it.kind == kind }.forEach { it.until = 0 } }

    override fun onFrame(f: CameraHub.Frame) {
        val now = SystemClock.elapsedRealtime()
        for (j in jobs) {
            if (now < j.nextAt) continue
            val r = views.render(f, j.camera, j.q.maxW) ?: continue
            val step = 1000L / j.q.fps          // reparto regular: de 25 fps de la cámara saca los que pide la calidad
            j.nextAt = if (now - j.nextAt > step) now + step else j.nextAt + step
            j.queue.offer(r to now)      // si el codificador va atrasado, se descarta el frame
        }
    }

    private val clock = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US)
    private var stampSec = -1L
    private var stampText = ""

    /** "01/11/2026 08:15:02  52 km/h  74 %" (se recalcula una vez por segundo). */
    @Synchronized private fun stampLine(): String {
        val now = System.currentTimeMillis()
        if (now / 1000 != stampSec) {
            stampSec = now / 1000
            val s = StateHub.state
            stampText = listOfNotNull(clock.format(Date(now)), s?.let { "${it.speed} km/h" }, s?.soc?.let { "$it %" }).joinToString("  ")
        }
        return stampText
    }

    private fun encode(j: Job) {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxStarted = false
        var track = -1
        var written = 0
        val stamp = if (j.stamp) Stamp() else null
        var stampBroken = false
        try {
            val first = j.queue.poll(10, TimeUnit.SECONDS) ?: throw IllegalStateException("no llegan frames")
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
                setInteger(MediaFormat.KEY_BIT_RATE, j.q.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, j.q.fps)
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
                val item = next ?: j.queue.poll(200, TimeUnit.MILLISECONDS)
                next = null
                val end = SystemClock.elapsedRealtime() >= j.until || !hub.isOpen()
                if (item != null && item.first.w == w && item.first.h == h) {
                    val ii = c.dequeueInputBuffer(20_000)
                    if (ii >= 0) {
                        if (stamp != null && !stampBroken) {
                            try { stamp.apply(item.first, stampLine()) }
                            catch (e: Throwable) { stampBroken = true; CamLog.w("vídeo: sin sello (falló)", e) }
                        }
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
            CamLog.i("vídeo: terminado ${j.file.name} ($written muestras, ${j.file.length() / 1024} KB)")
        } catch (e: Throwable) {
            CamLog.w("vídeo: fallo al grabar ${j.file.name}", e)
        } finally {
            try { codec?.stop() } catch (e: Throwable) {}
            try { codec?.release() } catch (e: Throwable) {}
            try { if (muxStarted) muxer?.stop() } catch (e: Throwable) {}
            try { muxer?.release() } catch (e: Throwable) {}
            if (written == 0) j.file.delete()
            jobs.remove(j)
            if (jobs.isEmpty()) hub.removeTap(this)
            j.consumer?.let { hub.release(it) }
            try { j.onDone?.invoke(j.file.takeIf { written > 0 && it.isFile }) } catch (e: Throwable) {}
            onChange?.invoke()
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
}
