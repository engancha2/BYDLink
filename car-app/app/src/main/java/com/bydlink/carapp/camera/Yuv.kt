package com.bydlink.carapp.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import java.io.ByteArrayOutputStream

enum class YuvKind { SEMI, PLANAR, YUY2 }

/**
 * Geometría REAL de un frame BMM, deducida de los metadatos del callback y del tamaño de los datos
 * (fallo S4: los metadatos no siempre cuadran; la AVM del Atto 3 entrega una tira 5120×960 con 4 cámaras).
 * [cols]×[rows] = trozos (una cámara por trozo). [vuFirst]: el croma va V,U (NV21) o U,V (NV12/I420).
 */
class FrameGeom(
    val w: Int, val h: Int, val fmt: Int, val kind: YuvKind,
    val cols: Int, val rows: Int, val vuFirst: Boolean, val bytes: Int
) {
    val tileW: Int = (w / cols) and 1.inv()
    val tileH: Int = (h / rows) and 1.inv()
    val tiles: Int get() = cols * rows
    fun tileX(k: Int) = ((k % cols) * (w / cols)) and 1.inv()
    fun tileY(k: Int) = ((k / cols) * (h / rows)) and 1.inv()
    fun validTile(k: Int) = k in 0 until tiles

    fun toMap(): Map<String, Any?> = mapOf(
        "w" to w, "h" to h, "fmt" to fmt, "kind" to kind.name.lowercase(), "grid" to "${cols}x$rows",
        "tiles" to tiles, "tileW" to tileW, "tileH" to tileH, "nv12" to !vuFirst, "bytes" to bytes
    )

    override fun toString() = "${w}x$h fmt=$fmt ${kind.name} grid=${cols}x$rows tile=${tileW}x$tileH ${if (vuFirst) "VU" else "UV"}"

    companion object {
        /** Tamaños conocidos (por orden de preferencia si el nº de bytes coincide con varios). */
        private val KNOWN = listOf(
            5120 to 960, 5120 to 720, 2560 to 960, 1280 to 960, 1280 to 720, 2560 to 1920, 1920 to 1080,
            7680 to 1080, 3840 to 1080, 2560 to 720, 1280 to 800, 5120 to 800, 640 to 480, 1920 to 720
        )

        fun kindOf(fmt: Int) = when (fmt) { 20 -> YuvKind.YUY2; 19, 0x32315659 -> YuvKind.PLANAR; else -> YuvKind.SEMI }

        fun need(kind: YuvKind, w: Int, h: Int): Int = if (kind == YuvKind.YUY2) w * h * 2 else w * h * 3 / 2

        /**
         * @param n bytes útiles (= min(dataSize, data.size)). Devuelve null si no se puede interpretar.
         * @param grid "auto" o "CxR" (del perfil). @param nv12 invierte el orden del croma.
         */
        fun deduce(w: Int, h: Int, fmt: Int, n: Int, grid: String, nv12: Boolean): FrameGeom? {
            if (n <= 0) return null
            val kind = kindOf(fmt)
            val metaOk = w in 16..16384 && h in 16..16384 && w % 2 == 0 && h % 2 == 0
            var W = 0; var H = 0
            if (metaOk && need(kind, w, h) == n) { W = w; H = h }
            if (W == 0) KNOWN.firstOrNull { need(kind, it.first, it.second) == n }?.let { W = it.first; H = it.second }
            if (W == 0 && metaOk) {
                val one = need(kind, w, h)
                val k = if (one > 0 && n % one == 0) n / one else 0
                if (k in 2..4) { W = w * k; H = h }            // tira: los metadatos son de una sola cámara
                else if (one <= n) { W = w; H = h }            // relleno al final
            }
            if (W == 0 || H == 0) return null
            var cols = 1; var rows = 1
            val g = Regex("([1-4])x([1-4])").matchEntire(grid)
            if (g != null) { cols = g.groupValues[1].toInt(); rows = g.groupValues[2].toInt() }
            else for (c in 4 downTo 1) {
                val a = (W.toDouble() / c) / H
                if (a in 1.2..1.9) { cols = c; break }
            }
            val vu = when (kind) { YuvKind.SEMI -> true; YuvKind.PLANAR -> fmt == 0x32315659; YuvKind.YUY2 -> false } xor nv12
            return FrameGeom(W, H, fmt, kind, cols, rows, vu, n)
        }
    }
}

/** Utilidades YUV en Kotlin puro (sin WebRTC ni librerías nativas): recorte+escala a NV21, luminancia, JPEG. */
object Yuv {

