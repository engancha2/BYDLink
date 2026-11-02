package com.bydlink.carapp.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.bydlink.carapp.service.StateHub
import com.bydlink.carapp.ui.HomeActivity.Companion.INK
import com.bydlink.carapp.ui.HomeActivity.Companion.ACCENT
import com.bydlink.carapp.ui.HomeActivity.Companion.AMBER
import com.bydlink.carapp.ui.HomeActivity.Companion.MUTED
import com.bydlink.carapp.ui.HomeActivity.Companion.RED
import com.bydlink.carapp.ui.HomeActivity.Companion.TEXT
import java.util.Locale
import kotlin.math.abs

/**
 * Batería (boceto): Resumen (carga, autonomía, energía, salud, potencia, 12 V y consumos), Carga (en curso y límite),
 * Diagnóstico (BMS, temperaturas, tensiones), Historial (mes y últimos viajes y cargas) y Ahorro (frente a gasolina).
 */
internal fun HomeActivity.batteryPage(): View {
    val col = page()
    val sub = pageHead(col, "Batería", listOf("resumen" to "Resumen", "carga" to "Carga", "diagnostico" to "Diagnóstico", "historial" to "Historial", "ahorro" to "Ahorro"))
    when (sub) {
        "carga" -> chargeBody(col)
        "diagnostico" -> diagBody(col)
        "historial" -> { val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; historyCard(inner, first = true)
            col.addView(ScrollView(this).apply { addView(inner) }, LinearLayout.LayoutParams(-1, 0, 1f)) }
        "ahorro" -> { val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; savingsCard(inner)
            col.addView(ScrollView(this).apply { addView(inner) }, LinearLayout.LayoutParams(-1, 0, 1f)) }
        else -> summaryBody(col)
    }
    return col
}

private fun HomeActivity.summaryBody(col: LinearLayout) {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    col.addView(row, LinearLayout.LayoutParams(-1, -2))
    val a = card(); row.addView(a, LinearLayout.LayoutParams(0, -2, 1.15f).apply { rightMargin = dp(16) })
    a.addView(label("Estado de carga", 17f, INK, bold = true))
    val big = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
    val pct = label("--%", 46f, INK).apply { includeFontPadding = false }; big.addView(pct, LinearLayout.LayoutParams(0, -2, 1f))
    val km = label("-- km", 36f, ACCENT).apply { includeFontPadding = false }; big.addView(km)
    a.addView(big, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    val bar = Bar(ACCENT); a.addView(bar, LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(10); bottomMargin = dp(6) })
    val energy = kv(a, "Energía restante / capacidad"); val soh = kv(a, "Salud de batería")
    val power = kv(a, "Potencia actual"); val aux = kv(a, "Batería auxiliar")

    val b = card(); row.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
    b.addView(h3("Consumo"))
    val inst = kv(b, "Instantáneo"); val avg = kv(b, "Media"); val last = kv(b, "Último viaje")
    val total = kv(b, "Energía total consumida"); val regen = kv(b, "Recuperación actual")
    refreshTab = {
        val s = StateHub.state
        val p = s?.soc
        pct.text = p?.let { "$it%" } ?: "--%"
        km.text = s?.rangeKm?.let { "$it km" } ?: "-- km"
        bar.set(p ?: 0, when { p == null -> MUTED; p <= 15 -> RED; p <= 30 -> AMBER; else -> ACCENT })
        val left = s?.energyLeftKwh
        val cap = if (left != null && p != null && p >= 10) left / p * 100 else null
        energy.text = listOfNotNull(left?.let { fmt1(it) }, cap?.let { fmt1(it) }).joinToString(" / ").ifEmpty { "--" } + if (left != null) " kWh" else ""
        soh.text = s?.soh?.let { "$it%" } ?: "--"
        val pw = s?.power
        power.text = when {
            s == null -> "--"
            s.charging -> (s.chargeKw?.let { "${fmt1(it)} kW" } ?: "--") + " · Cargando"
            pw != null && moving(s) -> "$pw kW · En marcha"
            else -> "${pw ?: 0} kW · Aparcado"
        }
        aux.text = s?.aux12v?.let { "${fmt1(it)} V · " + if (it < 12.0) "Baja" else "Normal" } ?: "--"
        aux.setTextColor(if ((s?.aux12v ?: 13.0) < 12.0) AMBER else INK)
        // Instantáneo: potencia / velocidad (solo en marcha y consumiendo).
        inst.text = if (s != null && s.speed >= 5 && pw != null && pw > 0) "${fmt1(pw * 100.0 / s.speed)} kWh/100 km" else "-- kWh/100 km"
        avg.text = s?.consumptionKwh100?.let { "${fmt1(it)} kWh/100 km" } ?: "--"
        last.text = s?.lastTripKwh100?.let { "${fmt1(it)} kWh/100 km" } ?: "--"
        total.text = s?.totalEnergyKwh?.let { String.format(I18n.locale, "%,d kWh", it.toInt()) } ?: "--"
        regen.text = if (pw != null && pw < 0) "${-pw} kW" else "0 kW"
        regen.setTextColor(if (pw != null && pw < 0) ACCENT else INK)
    }
}

