package com.bydlink.carapp.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout

/**
 * Skins del lanzador ("Estela"): fondo quieto (un degradado y dos brillos suaves) y tarjetas de cristal
 * (relleno translúcido, luz arriba y borde fino). Nada se mueve solo: no gasta más que un fondo liso.
 */
internal class Palette(
    val id: String, val name: String, val note: String, val dark: Boolean,
    val base: IntArray,                                   // degradado de fondo, de arriba abajo
    val glowA: Int, val glowAx: Float, val glowAy: Float,
    val glowB: Int, val glowBx: Float, val glowBy: Float,
    val ink: Int, val ink2: Int, val ink3: Int,
    val accent: Int, val accent2: Int, val onAccent: Int,
    val glass: Int, val glassHi: Int, val glassLine: Int,
    val chip: Int, val chipHi: Int,
    val neon: Boolean = false,
) {
    val warn: Int get() = if (dark) 0xFFFFB547.toInt() else 0xFFA35F00.toInt()
    val alert: Int get() = if (dark) 0xFFFF5A5F.toInt() else 0xFFD6262C.toInt()
    val ok: Int get() = if (dark) 0xFF4ADE80.toInt() else 0xFF13873E.toInt()
    val divider: Int get() = Skins.alpha(ink, .12f)
    /** Color opaco para hojas y paneles que van encima de otras cosas. */
    val sheet: Int get() = Skins.mix(base.first(), base.last(), .55f)
}

