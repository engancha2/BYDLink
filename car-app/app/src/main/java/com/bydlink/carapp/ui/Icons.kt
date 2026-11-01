package com.bydlink.carapp.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Iconos de línea del lanzador (estilo del boceto), dibujados en una rejilla de 24×24 y escalados al tamaño del hueco.
 * Sin fuentes de iconos ni imágenes: así se ven igual en cualquier DiLink.
 */
internal class LineIcon(private val kind: String, color: Int, private val stroke: Float = 2f) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.exactCenterX() - 12 * s, b.exactCenterY() - 12 * s)
        c.scale(s, s)
        p.strokeWidth = stroke
        shape(c)
        c.restore()
    }

    private fun path(vararg xy: Float, close: Boolean = false) = Path().apply {
        moveTo(xy[0], xy[1]); var i = 2
        while (i < xy.size) { lineTo(xy[i], xy[i + 1]); i += 2 }
        if (close) close()
    }
    private fun Canvas.line(vararg xy: Float) = drawPath(path(*xy), p)
    private fun Canvas.poly(vararg xy: Float) = drawPath(path(*xy, close = true), p)
    private fun Canvas.rr(l: Float, t: Float, r: Float, b: Float, rad: Float) = drawRoundRect(RectF(l, t, r, b), rad, rad, p)
    private fun Canvas.circle(x: Float, y: Float, r: Float) = drawCircle(x, y, r, p)
    private fun Canvas.arc(l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float) = drawArc(RectF(l, t, r, b), start, sweep, false, p)

    private fun shape(c: Canvas) {
        when (kind) {
            "home" -> { c.line(3f, 11f, 12f, 4f, 21f, 11f); c.line(6f, 9.5f, 6f, 20f, 18f, 20f, 18f, 9.5f); c.line(10f, 20f, 10f, 14f, 14f, 14f, 14f, 20f) }
            "car" -> {
                c.line(3f, 13f, 5.5f, 7f, 18.5f, 7f, 21f, 13f); c.rr(2f, 12f, 22f, 18f, 2.5f)
                c.circle(7f, 18f, 2f); c.circle(17f, 18f, 2f); c.line(6f, 15f, 6.5f, 15f); c.line(17.5f, 15f, 18f, 15f)
            }
            "battery" -> { c.rr(2f, 7f, 19f, 17f, 2.5f); c.line(21.5f, 10.5f, 21.5f, 13.5f); c.line(11.5f, 9f, 9f, 12.5f, 12f, 12.5f, 9.5f, 16f) }
            "map" -> { c.poly(3f, 6f, 9f, 3.5f, 15f, 6f, 21f, 3.5f, 21f, 18f, 15f, 20.5f, 9f, 18f, 3f, 20.5f); c.line(9f, 3.5f, 9f, 18f); c.line(15f, 6f, 15f, 20.5f) }
            "music" -> { c.line(9f, 17.5f, 9f, 5f, 20f, 3f, 20f, 15.5f); c.circle(6.5f, 17.5f, 2.5f); c.circle(17.5f, 15.5f, 2.5f); c.line(9f, 9f, 20f, 7f) }
            "camera" -> { c.rr(2.5f, 7f, 21.5f, 19.5f, 2.5f); c.line(8f, 7f, 9.5f, 4.5f, 14.5f, 4.5f, 16f, 7f); c.circle(12f, 13f, 3.5f) }
            "split" -> { c.rr(3f, 4f, 21f, 20f, 2.5f); c.line(12f, 4f, 12f, 20f) }
            "grid" -> { c.rr(3.5f, 3.5f, 10.5f, 10.5f, 2f); c.rr(13.5f, 3.5f, 20.5f, 10.5f, 2f); c.rr(3.5f, 13.5f, 10.5f, 20.5f, 2f); c.rr(13.5f, 13.5f, 20.5f, 20.5f, 2f) }
            "thermo" -> { c.line(10f, 14.5f, 10f, 5f); c.arc(10f, 3f, 14f, 7f, 180f, 180f); c.line(14f, 5f, 14f, 14.5f); c.circle(12f, 17.5f, 3.5f) }
            "fan" -> {
                c.circle(12f, 12f, 1.6f)
                for (k in 0 until 3) {
                    c.save(); c.rotate(k * 120f, 12f, 12f)
                    c.drawPath(Path().apply { moveTo(12f, 10.4f); cubicTo(9f, 7f, 10f, 2.5f, 13f, 3f); cubicTo(16f, 3.5f, 15f, 8f, 12f, 10.4f) }, p)
                    c.restore()
                }
            }
            "wifi" -> { c.arc(2f, 6f, 22f, 26f, 225f, 90f); c.arc(5.5f, 9.5f, 18.5f, 22.5f, 225f, 90f); c.arc(9f, 13f, 15f, 19f, 225f, 90f); c.drawCircle(12f, 19.5f, 1.2f, fill) }
            "signal" -> { c.line(5f, 19f, 5f, 16f); c.line(10f, 19f, 10f, 12f); c.line(15f, 19f, 15f, 8f); c.line(20f, 19f, 20f, 4f) }
            "cloud" -> c.drawPath(Path().apply {
                moveTo(7f, 18.5f); lineTo(17.5f, 18.5f); cubicTo(20.5f, 18.5f, 22f, 16f, 21.5f, 13.5f); cubicTo(21f, 11f, 18.5f, 10f, 17f, 10.5f)
                cubicTo(16f, 6.5f, 11.5f, 5f, 8.5f, 7.5f); cubicTo(7f, 8.8f, 6.5f, 10.5f, 6.8f, 11.8f); cubicTo(4.5f, 12f, 2.5f, 13.8f, 2.7f, 15.7f)
                cubicTo(2.9f, 17.4f, 4.5f, 18.5f, 7f, 18.5f) }, p)
            "sun" -> { c.circle(12f, 12f, 4f); for (k in 0 until 8) { c.save(); c.rotate(k * 45f, 12f, 12f); c.line(12f, 2.5f, 12f, 4.5f); c.restore() } }
            "rain" -> { shapeOf("cloud", c, dy = -2f); c.line(8f, 19f, 7f, 21.5f); c.line(12f, 19f, 11f, 21.5f); c.line(16f, 19f, 15f, 21.5f) }
            "snow" -> { for (k in 0 until 3) { c.save(); c.rotate(k * 60f, 12f, 12f); c.line(12f, 3f, 12f, 21f); c.line(10f, 5f, 12f, 7f, 14f, 5f); c.line(10f, 19f, 12f, 17f, 14f, 19f); c.restore() } }
            "fog" -> { c.line(4f, 9f, 20f, 9f); c.line(3f, 13f, 18f, 13f); c.line(6f, 17f, 21f, 17f) }
            "storm" -> { shapeOf("cloud", c, dy = -3f); c.line(12.5f, 15f, 10.5f, 18.5f, 13.5f, 18.5f, 11.5f, 22f) }
            "wind" -> {
                c.drawPath(Path().apply { moveTo(3f, 9f); lineTo(14f, 9f); cubicTo(17f, 9f, 17f, 4.5f, 14.5f, 4.5f); cubicTo(13f, 4.5f, 12.5f, 5.5f, 12.5f, 6.3f) }, p)
                c.drawPath(Path().apply { moveTo(3f, 13f); lineTo(18f, 13f); cubicTo(21.5f, 13f, 21.5f, 18f, 18.5f, 18f); cubicTo(17f, 18f, 16.3f, 17f, 16.3f, 16.2f) }, p)
                c.line(3f, 17f, 10f, 17f)
            }
            "drop" -> c.drawPath(Path().apply { moveTo(12f, 3f); cubicTo(12f, 3f, 5.5f, 10f, 5.5f, 14.5f); cubicTo(5.5f, 18.5f, 8.5f, 21f, 12f, 21f)
                cubicTo(15.5f, 21f, 18.5f, 18.5f, 18.5f, 14.5f); cubicTo(18.5f, 10f, 12f, 3f, 12f, 3f) }, p)
            "nav" -> c.poly(3f, 11f, 21f, 3f, 13f, 21f, 11f, 13f)
            "search" -> { c.circle(10.5f, 10.5f, 6.5f); c.line(15.5f, 15.5f, 21f, 21f) }
            "lock" -> { c.rr(5f, 11f, 19f, 21f, 2f); c.line(8f, 11f, 8f, 7.5f); c.arc(8f, 3.5f, 16f, 11.5f, 180f, 180f); c.line(16f, 7.5f, 16f, 11f) }
            "unlock" -> { c.rr(5f, 11f, 19f, 21f, 2f); c.line(8f, 11f, 8f, 7.5f); c.arc(8f, 3.5f, 16f, 11.5f, 180f, 160f) }
            "plug" -> { c.line(9f, 2.5f, 9f, 7f); c.line(15f, 2.5f, 15f, 7f); c.drawPath(Path().apply { moveTo(6f, 7f); lineTo(18f, 7f); lineTo(18f, 11f)
                cubicTo(18f, 14.5f, 15.5f, 16.5f, 12f, 16.5f); cubicTo(8.5f, 16.5f, 6f, 14.5f, 6f, 11f); close() }, p); c.line(12f, 16.5f, 12f, 21.5f) }
            "pulse" -> c.line(2.5f, 12f, 6.5f, 12f, 9f, 5f, 15f, 19f, 17.5f, 12f, 21.5f, 12f)
            "clock" -> { c.circle(12f, 12f, 9f); c.line(12f, 7f, 12f, 12f, 15.5f, 14f) }
            "leaf" -> { c.drawPath(Path().apply { moveTo(5f, 19f); cubicTo(4f, 11f, 9f, 4.5f, 20f, 4f); cubicTo(20f, 14f, 14f, 19.5f, 5f, 19f) }, p); c.line(5f, 19f, 13f, 11f) }
            "shield" -> c.drawPath(Path().apply { moveTo(12f, 2.5f); lineTo(20f, 5.5f); lineTo(20f, 11f); cubicTo(20f, 16f, 16.5f, 19.8f, 12f, 21.5f)
                cubicTo(7.5f, 19.8f, 4f, 16f, 4f, 11f); lineTo(4f, 5.5f); close() }, p)
            "rec" -> { c.circle(12f, 12f, 8.5f); c.drawCircle(12f, 12f, 4f, fill) }
            "photo" -> { c.rr(3f, 5f, 21f, 19f, 2.5f); c.circle(9f, 10f, 1.8f); c.line(3.5f, 17f, 9f, 12.5f, 12.5f, 15.5f, 15.5f, 12.5f, 20.5f, 17f) }
            "gauge" -> { c.arc(3f, 5f, 21f, 23f, 180f, 180f); c.line(12f, 14f, 16f, 9f); c.drawCircle(12f, 14f, 1.4f, fill) }
            "seat" -> c.line(7f, 3.5f, 9f, 13f, 17f, 13f, 18.5f, 18.5f, 9.5f, 18.5f, 6.5f, 20.5f)
            "light" -> { c.drawPath(Path().apply { moveTo(10f, 5f); cubicTo(4f, 5f, 4f, 19f, 10f, 19f); close() }, p); c.line(14f, 7f, 21f, 7f); c.line(14f, 12f, 21f, 12f); c.line(14f, 17f, 21f, 17f) }
            "gear" -> { c.circle(12f, 12f, 6.3f); c.circle(12f, 12f, 2.6f); for (k in 0 until 8) { c.save(); c.rotate(k * 45f, 12f, 12f); c.rr(10.6f, 2f, 13.4f, 5.6f, 0.8f); c.restore() } }
            "snowflake" -> shapeOf("snow", c)
            "apps" -> { for (i in 0..2) for (j in 0..2) c.drawCircle(5f + i * 7f, 5f + j * 7f, 1.6f, fill) }
            "phone" -> c.drawPath(Path().apply { moveTo(5f, 3.5f); lineTo(9f, 3.5f); lineTo(10.5f, 8f); lineTo(8.3f, 9.5f); cubicTo(9.3f, 11.8f, 12.2f, 14.7f, 14.5f, 15.7f)
                lineTo(16f, 13.5f); lineTo(20.5f, 15f); lineTo(20.5f, 19f); cubicTo(20.5f, 20f, 19.5f, 20.6f, 18.5f, 20.5f); cubicTo(10.5f, 20f, 4f, 13.5f, 3.5f, 5.5f)
                cubicTo(3.4f, 4.5f, 4f, 3.5f, 5f, 3.5f) }, p)
            "smartphone" -> { c.rr(6.5f, 2.5f, 17.5f, 21.5f, 2.5f); c.line(11f, 18.5f, 13f, 18.5f) }
            "download" -> { c.line(12f, 3.5f, 12f, 15f); c.line(7f, 10f, 12f, 15f, 17f, 10f); c.line(4f, 16f, 4f, 20f, 20f, 20f, 20f, 16f) }
            "usb" -> { c.line(12f, 3f, 12f, 21f); c.line(9.5f, 5.5f, 12f, 3f, 14.5f, 5.5f); c.line(12f, 15f, 7f, 12f, 7f, 9f); c.line(12f, 17f, 17f, 14f, 17f, 11f); c.circle(12f, 19.5f, 1.5f) }
            "swap" -> { c.line(4f, 8f, 20f, 8f); c.line(16f, 4f, 20f, 8f, 16f, 12f); c.line(20f, 16f, 4f, 16f); c.line(8f, 12f, 4f, 16f, 8f, 20f) }
            "expand" -> { c.line(14f, 4f, 20f, 4f, 20f, 10f); c.line(20f, 4f, 13.5f, 10.5f); c.line(10f, 20f, 4f, 20f, 4f, 14f); c.line(4f, 20f, 10.5f, 13.5f) }
            "warn" -> { c.poly(12f, 3.5f, 21.5f, 20f, 2.5f, 20f); c.line(12f, 9.5f, 12f, 14f); c.drawCircle(12f, 17f, 1.1f, fill) }
            "check" -> c.line(4.5f, 12.5f, 9.5f, 17.5f, 19.5f, 6.5f)
            "door" -> { c.line(5f, 21f, 5f, 4f, 15f, 2.5f, 15f, 21f); c.line(3f, 21f, 21f, 21f); c.line(15f, 4f, 19f, 4f, 19f, 21f); c.drawCircle(12.3f, 12f, 1f, fill) }
            "window" -> { c.rr(3f, 4f, 21f, 20f, 2f); c.line(3f, 9f, 21f, 9f); c.line(12f, 9f, 12f, 20f) }
            "tyre" -> { c.circle(12f, 12f, 9f); c.circle(12f, 12f, 4f); c.line(12f, 3f, 12f, 8f); c.line(12f, 16f, 12f, 21f); c.line(3f, 12f, 8f, 12f); c.line(16f, 12f, 21f, 12f) }
            "wheel" -> { c.circle(12f, 12f, 9f); c.circle(12f, 12f, 2.5f); c.line(3.5f, 11f, 9.5f, 12f); c.line(14.5f, 12f, 20.5f, 11f); c.line(12f, 14.5f, 12f, 21f) }
            "power" -> { c.arc(4f, 4f, 20f, 20f, -60f, 300f); c.line(12f, 2.5f, 12f, 11f) }
            "bluetooth" -> c.line(7f, 7f, 17f, 16.5f, 12f, 21f, 12f, 3f, 17f, 7.5f, 7f, 17f)
            "plus" -> { c.line(12f, 5f, 12f, 19f); c.line(5f, 12f, 19f, 12f) }
            "minus" -> c.line(5f, 12f, 19f, 12f)
            "x" -> { c.line(6f, 6f, 18f, 18f); c.line(18f, 6f, 6f, 18f) }
            "right" -> c.line(9f, 5f, 16f, 12f, 9f, 19f)
            "back" -> c.line(15f, 5f, 8f, 12f, 15f, 19f)
            else -> c.circle(12f, 12f, 8f)
        }
    }

    /** Reutiliza otra figura (p. ej. la nube debajo de la lluvia) desplazada en vertical. */
    private fun shapeOf(k: String, c: Canvas, dy: Float = 0f) {
        c.save(); c.translate(0f, dy)
        LineIcon(k, p.color, stroke).also { it.p.strokeWidth = stroke }.shapeInto(c)
        c.restore()
    }
    private fun shapeInto(c: Canvas) = shape(c)

    override fun setAlpha(alpha: Int) { p.alpha = alpha; fill.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf; fill.colorFilter = cf }
    @Deprecated("Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
