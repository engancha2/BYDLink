package com.bydlink.carapp.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.model.CarState
import com.bydlink.carapp.service.StateHub
import com.bydlink.carapp.ui.HomeActivity.Companion.ACCENT
import com.bydlink.carapp.ui.HomeActivity.Companion.AMBER
import com.bydlink.carapp.ui.HomeActivity.Companion.MUTED
import com.bydlink.carapp.ui.HomeActivity.Companion.TEXT

/**
 * Página Coche del lanzador: el coche visto desde arriba con las cuatro ruedas (presión y temperatura) y lo abierto
 * marcado, y al lado el estado de cierre, puertas, ventanas, conducción, cinturones, luces y temperaturas.
 */
internal fun HomeActivity.infoPage(): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, dp(4), dp(8)) }
    val carCard = card(); row.addView(carCard, LinearLayout.LayoutParams(dp(330), -1).apply { rightMargin = dp(12) })
    carCard.addView(label("Tu coche", 16f, MUTED, bold = true))
    val car = CarTopView(this); carCard.addView(car, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })

    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun section(title: String, vararg keys: String): Map<String, android.widget.TextView> {
        val c = card(); col.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        c.addView(label(title, 16f, MUTED, bold = true))
        return keys.associateWith { kv(c, it) }
    }
    val a = section("Cierre y puertas", "Cierre", "Puertas", "Maletero", "Ventana del conductor")
    val b = section("Conducción", "Marcha", "Freno de mano", "Velocidad", "Cinturón del conductor", "Luces")
    val c = section("Temperaturas y otros", "Exterior", "Interior", "Kilómetros", "Versión de BYDLink")
    row.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(0, -1, 1f))

    refreshTab = {
        val s = StateHub.state
        car.state = s; car.invalidate()
        a["Cierre"]?.text = when (s?.locked) { true -> "Cerrado"; false -> "Abierto"; null -> "--" }
        a["Cierre"]?.setTextColor(if (s?.locked == false) AMBER else Color.WHITE)
        val d = s?.doors
        val open = listOfNotNull(d?.fl.takeIf { it == true }?.let { "del. izq." }, d?.fr.takeIf { it == true }?.let { "del. der." },
            d?.rl.takeIf { it == true }?.let { "tras. izq." }, d?.rr.takeIf { it == true }?.let { "tras. der." })
        a["Puertas"]?.text = if (s == null) "--" else if (open.isEmpty()) "Cerradas" else "Abierta: ${open.joinToString(", ")}"
        a["Puertas"]?.setTextColor(if (open.isNotEmpty()) AMBER else Color.WHITE)
        a["Maletero"]?.text = when (d?.trunk) { true -> "Abierto"; false -> "Cerrado"; null -> "--" }
        a["Ventana del conductor"]?.text = s?.windowsPct?.fl?.let { if (it > 0) "Abierta $it %" else "Cerrada" }
            ?: s?.windows?.fl?.let { if (it) "Abierta" else "Cerrada" } ?: "--"
        b["Marcha"]?.text = s?.gear ?: "--"
        b["Freno de mano"]?.text = s?.parkBrake?.let { if (it) "Echado" else "Quitado" } ?: "--"
        b["Velocidad"]?.text = s?.let { "${it.speed} km/h" } ?: "--"
        b["Cinturón del conductor"]?.text = s?.seatbelts?.driver?.let { if (it) "Puesto" else "Sin poner" } ?: "--"
        val l = s?.lights
        b["Luces"]?.text = listOfNotNull(l?.lowBeam.takeIf { it == true }?.let { "cortas" }, l?.highBeam.takeIf { it == true }?.let { "largas" },
            l?.position.takeIf { it == true }?.let { "posición" }, l?.drl.takeIf { it == true }?.let { "diurnas" },
            l?.rearFog.takeIf { it == true }?.let { "antiniebla" }).joinToString(", ").ifEmpty { if (l == null) "--" else "Apagadas" }
        c["Exterior"]?.text = s?.outTemp?.let { "$it °${s.tempUnit}" } ?: "--"
        c["Interior"]?.text = s?.inTemp?.let { "$it °${s.tempUnit}" } ?: "--"
        c["Kilómetros"]?.text = s?.odometer?.let { String.format(java.util.Locale.getDefault(), "%,d km", it) } ?: "--"
        c["Versión de BYDLink"]?.text = BuildConfigCompat.appVersion(this)
    }
    return row
}