    /** Tamaño de salida con el aspecto del trozo, ancho [maxW], ambos pares. */
    fun fit(tileW: Int, tileH: Int, maxW: Int): Pair<Int, Int> {
        val w = maxW and 1.inv()
        val h = ((w.toLong() * tileH / maxOf(1, tileW)).toInt() + 1) and 1.inv()
        return w to maxOf(2, h)
    }

    /**
     * Copia el rectángulo (rx,ry,rw,rh) de [src] escalado a NV21 dentro de [out] (NV21 de ow×oh)
     * en la posición (dx,dy) con tamaño dw×dh. Muestreo por el centro; media 2×2 de luma si reduce ≥ 2×.
     * Todo par: dx, dy, dw, dh. [src] ya validado: g.bytes ≥ need(g).
     */
    fun scaleInto(
        src: ByteArray, g: FrameGeom, rx: Int, ry: Int, rw: Int, rh: Int,
        out: ByteArray, ow: Int, oh: Int, dx: Int, dy: Int, dw: Int, dh: Int
    ) {
        val W = g.w; val H = g.h
        val xs = IntArray(dw) { (rx + ((2 * it + 1) * rw) / (2 * dw)).coerceIn(0, W - 2) }
        val box = rw >= 2 * dw && rh >= 2 * dh
        val ySize = W * H
        val outY = ow * oh
        for (y in 0 until dh) {
            val sy = (ry + ((2 * y + 1) * rh) / (2 * dh)).coerceIn(0, H - 2)
            val o = (dy + y) * ow + dx
            when (g.kind) {
                YuvKind.SEMI, YuvKind.PLANAR -> {
                    val r0 = sy * W; val r1 = r0 + W
                    if (box) for (x in 0 until dw) {
                        val sx = xs[x]
                        out[o + x] = (((src[r0 + sx].toInt() and 255) + (src[r0 + sx + 1].toInt() and 255) +
                            (src[r1 + sx].toInt() and 255) + (src[r1 + sx + 1].toInt() and 255)) shr 2).toByte()
                    } else for (x in 0 until dw) out[o + x] = src[r0 + xs[x]]
                }
                YuvKind.YUY2 -> {
                    val r0 = sy * W * 2
                    if (box) for (x in 0 until dw) {
                        val p = r0 + xs[x] * 2
                        out[o + x] = (((src[p].toInt() and 255) + (src[p + 2].toInt() and 255)) shr 1).toByte()
                    } else for (x in 0 until dw) out[o + x] = src[r0 + xs[x] * 2]
                }
            }
            if ((y and 1) == 0) {
                val oc = outY + ((dy + y) shr 1) * ow + dx
                var x = 0
                while (x < dw) {
                    val sx = xs[x] and 1.inv()
                    var a: Byte = 0; var b: Byte = 0   // a = primero en memoria, b = segundo
                    when (g.kind) {
                        YuvKind.SEMI -> { val p = ySize + (sy shr 1) * W + sx; a = src[p]; b = src[p + 1] }
                        YuvKind.PLANAR -> {
                            val cw = W shr 1; val q = (sy shr 1) * cw + (sx shr 1)
                            a = src[ySize + q]; b = src[ySize + (ySize shr 2) + q]
                        }
                        YuvKind.YUY2 -> { val p = (sy * W + sx) * 2; a = src[p + 1]; b = src[p + 3] }
                    }
                    // NV21 de salida: V y después U.
                    if (g.vuFirst) { out[oc + x] = a; out[oc + x + 1] = b } else { out[oc + x] = b; out[oc + x + 1] = a }
                    x += 2
                }
            }
        }
    }

    /** Un trozo (cámara) del frame a NV21 de ow×oh. */
    fun tile(src: ByteArray, g: FrameGeom, tile: Int, ow: Int, oh: Int, out: ByteArray = ByteArray(ow * oh * 3 / 2)): ByteArray {
        val k = if (g.validTile(tile)) tile else 0
        scaleInto(src, g, g.tileX(k), g.tileY(k), g.tileW, g.tileH, out, ow, oh, 0, 0, ow, oh)
        return out
    }

    /** El frame entero (todas las cámaras) a NV21 de ow×oh. */
    fun whole(src: ByteArray, g: FrameGeom, ow: Int, oh: Int): ByteArray {
        val out = ByteArray(ow * oh * 3 / 2)
        scaleInto(src, g, 0, 0, g.w, g.h, out, ow, oh, 0, 0, ow, oh)
        return out
    }