private fun HomeActivity.chargeBody(col: LinearLayout) {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    col.addView(row, LinearLayout.LayoutParams(-1, -2))
    val chg = card(); row.addView(chg, LinearLayout.LayoutParams(0, -2, 1.3f).apply { rightMargin = dp(16) })
    val cHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val cTitle = label("Carga", 17f, INK, bold = true); cHead.addView(cTitle, LinearLayout.LayoutParams(0, -2, 1f))
    val cPower = label("", 32f, ACCENT); cHead.addView(cPower)
    chg.addView(cHead)
    val cSub = label("", 16f, TEXT).apply { setPadding(0, dp(6), 0, 0) }; chg.addView(cSub)
    val cBar = Bar(ACCENT); chg.addView(cBar, LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(12) })
    chg.addView(label("Límite de carga (como en los ajustes de BYD)", 15f, MUTED).apply { setPadding(0, dp(16), 0, dp(8)) })
    val lRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val limBtns = listOf(60, 70, 80, 90, 100).map { p ->
        pill(if (p == 100) "Sin límite" else "$p %") { send("setChargeLimit", mapOf("percent" to p), if (p == 100) "Sin límite de carga" else "Límite al $p %") }
            .also { lRow.addView(it, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) }) } to p
    }
    chg.addView(lRow)
    val lHint = label("", 14f, MUTED).apply { setPadding(0, dp(10), 0, 0) }; chg.addView(lHint)

    val info = card(); row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
    info.addView(h3("Datos de la carga"))
    val gun = kv(info, "Manguera"); val added = kv(info, "kWh en esta carga"); val rest = kv(info, "Tiempo para llenar")
    val amp = kv(info, "Corriente"); val temp = kv(info, "Temperatura de la batería")
    refreshTab = {
        val s = StateHub.state; val soc = s?.soc
        val plugged = s != null && (s.charging || (s.chargingGun != null && s.chargingGun != "none"))
        cTitle.text = when { s?.charging == true -> "Cargando" + (s.chargingGun?.takeIf { it == "AC" || it == "DC" }?.let { " ($it)" } ?: ""); plugged -> "Enchufado"; else -> "Sin cargar" }
        cPower.text = if (s?.charging == true) (s.chargeKw?.let { "${fmt1(it)} kW" } ?: s.chargingPower?.let { "$it kW" } ?: "") else ""
        cSub.text = if (s?.charging == true) listOfNotNull(
            soc?.let { p -> "$p %" + (s.chargeLimit?.takeIf { it < 100 }?.let { " → $it %" } ?: "") },
            s.chargedKwh?.let { "+${fmt1(it)} kWh" },
            s.chargeRestMin?.takeIf { it in 1..2880 }?.let { "lleno en ${dur(it)}" }).joinToString("  ·  ")
            else "Enchufa el coche para ver la potencia, los kWh que entran y el tiempo que falta."
        cBar.visibility = if (s?.charging == true) View.VISIBLE else View.GONE; cBar.set(soc ?: 0, ACCENT)
        limBtns.forEach { (b, p) -> active(b, s?.chargeLimit == p) }
        lHint.text = when {
            s?.chargeLimitIgnored == true -> "Este coche no respeta el límite: en una carga pasó de él. Vigila el porcentaje y desenchufa tú (o crea en el móvil el aviso «Al llegar al 80 %»)."
            s?.chargeLimitConfirmed == false -> "El coche no confirma el límite. En algunos modelos (como el Dolphin Surf) lo ignora: se comprueba en la carga."
            else -> ""
        }
        lHint.setTextColor(if (s?.chargeLimitIgnored == true) AMBER else MUTED)
        gun.text = when (s?.chargingGun) { "AC" -> "Conectada (AC)"; "DC" -> "Conectada (DC)"; "none" -> "Sin conectar"; null -> "--"; else -> s.chargingGun }
        added.text = s?.chargedKwh?.let { "${fmt1(it)} kWh" } ?: "--"
        rest.text = s?.chargeRestMin?.takeIf { it in 1..2880 && s.charging }?.let { dur(it) } ?: "--"
        amp.text = s?.bmsAmp?.let { "${fmt1(abs(it))} A" + if (it < -0.2) " (entrando)" else if (it > 0.2) " (saliendo)" else "" } ?: "--"
        temp.text = if (s?.batteryTempMin != null && s.batteryTempMax != null) "${s.batteryTempMin}–${s.batteryTempMax} °C" else "--"
    }
}