/** El coche visto desde arriba: carrocería, puertas/maletero abiertos en ámbar y las ruedas con presión y temperatura. */
internal class CarTopView(a: HomeActivity) : View(a) {
    var state: CarState? = null
    private val dpx = a.resources.displayMetrics.density
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A3442.toInt() }
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF16202B.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x55FFFFFF; strokeWidth = 1.5f * dpx }
    private val open = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = AMBER; strokeWidth = 4f * dpx; strokeCap = Paint.Cap.ROUND }
    private val wheel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0F14.toInt() }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 17f * dpx; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = 13f * dpx; textAlign = Paint.Align.CENTER }

    override fun onDraw(c: Canvas) {
        val s = state
        val w = width.toFloat(); val h = height.toFloat()
        val cw = minOf(w * 0.42f, h * 0.42f); val ch = cw * 2.05f
        val left = (w - cw) / 2; val top = (h - ch) / 2
        val r = RectF(left, top, left + cw, top + ch)
        // ruedas (debajo de la carrocería)
        val ww = cw * 0.16f; val wh = ch * 0.15f
        val wy = floatArrayOf(top + ch * 0.13f, top + ch * 0.72f)
        for (y in wy) { c.drawRoundRect(RectF(left - ww * 0.55f, y, left + ww * 0.45f, y + wh), 6 * dpx, 6 * dpx, wheel)
                        c.drawRoundRect(RectF(left + cw - ww * 0.45f, y, left + cw + ww * 0.55f, y + wh), 6 * dpx, 6 * dpx, wheel) }
        c.drawRoundRect(r, cw * 0.35f, cw * 0.35f, body)
        c.drawRoundRect(r, cw * 0.35f, cw * 0.35f, line)
        // parabrisas, techo y luneta
        c.drawRoundRect(RectF(left + cw * 0.12f, top + ch * 0.22f, left + cw * 0.88f, top + ch * 0.36f), 10 * dpx, 10 * dpx, glass)
        c.drawRoundRect(RectF(left + cw * 0.15f, top + ch * 0.38f, left + cw * 0.85f, top + ch * 0.68f), 10 * dpx, 10 * dpx, Paint(glass).apply { alpha = 120 })
        c.drawRoundRect(RectF(left + cw * 0.15f, top + ch * 0.72f, left + cw * 0.85f, top + ch * 0.82f), 10 * dpx, 10 * dpx, glass)
        // puertas y maletero abiertos
        val d = s?.doors
        if (d?.fl == true) c.drawLine(left, top + ch * 0.36f, left - cw * 0.22f, top + ch * 0.30f, open)
        if (d?.fr == true) c.drawLine(left + cw, top + ch * 0.36f, left + cw * 1.22f, top + ch * 0.30f, open)
        if (d?.rl == true) c.drawLine(left, top + ch * 0.58f, left - cw * 0.22f, top + ch * 0.52f, open)
        if (d?.rr == true) c.drawLine(left + cw, top + ch * 0.58f, left + cw * 1.22f, top + ch * 0.52f, open)
        if (d?.trunk == true) c.drawLine(left + cw * 0.2f, top + ch, left + cw * 0.8f, top + ch, open)
        if (s?.locked == false) c.drawText("ABIERTO", w / 2, top + ch * 0.55f, Paint(small).apply { color = AMBER; isFakeBoldText = true })
        else if (s?.locked == true) c.drawText("CERRADO", w / 2, top + ch * 0.55f, Paint(small).apply { color = ACCENT; isFakeBoldText = true })
        // presión y temperatura junto a cada rueda
        val ty = s?.tyres; val tt = s?.tyreTemps
        fun tyre(p: Double?, t: Int?, x: Float, y: Float) {
            val bad = p != null && (p < 2.0 || p > 3.0)
            c.drawText(p?.let { String.format(java.util.Locale.getDefault(), "%.1f", it) } ?: "--", x, y, Paint(big).apply { if (bad) color = AMBER })
            c.drawText(listOfNotNull("bar", t?.let { "$it°" }).joinToString(" · "), x, y + 16 * dpx, small)
        }
        val lx = left - cw * 0.55f; val rx = left + cw * 1.55f
        tyre(ty?.fl, tt?.fl, lx, wy[0] + wh * 0.55f); tyre(ty?.fr, tt?.fr, rx, wy[0] + wh * 0.55f)
        tyre(ty?.rl, tt?.rl, lx, wy[1] + wh * 0.55f); tyre(ty?.rr, tt?.rr, rx, wy[1] + wh * 0.55f)
        c.drawText("DELANTE", w / 2, top - 8 * dpx, small)
        if (s?.speed ?: 0 > 0) c.drawText("${s?.speed} km/h", w / 2, top + ch + 22 * dpx, Paint(big).apply { color = TEXT })
    }
}