    /** Mosaico 2×2 con los trozos [tiles] (−1 = negro). ow y oh múltiplos de 4. */
    fun mosaic(src: ByteArray, g: FrameGeom, tiles: List<Int>, ow: Int, oh: Int, out: ByteArray = ByteArray(ow * oh * 3 / 2)): ByteArray {
        val hw = ow / 2; val hh = oh / 2
        for (i in 0 until 4) {
            val k = tiles.getOrNull(i) ?: -1
            val dx = (i % 2) * hw; val dy = (i / 2) * hh
            if (g.validTile(k)) scaleInto(src, g, g.tileX(k), g.tileY(k), g.tileW, g.tileH, out, ow, oh, dx, dy, hw, hh)
            else blank(out, ow, oh, dx, dy, hw, hh)
        }
        return out
    }

    private fun blank(out: ByteArray, ow: Int, oh: Int, dx: Int, dy: Int, dw: Int, dh: Int) {
        for (y in 0 until dh) java.util.Arrays.fill(out, (dy + y) * ow + dx, (dy + y) * ow + dx + dw, 16.toByte())
        for (y in 0 until dh / 2) {
            val o = ow * oh + (dy / 2 + y) * ow + dx
            java.util.Arrays.fill(out, o, o + dw, 128.toByte())
        }
    }

    /** Luminancia reducida de un rectángulo a gw×gh (media de 4 muestras por celda). Para el centinela. */
    fun lumaGrid(src: ByteArray, g: FrameGeom, rx: Int, ry: Int, rw: Int, rh: Int, gw: Int, gh: Int, out: ByteArray) {
        val W = g.w; val H = g.h
        val yuy = g.kind == YuvKind.YUY2
        val px = if (yuy) 2 else 1
        val stepX = maxOf(1, rw / (gw * 2)); val stepY = maxOf(1, rh / (gh * 2))
        // Las 4 muestras de cada celda quedan dentro del rectángulo (no se cuelan del trozo vecino).
        val x0 = rx.coerceIn(0, W - 1); val y0 = ry.coerceIn(0, H - 1)
        val maxX = maxOf(x0, minOf(W - 1, rx + rw - 1) - stepX); val maxY = maxOf(y0, minOf(H - 1, ry + rh - 1) - stepY)
        for (y in 0 until gh) {
            val sy = (ry + ((2 * y + 1) * rh) / (2 * gh) - stepY / 2).coerceIn(y0, maxY)
            val r0 = sy * W * px; val r1 = minOf(H - 1, sy + stepY) * W * px
            for (x in 0 until gw) {
                val sx = (rx + ((2 * x + 1) * rw) / (2 * gw) - stepX / 2).coerceIn(x0, maxX)
                val a = sx * px; val b = minOf(W - 1, sx + stepX) * px
                out[y * gw + x] = (((src[r0 + a].toInt() and 255) + (src[r0 + b].toInt() and 255) +
                    (src[r1 + a].toInt() and 255) + (src[r1 + b].toInt() and 255)) shr 2).toByte()
            }
        }
    }

    /** Huella 4×4 de luminancia de un trozo (16 cifras hex). Sirve para ver si dos vistas son la misma cámara. */
    fun fingerprint(src: ByteArray, g: FrameGeom, tile: Int): String {
        val k = if (g.validTile(tile)) tile else 0
        val o = ByteArray(16)
        lumaGrid(src, g, g.tileX(k), g.tileY(k), g.tileW, g.tileH, 4, 4, o)
        return o.joinToString("") { "%x".format((it.toInt() and 255) shr 4) }
    }

    /** Luminancia media (0..255) de un trozo: para saber si la imagen es negra. */
    fun meanLuma(src: ByteArray, g: FrameGeom, tile: Int): Int {
        val k = if (g.validTile(tile)) tile else 0
        val o = ByteArray(64)
        lumaGrid(src, g, g.tileX(k), g.tileY(k), g.tileW, g.tileH, 8, 8, o)
        return o.sumOf { it.toInt() and 255 } / 64
    }

    fun jpeg(nv21: ByteArray, w: Int, h: Int, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream(maxOf(4096, w * h / 6))
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality.coerceIn(10, 95), bos)
        return bos.toByteArray()
    }

    /** JPEG que no pase de [maxBytes] (baja la calidad en escalones). */
    fun jpegMax(nv21: ByteArray, w: Int, h: Int, maxBytes: Int, q0: Int): ByteArray {
        var q = q0
        var j = jpeg(nv21, w, h, q)
        while (j.size > maxBytes && q > 20) { q -= 10; j = jpeg(nv21, w, h, q) }
        return j
    }

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