internal object Skins {
    fun alpha(c: Int, a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
    fun mix(a: Int, b: Int, t: Float) = Color.rgb((Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
    private fun c(hex: String) = Color.parseColor(hex)
    private fun rgba(r: Int, g: Int, b: Int, a: Float) = Color.argb((a * 255).toInt(), r, g, b)
    private val W05 = rgba(255, 255, 255, .05f); private val W06 = rgba(255, 255, 255, .06f)
    private val W09 = rgba(255, 255, 255, .09f); private val W11 = rgba(255, 255, 255, .11f)

    /** Neones de Medianoche: nombre, neón, tono claro del mismo neón, tinta encima del neón. */
    val NEONS = linkedMapOf(
        "rosa" to arrayOf("Rosa", "#FF2BD6", "#FF7AE8", "#2A0022"), "verde" to arrayOf("Verde", "#39FF14", "#B6FF3A", "#062200"),
        "naranja" to arrayOf("Naranja", "#FF7A00", "#FFB347", "#2A1000"), "rojo" to arrayOf("Rojo", "#FF1F4B", "#FF6B81", "#2A0008"),
        "azul" to arrayOf("Azul", "#00A2FF", "#5AD1FF", "#001A2B"), "violeta" to arrayOf("Violeta", "#A64DFF", "#D19BFF", "#1A0033"),
        "amarillo" to arrayOf("Amarillo", "#F5FF00", "#FFF176", "#242600"), "cian" to arrayOf("Cian", "#00F0FF", "#7AFCFF", "#002A2C"),
    )

    fun all(neonId: String = neon): List<Palette> {
        val n = NEONS[neonId] ?: NEONS.getValue("rosa")
        val nc = c(n[1]); val nc2 = c(n[2])
        fun d3(top: String, bottom: String) = intArrayOf(c(top), c(bottom), c(bottom))
        return listOf(
            Palette("oceano", "Océano", "Oscura · azul marino", true, d3("#0A3346", "#02131C"),
                rgba(87, 240, 224, .20f), .5f, -.12f, rgba(76, 141, 255, .18f), 1.05f, 1.05f,
                c("#E9FBFF"), rgba(214, 240, 247, .72f), rgba(214, 240, 247, .46f), c("#57F0E0"), c("#4C8DFF"), c("#00292B"),
                rgba(12, 52, 70, .52f), rgba(190, 250, 255, .14f), rgba(170, 240, 255, .14f), W06, W11),
            Palette("aurora", "Aurora", "Oscura · menta", true, d3("#0D1A31", "#040811"),
                rgba(116, 225, 206, .20f), .22f, .08f, rgba(154, 134, 255, .20f), .78f, .18f,
                c("#EEF6FF"), rgba(220, 232, 255, .72f), rgba(220, 232, 255, .46f), c("#74E1CE"), c("#9A86FF"), c("#04261F"),
                rgba(18, 28, 52, .56f), rgba(200, 230, 255, .13f), rgba(190, 220, 255, .13f), W06, W11),
            Palette("horizon", "Horizon", "Oscura · atardecer", true, intArrayOf(c("#0B0E1C"), c("#2A1A30"), c("#5A2C33"), c("#1A1222"), c("#0A0D1A")),
                rgba(255, 154, 90, .34f), .5f, .52f, 0, 0f, 0f,
                c("#FFF6EC"), rgba(255, 236, 214, .72f), rgba(255, 236, 214, .46f), c("#F2C27B"), c("#FF7A5A"), c("#2B1A07"),
                rgba(44, 26, 42, .55f), rgba(255, 220, 180, .14f), rgba(255, 214, 170, .14f), W06, W11),
            Palette("pulse", "Pulse", "Oscura · eléctrica", true, d3("#0F2216", "#050B07"),
                rgba(198, 243, 107, .14f), 1f, 0f, rgba(60, 240, 160, .10f), 0f, 1f,
                c("#F1FFE9"), rgba(225, 245, 215, .70f), rgba(225, 245, 215, .44f), c("#C6F36B"), c("#3CF0A0"), c("#1B2A04"),
                rgba(20, 38, 25, .58f), rgba(220, 255, 200, .12f), rgba(200, 255, 170, .12f), W06, W11),
            Palette("porcelana", "Porcelana", "Clara · luz de día", false, intArrayOf(c("#F7F9FB"), c("#E9EEF4")),
                rgba(255, 255, 255, .9f), 0f, 0f, rgba(37, 87, 255, .10f), 1f, 1f,
                c("#0F1724"), rgba(15, 23, 36, .68f), rgba(15, 23, 36, .46f), c("#2557FF"), c("#6F95FF"), c("#FFFFFF"),
                rgba(255, 255, 255, .66f), rgba(255, 255, 255, .95f), rgba(255, 255, 255, .85f), rgba(15, 23, 36, .05f), rgba(15, 23, 36, .09f)),
            Palette("forma", "Forma", "Clara · cálida", false, intArrayOf(c("#F6F2EC"), c("#E6DFD4")),
                rgba(232, 149, 106, .16f), 1f, 0f, rgba(106, 85, 224, .10f), 0f, 1f,
                c("#1D1A22"), rgba(29, 26, 34, .68f), rgba(29, 26, 34, .46f), c("#6A55E0"), c("#E8956A"), c("#FFFFFF"),
                rgba(255, 252, 248, .64f), rgba(255, 255, 255, .92f), rgba(255, 255, 255, .80f), rgba(29, 26, 34, .05f), rgba(29, 26, 34, .09f)),
            Palette("neon", "Neón", "Magenta y cian", true, d3("#1A0B2E", "#07030F"),
                rgba(255, 43, 214, .28f), 0f, 0f, rgba(0, 229, 255, .22f), 1f, 1f,
                c("#FDF3FF"), rgba(250, 225, 255, .74f), rgba(250, 225, 255, .48f), c("#FF2BD6"), c("#00E5FF"), c("#2A0022"),
                rgba(30, 12, 50, .58f), rgba(255, 180, 245, .14f), rgba(255, 43, 214, .32f), W06, W11, neon = true),
            Palette("acido", "Ácido", "Verde y amarillo flúor", true, d3("#0C1D0D", "#030803"),
                rgba(57, 255, 20, .22f), 1f, 0f, rgba(245, 255, 0, .14f), 0f, 1f,
                c("#F4FFE9"), rgba(230, 255, 215, .74f), rgba(230, 255, 215, .48f), c("#39FF14"), c("#F5FF00"), c("#062200"),
                rgba(12, 32, 14, .60f), rgba(200, 255, 180, .12f), rgba(57, 255, 20, .28f), W06, W11, neon = true),
            Palette("mango", "Mango", "Naranja y rosa flúor", true, d3("#2A0E07", "#0F0603"),
                rgba(255, 122, 0, .26f), 0f, 0f, rgba(255, 31, 143, .20f), 1f, 1f,
                c("#FFF4EC"), rgba(255, 232, 214, .74f), rgba(255, 232, 214, .48f), c("#FF7A00"), c("#FF1F8F"), c("#2A1000"),
                rgba(48, 18, 10, .58f), rgba(255, 200, 170, .13f), rgba(255, 122, 0, .30f), W06, W11, neon = true),
            Palette("laser", "Láser", "Azul y violeta", true, d3("#0B1446", "#03041A"),
                rgba(0, 179, 255, .26f), .5f, -.1f, rgba(177, 77, 255, .22f), 1f, 1f,
                c("#EEF2FF"), rgba(220, 228, 255, .74f), rgba(220, 228, 255, .48f), c("#00B3FF"), c("#B14DFF"), c("#001A2B"),
                rgba(14, 22, 70, .58f), rgba(190, 210, 255, .13f), rgba(0, 179, 255, .30f), W06, W11, neon = true),
            Palette("rubi", "Rubí", "Rojo y naranja vivo", true, d3("#2A0714", "#0E0307"),
                rgba(255, 31, 75, .26f), 1f, 0f, rgba(255, 145, 0, .16f), 0f, 1f,
                c("#FFF0F3"), rgba(255, 222, 230, .74f), rgba(255, 222, 230, .48f), c("#FF1F4B"), c("#FF9100"), c("#2A0008"),
                rgba(46, 10, 22, .58f), rgba(255, 180, 195, .13f), rgba(255, 31, 75, .30f), W06, W11, neon = true),
            Palette("sorbete", "Sorbete", "Clara · rosa y violeta", false, intArrayOf(c("#FFF8FC"), c("#FFE6F2")),
                rgba(255, 45, 149, .20f), 0f, 0f, rgba(123, 92, 255, .18f), 1f, 1f,
                c("#1F0F1A"), rgba(31, 15, 26, .68f), rgba(31, 15, 26, .46f), c("#FF2D95"), c("#7B5CFF"), c("#FFFFFF"),
                rgba(255, 255, 255, .64f), rgba(255, 255, 255, .95f), rgba(255, 45, 149, .26f), rgba(31, 15, 26, .05f), rgba(31, 15, 26, .09f)),
            Palette("medianoche", "Medianoche", "Negro y tu neón", true, intArrayOf(c("#07070A"), c("#000000"), c("#000000")),
                alpha(nc, .13f), .5f, -.12f, 0, 0f, 0f,
                c("#F5F5F7"), rgba(245, 245, 247, .72f), rgba(245, 245, 247, .46f), nc, nc2, c(n[3]),
                rgba(255, 255, 255, .035f), rgba(255, 255, 255, .07f), alpha(nc, .34f), W05, W09, neon = true),
        )
    }

    val GROUPS = listOf("Clásicas" to listOf("oceano", "aurora", "horizon", "pulse", "porcelana", "forma"),
        "Flúor" to listOf("neon", "acido", "mango", "laser", "rubi", "sorbete"))

    @Volatile var cur: Palette = all("rosa").first(); private set
    var skin = "oceano"; private set
    var neon = "rosa"; private set
    var auto = false; private set

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        neon = p.getString("neon", null)?.takeIf { it in NEONS } ?: "rosa"
        auto = p.getBoolean("skinAuto", false)
        skin = if (auto) autoPick(ctx) else (p.getString("skin", null) ?: "oceano")
        val list = all(neon)
        cur = list.firstOrNull { it.id == skin } ?: list.first()
        skin = cur.id
    }

    fun select(ctx: Context, id: String, neonId: String = neon) {
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString("skin", id).putString("neon", neonId).putBoolean("skinAuto", false).apply()
        load(ctx)
    }

    fun setAuto(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putBoolean("skinAuto", on).apply()
        load(ctx)
    }

    /** Automática: Océano de noche y Porcelana de día, según el modo noche del sistema (si no lo dice, de noche). */
    private fun autoPick(ctx: Context): String =
        if ((ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO) "porcelana" else "oceano"

    /** Fondo quieto de la pantalla. */
    fun scene(p: Palette, w: Int, h: Int, corner: Float = 0f): Drawable {
        val layers = ArrayList<Drawable>()
        layers += GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, p.base).apply { cornerRadius = corner }
        val r = maxOf(w, h) * .6f
        fun glow(color: Int, x: Float, y: Float) {
            if (Color.alpha(color) == 0) return
            layers += GradientDrawable().apply {
                setGradientType(GradientDrawable.RADIAL_GRADIENT); setColors(intArrayOf(color, color and 0x00FFFFFF))
                setGradientRadius(r); setGradientCenter(x, y); cornerRadius = corner
            }
        }
        glow(p.glowA, p.glowAx, p.glowAy); glow(p.glowB, p.glowBx, p.glowBy)
        return LayerDrawable(layers.toTypedArray())
    }

    /** Tarjeta de cristal: relleno translúcido, borde fino y una luz suave arriba. */
    fun glass(p: Palette, radius: Float, stroke: Int, fill: Int = p.glass, line: Int = p.glassLine): Drawable {
        val body = GradientDrawable().apply { setColor(fill); cornerRadius = radius; setStroke(stroke, line) }
        val hi = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(p.glassHi, p.glassHi and 0x00FFFFFF, 0)).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(body, hi))
    }

