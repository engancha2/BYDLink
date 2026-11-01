package com.bydlink.carapp.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bydlink.carapp.App
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.service.StateHub
import com.bydlink.carapp.ui.HomeActivity.Companion.ACCENT
import com.bydlink.carapp.ui.HomeActivity.Companion.AMBER
import com.bydlink.carapp.ui.HomeActivity.Companion.MUTED
import com.bydlink.carapp.ui.HomeActivity.Companion.RED
import com.bydlink.carapp.ui.HomeActivity.Companion.TEXT
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

/**
 * Página Energía del lanzador: batería (carga, autonomía, salud, temperaturas, 12 V, voltaje y corriente del BMS),
 * la carga en curso con el límite (códigos de BYD), consumos e historial de las últimas cargas (lo que guarda el
 * Recorder en la nube).
 */
internal fun HomeActivity.energyPage(): View {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), dp(8)) }

    // ── fila 1: batería grande + salud ──
    val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    col.addView(row1)
    val batt = card(); row1.addView(batt, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12) })
    batt.addView(label("Batería", 16f, MUTED, bold = true))
    val bRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val ring = SocRing(this); bRow.addView(ring, LinearLayout.LayoutParams(dp(140), dp(140)))
    val bCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, 0, 0) }
    val range = label("--", 30f, Color.WHITE, bold = true); val rangeK = label("de autonomía", 14f, MUTED)
    val kwh = label("--", 22f, Color.WHITE, bold = true); val kwhK = label("en la batería", 14f, MUTED)
    bCol.addView(range); bCol.addView(rangeK); bCol.addView(kwh.apply { setPadding(0, dp(10), 0, 0) }); bCol.addView(kwhK)
    bRow.addView(bCol); batt.addView(bRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    val health = card(); row1.addView(health, LinearLayout.LayoutParams(0, -2, 1f))
    health.addView(label("Salud y temperatura", 16f, MUTED, bold = true))
    val soh = kv(health, "Salud (SOH)"); val temp = kv(health, "Temperatura de celdas")
    val v12 = kv(health, "Batería 12 V"); val hv = kv(health, "Voltaje de tracción"); val amp = kv(health, "Corriente")

    // ── fila 2: carga ──
    val chg = card(); col.addView(chg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    val cHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val cTitle = label("Carga", 18f, Color.WHITE, bold = true); cHead.addView(cTitle, LinearLayout.LayoutParams(0, -2, 1f))
    val cPower = label("", 28f, ACCENT, bold = true); cHead.addView(cPower)
    chg.addView(cHead)
    val cSub = label("", 15f, TEXT).apply { setPadding(0, dp(4), 0, 0) }; chg.addView(cSub)
    val cBar = Bar(ACCENT); chg.addView(cBar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(10) })
    chg.addView(label("Límite de carga (como en los ajustes de BYD)", 14f, MUTED).apply { setPadding(0, dp(14), 0, dp(6)) })
    val lRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val limBtns = listOf(60, 70, 80, 90, 100).map { p ->
        pill(if (p == 100) "Sin límite" else "$p %") { send("setChargeLimit", mapOf("percent" to p), if (p == 100) "Sin límite de carga" else "Límite al $p %") }
            .also { lRow.addView(it, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) }) } to p
    }
    chg.addView(lRow)
    val lHint = label("", 13f, MUTED).apply { setPadding(0, dp(8), 0, 0) }; chg.addView(lHint)

    // ── fila 3: consumos ──
    val cons = card(); col.addView(cons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    cons.addView(label("Consumo", 16f, MUTED, bold = true))
    val cRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
    fun stat(k: String): TextView {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val v = label("--", 22f, Color.WHITE, bold = true); c.addView(v); c.addView(label(k, 13f, MUTED))
        cRow.addView(c, LinearLayout.LayoutParams(0, -2, 1f)); return v
    }
    val avg = stat("media (kWh/100 km)"); val last = stat("último viaje"); val total = stat("kWh gastados"); val odo = stat("kilómetros")
    cons.addView(cRow)

    // ── fila 4: historial de cargas ──
    val hist = card(); col.addView(hist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    hist.addView(label("Últimas cargas", 16f, MUTED, bold = true))
    val histList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
    hist.addView(histList)
    histList.addView(label("Cargando…", 14f, MUTED))
    loadChargeHistory(histList)

    refreshTab = {
        val s = StateHub.state
        val soc = s?.soc
        ring.pct = soc ?: 0; ring.color = when { soc == null -> MUTED; soc <= 15 -> RED; soc <= 30 -> AMBER; else -> ACCENT }; ring.invalidate()
        range.text = s?.rangeKm?.let { "$it km" } ?: "--"
        kwh.text = s?.energyLeftKwh?.let { "${fmt1(it)} kWh" } ?: "--"
        soh.text = s?.soh?.let { "$it %" } ?: "--"
        temp.text = if (s?.batteryTempMin != null && s.batteryTempMax != null) "${s.batteryTempMin}–${s.batteryTempMax} °C" else "--"
        v12.text = s?.aux12v?.let { "${fmt1(it)} V" } ?: "--"
        v12.setTextColor(if ((s?.aux12v ?: 13.0) < 12.0) AMBER else Color.WHITE)
        hv.text = (s?.bmsVolt ?: s?.voltage?.toDouble())?.let { "${it.toInt()} V" } ?: "--"
        amp.text = s?.bmsAmp?.let { "${fmt1(kotlin.math.abs(it))} A" + if (it < -0.2) " (entrando)" else if (it > 0.2) " (saliendo)" else "" } ?: "--"
        val plugged = s != null && (s.charging || (s.chargingGun != null && s.chargingGun != "none"))
        cTitle.text = when { s?.charging == true -> "Cargando" + (s.chargingGun?.takeIf { it == "AC" || it == "DC" }?.let { " ($it)" } ?: ""); plugged -> "Enchufado"; else -> "Sin cargar" }
        cPower.text = if (s?.charging == true) (s.chargeKw?.let { "${fmt1(it)} kW" } ?: s.chargingPower?.let { "$it kW" } ?: "") else ""
        cSub.text = if (s?.charging == true) listOfNotNull(
            soc?.let { p -> "$p %" + (s.chargeLimit?.takeIf { it < 100 }?.let { " → $it %" } ?: "") },
            s.chargedKwh?.let { "+${fmt1(it)} kWh en esta carga" },
            s.chargeRestMin?.takeIf { it in 1..2880 }?.let { "lleno en ${dur(it)}" }).joinToString("  ·  ")
            else "Enchufa el coche para ver la potencia, los kWh que entran y el tiempo que falta."
        cBar.visibility = if (s?.charging == true) View.VISIBLE else View.GONE; cBar.set(soc ?: 0, ACCENT)
        limBtns.forEach { (b, p) -> active(b, s?.chargeLimit == p) }
        lHint.text = when {
            s?.chargeLimitIgnored == true -> "Este coche no respeta el límite: en una carga pasó de él. Vigila el porcentaje y desenchufa tú."
            s?.chargeLimitConfirmed == false -> "El coche no confirma el límite. En algunos modelos (como el Dolphin Surf) lo ignora: se comprueba en la carga."
            else -> ""
        }
        chg.visibility = if (caps["chargeLimit"] == false && s?.charging != true) View.GONE else View.VISIBLE
        avg.text = s?.consumptionKwh100?.let { fmt1(it) } ?: "--"
        last.text = s?.lastTripKwh100?.let { fmt1(it) } ?: "--"
        total.text = s?.totalEnergyKwh?.let { String.format(java.util.Locale.getDefault(), "%,d", it.toInt()) } ?: "--"
        odo.text = s?.odometer?.let { String.format(java.util.Locale.getDefault(), "%,d", it) } ?: "--"
    }
    return ScrollView(this).apply { addView(col) }
}

/** Las últimas cargas que guardó el Recorder (cars/{id}/chargingSessions). */
private fun HomeActivity.loadChargeHistory(list: LinearLayout) {
    val ref = App.db.getReference("cars/${BuildConfigCompat.carId(this)}/chargingSessions").orderByChild("start").limitToLast(8)
    ref.addListenerForSingleValueEvent(object : ValueEventListener {
        override fun onDataChange(snap: DataSnapshot) {
            list.removeAllViews()
            val rows = snap.children.mapNotNull { it.value as? Map<*, *> }.reversed()
            if (rows.isEmpty()) { list.addView(label("Aún no hay cargas guardadas.", 14f, MUTED)); return }
            val fmt = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
            rows.forEach { r ->
                val start = (r["start"] as? Number)?.toLong() ?: 0L
                val kwh = (r["kwh"] as? Number)?.toDouble()
                val s0 = (r["socStart"] as? Number)?.toInt(); val s1 = (r["socEnd"] as? Number)?.toInt()
                val min = (r["durationMin"] as? Number)?.toInt()
                val live = r["live"] == true
                val row = LinearLayout(this@loadChargeHistory).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, dp(7)) }
                row.addView(label(fmt.format(java.util.Date(start)) + if (live) "  · ahora" else "", 15f, if (live) ACCENT else TEXT), LinearLayout.LayoutParams(0, -2, 1.3f))
                row.addView(label(if (s0 != null && s1 != null) "$s0 → $s1 %" else "--", 15f, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(label(kwh?.let { "+${fmt1(it)} kWh" } ?: "--", 15f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(label(listOfNotNull(min?.let { dur(it) }, r["type"] as? String).joinToString(" · "), 14f, MUTED).apply { gravity = Gravity.END },
                    LinearLayout.LayoutParams(0, -2, 1f))
                list.addView(row)
            }
        }
        override fun onCancelled(error: DatabaseError) {
            list.removeAllViews(); list.addView(label("No se pudo leer el historial (${error.message}).", 14f, MUTED))
        }
    })
}

/** Anillo de carga de la batería con el % en el centro. */
internal class SocRing(a: HomeActivity) : View(a) {
    var pct = 0
    var color = ACCENT
    private val dpx = a.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; this.color = 0x22FFFFFF; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    override fun onDraw(c: Canvas) {
        val w = 10 * dpx; track.strokeWidth = w; arc.strokeWidth = w; arc.color = color
        val r = RectF(w, w, width - w, height - w)
        c.drawArc(r, -90f, 360f, false, track)
        c.drawArc(r, -90f, 360f * pct.coerceIn(0, 100) / 100f, false, arc)
        num.textSize = height * 0.24f
        c.drawText("$pct %", width / 2f, height / 2f + num.textSize / 3, num)
    }
}