private fun HomeActivity.diagBody(col: LinearLayout) {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    col.addView(row, LinearLayout.LayoutParams(-1, -2))
    val a = card(); row.addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(16) })
    a.addView(h3("Batería de tracción"))
    val soh = kv(a, "Salud (SOH)"); val temp = kv(a, "Temperatura de celdas"); val hv = kv(a, "Voltaje"); val amp = kv(a, "Corriente")
    val bms = kv(a, "Estado del BMS")
    val b = card(); row.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
    b.addView(h3("Coche"))
    val v12 = kv(b, "Batería 12 V"); val pst = kv(b, "Encendido"); val sdk = kv(b, "Lectura del coche"); val alarm = kv(b, "Alarma")
    val odo = kv(b, "Kilómetros")
    refreshTab = {
        val s = StateHub.state
        soh.text = s?.soh?.let { "$it %" } ?: "--"
        temp.text = if (s?.batteryTempMin != null && s.batteryTempMax != null) "${s.batteryTempMin}–${s.batteryTempMax} °C" else "--"
        hv.text = (s?.bmsVolt ?: s?.voltage?.toDouble())?.let { "${it.toInt()} V" } ?: "--"
        amp.text = s?.bmsAmp?.let { "${fmt1(abs(it))} A" + if (it < -0.2) " (entrando)" else if (it > 0.2) " (saliendo)" else "" } ?: "--"
        bms.text = when (s?.bmsState) { 0 -> "Lista"; 1 -> "Cargando"; 2 -> "Carga terminada"; 3 -> "Descargando"; 4 -> "Carga parada"; 15 -> "En reposo"; null -> "--"; else -> "Código ${s.bmsState}" }
        v12.text = s?.aux12v?.let { "${fmt1(it)} V" } ?: "--"
        v12.setTextColor(if ((s?.aux12v ?: 13.0) < 12.0) AMBER else INK)
        pst.text = when (s?.powerState) { "OFF" -> "Apagado"; "ACC" -> "Accesorios"; "ON", "OK" -> "Encendido"; null -> "--"; else -> s.powerState }
        val age = (System.currentTimeMillis() - StateHub.readAt) / 60000
        sdk.text = when { s == null -> "Esperando…"; !StateHub.sdkOk -> "Dormido (hace ${dur(age.toInt())})"; else -> "Al día" }
        alarm.text = s?.alarm?.let { if (it) "Armada" else "Desarmada" } ?: "--"
        odo.text = s?.odometer?.let { String.format(I18n.locale, "%,d km", it) } ?: "--"
    }
}