    /** Botón o chip teñido del acento (seleccionado). */
    fun tinted(p: Palette, radius: Float, stroke: Int) = GradientDrawable().apply {
        setColor(alpha(p.accent, .16f)); cornerRadius = radius; setStroke(stroke, alpha(p.accent, .45f))
    }

    /** Miniatura de una skin para el selector: su fondo, una tarjeta y la raya del acento. */
    fun preview(p: Palette, w: Int, h: Int, radius: Float): Drawable {
        val bg = scene(p, w, h, radius)
        val cardD = glass(p, radius * .6f, maxOf(1, (radius / 12).toInt()))
        val line = GradientDrawable().apply { setColor(p.accent); cornerRadius = 4f }
        return LayerDrawable(arrayOf(bg, cardD, line)).apply {
            setLayerInset(1, w / 6, h / 5, w / 6, h * 3 / 10)
            setLayerInset(2, w / 10, h * 8 / 10, w / 10, h / 10)
        }
    }
}

/** Anillo de energía (autonomía o carga). Se pinta solo cuando cambia el valor. */
internal class Gauge(ctx: Context, private val p: Palette, private val density: Float) : View(ctx) {
    private var v = -1f
    private var charging = false
    private val sw = 13f * density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; color = p.chipHi }
    private val ticks = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density; color = p.ink3; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    init { if (p.neon) setLayerType(LAYER_TYPE_SOFTWARE, null) }    // el brillo del neón (sombra) necesita capa software en Android 7

    fun set(value: Float, chg: Boolean) {
        val nv = value.coerceIn(0f, 1f)
        if (nv == v && chg == charging) return
        v = nv; charging = chg; invalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat(); if (s <= 0f) return
        val cx = width / 2f; val cy = height / 2f
        val r = s / 2f - sw - 9f * density
        rect.set(cx - r, cy - r, cx + r, cy + r)
        for (i in 0 until 40) {
            val a = Math.toRadians(i * 9.0 - 90.0)
            val r1 = r + sw * .85f; val r2 = r1 + (if (i % 4 == 0) 6f else 3f) * density
            c.drawLine(cx + (Math.cos(a) * r1).toFloat(), cy + (Math.sin(a) * r1).toFloat(), cx + (Math.cos(a) * r2).toFloat(), cy + (Math.sin(a) * r2).toFloat(), ticks)
        }
        c.drawOval(rect, track)
        if (v <= 0f) return
        val a1 = if (charging) p.ok else p.accent2; val a2 = if (charging) p.ok else p.accent
        fill.shader = SweepGradient(cx, cy, intArrayOf(a1, a2, a2), floatArrayOf(0f, v.coerceAtLeast(.02f), 1f)).apply {
            setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) })
        }
        if (p.neon) fill.setShadowLayer(10f * density, 0f, 0f, Skins.alpha(a2, .85f)) else fill.clearShadowLayer()
        c.drawArc(rect, -90f, 360f * v, false, fill)
    }
}

