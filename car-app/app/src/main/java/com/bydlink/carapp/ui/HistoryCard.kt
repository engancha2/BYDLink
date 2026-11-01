package com.bydlink.carapp.ui

import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.bydlink.carapp.App
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.data.Tariff
import com.bydlink.carapp.service.StateHub
import com.bydlink.carapp.ui.HomeActivity.Companion.ACCENT
import com.bydlink.carapp.ui.HomeActivity.Companion.MUTED
import com.bydlink.carapp.ui.HomeActivity.Companion.TEXT
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import java.util.Calendar
import java.util.Locale

/**
 * Página Energía del lanzador: resumen del mes (km, kWh, €) y los últimos viajes y cargas. Lee lo que guarda el
 * Recorder en la nube y los precios que pone el móvil (cars/{id}/historyCfg); el detalle está en el móvil.
 */
internal fun HomeActivity.historyCard(col: LinearLayout) {
    val sum = card(); col.addView(sum, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val month = Calendar.getInstance().getDisplayName(Calendar.MONTH, Calendar.LONG, Locale("es", "ES")) ?: ""
    head.addView(label("Este mes · $month", 16f, MUTED, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(label("Detalle y precios en el móvil", 13f, MUTED))
    sum.addView(head)
    val sRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
    fun stat(k: String): TextView {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val v = label("--", 22f, Color.WHITE, bold = true); c.addView(v); c.addView(label(k, 13f, MUTED))
        sRow.addView(c, LinearLayout.LayoutParams(0, -2, 1f)); return v
    }
    val km = stat("kilómetros"); val used = stat("kWh gastados"); val charged = stat("kWh cargados")
    val cost = stat("en cargas"); val per100 = stat("€ / 100 km")
    sum.addView(sRow)

    val lists = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    col.addView(lists, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    val tc = card(); lists.addView(tc, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12) })
    tc.addView(label("Últimos viajes", 16f, MUTED, bold = true))
    val tList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }; tc.addView(tList)
    val cc = card(); lists.addView(cc, LinearLayout.LayoutParams(0, -2, 1f))
    cc.addView(label("Últimas cargas", 16f, MUTED, bold = true))
    val cList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }; cc.addView(cList)
    tList.addView(label("Cargando…", 14f, MUTED)); cList.addView(label("Cargando…", 14f, MUTED))

    val root = App.db.getReference("cars/${BuildConfigCompat.carId(this)}")
    val from = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val got = HashMap<String, Any?>()
    val keys = listOf("cfg", "edits", "mTrips", "mChg", "rTrips", "rChg")
    fun done() {
        if (!keys.all { got.containsKey(it) }) return
        val cfg = Tariff.parse(got["cfg"] as? Map<*, *>)
        val edits = got["edits"] as? Map<*, *>
        fun edit(kind: String, id: String?) = (edits?.get(kind) as? Map<*, *>)?.get(id) as? Map<*, *>
        @Suppress("UNCHECKED_CAST") fun rows(k: String) = (got[k] as? List<Pair<String, Map<*, *>>>).orEmpty()
        val trips = rows("mTrips").filter { edit("trips", it.first)?.get("skip") != true }.map { it.second }
        val chg = rows("mChg").filter { edit("charges", it.first)?.get("skip") != true }
        val kmSum = trips.sumOf { (it["distanceKm"] as? Number)?.toDouble() ?: 0.0 }
        val known = trips.filter { (it["kwh"] as? Number) != null && ((it["distanceKm"] as? Number)?.toDouble() ?: 0.0) > 0 }
        val knownKm = known.sumOf { (it["distanceKm"] as Number).toDouble() }
        val avg = if (knownKm >= 5) known.sumOf { (it["kwh"] as Number).toDouble() } / knownKm * 100
            else StateHub.state?.consumptionKwh100?.takeIf { it in 5.0..40.0 } ?: 14.0
        val usedSum = known.sumOf { (it["kwh"] as Number).toDouble() } + (kmSum - knownKm) * avg / 100
        val chgKwh = chg.sumOf { (it.second["kwh"] as? Number)?.toDouble() ?: 0.0 }
        val chgCost = chg.sumOf { Tariff.chargeCost(cfg, it.second, edit("charges", it.first)) }
        val eurKwh = if (chgKwh >= 1) chgCost / chgKwh else if (cfg.periods) cfg.p3 else cfg.flat
        km.text = if (trips.isEmpty()) "0" else String.format(Locale.getDefault(), "%,d", Math.round(kmSum))
        used.text = fmt1(usedSum); charged.text = fmt1(chgKwh)
        cost.text = String.format(Locale.getDefault(), "%.2f €", chgCost)
        per100.text = if (kmSum >= 10) String.format(Locale.getDefault(), "%.2f", avg * eurKwh) else "--"
        per100.setTextColor(ACCENT)

        val f = java.text.SimpleDateFormat("d MMM HH:mm", Locale("es", "ES"))
        tList.removeAllViews()
        val rt = rows("rTrips").reversed()
        if (rt.isEmpty()) tList.addView(label("Aún no hay viajes guardados.", 14f, MUTED))
        rt.forEach { (_, r) ->
            val d = (r["distanceKm"] as? Number)?.toDouble()
            val k100 = (r["kwh100"] as? Number)?.toDouble()
            val min = ((r["durationSec"] as? Number)?.toInt() ?: 0) / 60
            line(tList, f.format(java.util.Date((r["start"] as? Number)?.toLong() ?: 0L)) + if (r["live"] == true) " · ahora" else "",
                d?.let { "${fmt1(it)} km" } ?: "--", listOfNotNull(dur(min), k100?.let { "${fmt1(it)} kWh/100" }).joinToString(" · "), r["live"] == true)
        }
        cList.removeAllViews()
        val rc = rows("rChg").reversed()
        if (rc.isEmpty()) cList.addView(label("Aún no hay cargas guardadas.", 14f, MUTED))
        rc.forEach { (id, r) ->
            val k = (r["kwh"] as? Number)?.toDouble()
            val s0 = (r["socStart"] as? Number)?.toInt(); val s1 = (r["socEnd"] as? Number)?.toInt()
            val e = Tariff.chargeCost(cfg, r, edit("charges", id))
            line(cList, f.format(java.util.Date((r["start"] as? Number)?.toLong() ?: 0L)) + if (r["live"] == true) " · ahora" else "",
                k?.let { "+${fmt1(it)} kWh" } ?: "--",
                listOfNotNull(if (s0 != null && s1 != null) "$s0 → $s1 %" else null, (r["type"] as? String), String.format(Locale.getDefault(), "%.2f €", e)).joinToString(" · "),
                r["live"] == true)
        }
    }
    fun once(k: String, q: Query, asList: Boolean) = q.addListenerForSingleValueEvent(object : ValueEventListener {
        override fun onDataChange(snap: DataSnapshot) {
            got[k] = if (asList) snap.children.mapNotNull { c -> (c.value as? Map<*, *>)?.let { c.key.orEmpty() to it } } else snap.value
            done()
        }
        override fun onCancelled(error: DatabaseError) { got[k] = null; done() }
    })
    once("cfg", root.child("historyCfg"), false)
    once("edits", root.child("historyEdits"), false)
    once("mTrips", root.child("trips").orderByChild("start").startAt(from.toDouble()), true)
    once("mChg", root.child("chargingSessions").orderByChild("start").startAt(from.toDouble()), true)
    once("rTrips", root.child("trips").orderByChild("start").limitToLast(5), true)
    once("rChg", root.child("chargingSessions").orderByChild("start").limitToLast(5), true)
}

private fun HomeActivity.line(list: LinearLayout, whenTxt: String, main: String, sub: String, live: Boolean) {
    val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(7), 0, dp(7)) }
    val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    top.addView(label(whenTxt, 15f, if (live) ACCENT else TEXT), LinearLayout.LayoutParams(0, -2, 1f))
    top.addView(label(main, 15f, Color.WHITE, bold = true))
    row.addView(top)
    row.addView(label(sub, 13f, MUTED))
    list.addView(row)
}
