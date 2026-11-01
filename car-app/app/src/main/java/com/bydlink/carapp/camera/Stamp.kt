package com.bydlink.carapp.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Sello de dashcam abajo a la izquierda del vídeo ("01/11/2026 08:15:02 · 52 km/h · 74 %"): se dibuja el texto en un
 * bitmap pequeño una vez por segundo y se mezcla con el NV21 de cada frame (brillo y color neutro donde hay letra).
 */
class Stamp {
    private var text = ""
    private var w = 0
    private var bandH = 0
    private var px: IntArray? = null

    fun apply(r: Rendered, line: String) {
        if (line != text || r.w != w) build(r.w, r.h, line)
        val m = px ?: return
        val y0 = (r.h - bandH - 4) and 1.inv()
        if (y0 < 0) return
        val src = r.nv21
        for (y in 0 until bandH) {
            val row = (y0 + y) * r.w; val mrow = y * w
            for (x in 0 until w) {
                val c = m[mrow + x]; val a = c ushr 24
                if (a == 0) continue
                val old = src[row + x].toInt() and 0xFF
                src[row + x] = ((old * (255 - a) + luma(c) * a) / 255).toByte()
            }
        }
        val ySize = r.w * r.h
        for (y in 0 until bandH / 2) {
            val crow = ySize + (y0 / 2 + y) * r.w; val mrow = y * 2 * w
            for (x in 0 until w / 2) {
                val a = m[mrow + x * 2] ushr 24
                if (a == 0) continue
                val i = crow + x * 2
                src[i] = mix(src[i], a); src[i + 1] = mix(src[i + 1], a)
            }
        }
    }

    private fun mix(b: Byte, a: Int) = (((b.toInt() and 0xFF) * (255 - a) + 128 * a) / 255).toByte()

    private fun luma(c: Int): Int {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return 16 + ((66 * r + 129 * g + 25 * b + 128) shr 8)
    }

    private fun build(w: Int, h: Int, line: String) {
        val bh = ((h / 18).coerceAtLeast(18)) and 1.inv()
        val bmp = Bitmap.createBitmap(w, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = bh * 0.72f; color = Color.WHITE; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val tw = minOf(p.measureText(line) + 16f, w - 8f)
        c.drawRoundRect(RectF(6f, 0f, 6f + tw, bh.toFloat()), 4f, 4f, Paint().apply { color = 0x8C000000.toInt() })
        c.drawText(line, 14f, bh * 0.77f, p)
        px = IntArray(w * bh).also { bmp.getPixels(it, 0, w, 0, 0, w, bh) }
        bmp.recycle()
        this.w = w; bandH = bh; text = line
    }
}