/**
 * Marca de BYDLink (la "B" de dos eslabones) dibujada en código: el eslabón de arriba se recorta por detrás del de
 * abajo con un recorte de verdad, así queda bien sobre cualquier fondo. Colores del acento de la skin.
 */
internal class BrandMark(private val p: Palette) : Drawable() {
    private val up = Path().apply { moveTo(17f, 9f); lineTo(31f, 9f); arcTo(RectF(18f, 9f, 44f, 35f), -90f, 180f); lineTo(17f, 35f); close() }
    private val low = Path().apply { moveTo(17f, 25f); lineTo(33f, 25f); arcTo(RectF(18f, 25f, 48f, 55f), -90f, 180f); lineTo(17f, 55f); close() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6f; strokeJoin = Paint.Join.ROUND
        shader = android.graphics.LinearGradient(12f, 8f, 52f, 56f, p.accent, p.accent2, android.graphics.Shader.TileMode.CLAMP)
    }
    private val hole = Path().apply {
        val band = Path(); Paint().apply { style = Paint.Style.STROKE; strokeWidth = 10f; strokeJoin = Paint.Join.ROUND }.getFillPath(low, band)
        addPath(low); op(band, Path.Op.UNION)
    }
    @Suppress("DEPRECATION")
    override fun draw(c: Canvas) {
        val b = bounds; if (b.isEmpty) return
        val s = minOf(b.width(), b.height()) / 64f
        c.save()
        c.translate(b.left + (b.width() - 64f * s) / 2f, b.top + (b.height() - 64f * s) / 2f); c.scale(s, s)
        c.save(); c.clipPath(hole, Region.Op.DIFFERENCE); c.drawPath(up, stroke); c.restore()
        c.drawPath(low, stroke)
        c.restore()
    }
    override fun setAlpha(alpha: Int) { stroke.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { stroke.colorFilter = cf }
    @Deprecated("Drawable") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Cuadrado del alto que le den (para el anillo). */
internal class SquareFrame(ctx: Context) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val spec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
    }
}

/** Cambio de skin: la pantalla de antes encima, con un círculo que se abre desde el dedo y deja ver la nueva. */
internal class HoleReveal(ctx: Context, private val bmp: Bitmap, private val cx: Float, private val cy: Float) : View(ctx) {
    private val path = Path()
    private var r = 0f
    fun start(onEnd: () -> Unit) {
        val max = Math.hypot(maxOf(cx, bmp.width - cx).toDouble(), maxOf(cy, bmp.height - cy).toDouble()).toFloat()
        ValueAnimator.ofFloat(0f, max).apply {
            duration = 520; interpolator = PathInterpolator(.77f, 0f, .175f, 1f)
            addUpdateListener { r = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { onEnd() } })
            start()
        }
    }
    @Suppress("DEPRECATION")
    override fun onDraw(c: Canvas) {
        path.reset(); path.addCircle(cx, cy, r, Path.Direction.CW)
        c.save(); c.clipPath(path, Region.Op.DIFFERENCE); c.drawBitmap(bmp, 0f, 0f, null); c.restore()
    }
}
