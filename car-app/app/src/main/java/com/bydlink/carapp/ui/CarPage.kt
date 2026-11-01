package com.bydlink.carapp.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.model.CarState
import com.bydlink.carapp.service.StateHub
import com.bydlink.carapp.ui.HomeActivity.Companion.ACCENT
import com.bydlink.carapp.ui.HomeActivity.Companion.AMBER
import com.bydlink.carapp.ui.HomeActivity.Companion.CARD2
import com.bydlink.carapp.ui.HomeActivity.Companion.MUTED
import java.util.Locale

/** Coche: Estado (el coche visto desde arriba con ruedas, puertas y ventanillas), Confort, Luces y Conducción. */
internal fun HomeActivity.carPage(): View {
    val col = page()
    val sub = pageHead(col, "Coche", listOf("estado" to "Estado", "confort" to "Confort", "luces" to "Luces", "conduccion" to "Conducción"))
    when (sub) {
        "confort" -> comfortBody(col)
        "luces" -> lightsBody(col)
        "conduccion" -> drivingBody(col)
        else -> statusBody(col)
    }
    return col
}

/** Clima (desde la barra de abajo o Menú): lo mismo que Coche → Confort. */
internal fun HomeActivity.climatePage(): View {
    val col = page()
    pageHead(col, "Clima")
    comfortBody(col)
    return col
}

// ═════════════════════════ Estado ═════════════════════════
private fun HomeActivity.statusBody(col: LinearLayout) {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    col.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
    val left = card(); row.addView(left, LinearLayout.LayoutParams(0, -1, 1.25f).apply { rightMargin = dp(16) })
    val lh = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    lh.addView(label("Estado del vehículo", 17f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
    val lock = label("", 15f, MUTED); lh.addView(lock)
    left.addView(lh)
    val car = CarTopView(this); left.addView(car, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })

    val right = card(); row.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
    right.addView(h3("Puertas y ventanillas"))
    val alerts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(alerts); addView(list) }
    right.addView(ScrollView(this).apply { addView(inner) }, LinearLayout.LayoutParams(-1, 0, 1f))
    val rDoors = kv(list, "Resto de puertas"); val rWin = kv(list, "Resto de ventanillas")
    val rTrunk = kv(list, "Maletero / capó"); val rTyres = kv(list, "Neumáticos"); val rLock = kv(list, "Cierre")
    val closeWin = pill("Cerrar ventanillas") { send("closeWindows", emptyMap(), "Cerrando ventanillas") }
    right.addView(closeWin, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })

    var lastKey = ""
    refreshTab = {
        val s = StateHub.state
        car.state = s; car.invalidate()
        lock.text = when (s?.locked) { true -> "Cerrado"; false -> "Abierto"; null -> "" }
        lock.setTextColor(if (s?.locked == false) AMBER else MUTED)
        val d = s?.doors; val w = s?.windows; val wp = s?.windowsPct
        val names = mapOf("fl" to "delantera izquierda", "fr" to "delantera derecha", "rl" to "trasera izquierda", "rr" to "trasera derecha")
        val openDoors = names.filterKeys { k -> when (k) { "fl" -> d?.fl; "fr" -> d?.fr; "rl" -> d?.rl; else -> d?.rr } == true }
        fun pct(k: String) = when (k) { "fl" -> wp?.fl; "fr" -> wp?.fr; "rl" -> wp?.rl; else -> wp?.rr }
        val openWins = names.filterKeys { k -> (pct(k) ?: 0) > 0 || when (k) { "fl" -> w?.fl; "fr" -> w?.fr; "rl" -> w?.rl; else -> w?.rr } == true }
        val key = "$openDoors|$openWins|${d?.trunk}|${d?.hood}|${openWins.keys.map { pct(it) }}"
        if (key != lastKey) {
            lastKey = key
            alerts.removeAllViews()
            openDoors.forEach { (_, n) -> alerts.addView(alertRow("door", "Puerta $n", "Abierta"), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
            openWins.forEach { (k, n) -> alerts.addView(alertRow("window", "Ventanilla $n", pct(k)?.takeIf { it > 0 }?.let { "Abierta al $it %" } ?: "Abierta"),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
            if (d?.trunk == true) alerts.addView(alertRow("car", "Maletero", "Abierto"), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            if (d?.hood == true) alerts.addView(alertRow("car", "Capó", "Abierto"), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        rDoors.text = if (s == null) "--" else if (openDoors.size == 4) "—" else "Cerradas"
        rWin.text = if (s == null) "--" else if (openWins.size == 4) "—" else "Cerradas"
        rTrunk.text = if (s == null) "--" else if (d?.trunk == true || d?.hood == true) "Abierto" else "Cerrados"
        val ty = s?.tyres
        val low = listOfNotNull(ty?.fl, ty?.fr, ty?.rl, ty?.rr).any { it < 2.0 || it > 3.0 }
        rTyres.text = if (ty == null || listOfNotNull(ty.fl, ty.fr, ty.rl, ty.rr).isEmpty()) "--" else if (low) "Revisa la presión" else "Sin avisos"
        rTyres.setTextColor(if (low) AMBER else Color.WHITE)
        rLock.text = when (s?.locked) { true -> "Cerrado"; false -> "Abierto"; null -> "--" }
        closeWin.visibility = if (caps["windows"] == false || openWins.isEmpty()) View.GONE else View.VISIBLE
    }
}

// ═════════════════════════ Confort (clima y asientos) ═════════════════════════
internal fun HomeActivity.comfortBody(col: LinearLayout) {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    col.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
    val climaCard = card(); row.addView(climaCard, LinearLayout.LayoutParams(0, -1, 1.25f).apply { rightMargin = dp(16) })
    val clima = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    climaCard.addView(ScrollView(this).apply { addView(clima); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(-1, -1))
    val cHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val cState = label("Clima", 17f, Color.WHITE, bold = true); cHead.addView(cState, LinearLayout.LayoutParams(0, -2, 1f))
    val onBtn = pill("Encender") {}; cHead.addView(onBtn, LinearLayout.LayoutParams(dp(150), dp(52)))
    clima.addView(cHead)
    val tRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val minus = pill("−") {}.apply { textSize = 26f }; val tTxt = label("--°", 40f, Color.WHITE).apply { gravity = Gravity.CENTER }
    val plus = pill("+") {}.apply { textSize = 26f }
    tRow.addView(minus, LinearLayout.LayoutParams(dp(72), dp(52))); tRow.addView(tTxt, LinearLayout.LayoutParams(dp(120), -2)); tRow.addView(plus, LinearLayout.LayoutParams(dp(72), dp(52)))
    val inT = label("", 15f, MUTED).apply { setPadding(dp(20), 0, 0, 0) }; tRow.addView(inT)
    clima.addView(tRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
    clima.addView(label("Ventilador", 15f, MUTED).apply { setPadding(0, dp(8), 0, dp(5)) })
    val fanRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val fanBtns = (0..7).map { n -> pill(if (n == 0) "Auto" else "$n") { climate(if (n == 0) mapOf("auto" to true) else mapOf("fan" to n, "auto" to false)) }
        .also { fanRow.addView(it.apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(46), if (n == 0) 1.4f else 1f).apply { rightMargin = dp(5) }) } }
    clima.addView(fanRow)
    clima.addView(label("Salida del aire y desempañado", 15f, MUTED).apply { setPadding(0, dp(8), 0, dp(5)) })
    val ventRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    // Salida: cara=1, pies=2, parabrisas=4 (se combinan) → modo 1..7 del coche.
    val modeOf = mapOf(1 to 1, 3 to 2, 2 to 3, 6 to 4, 4 to 5, 7 to 6, 5 to 7)
    val bitsOf = modeOf.entries.associate { (b, m) -> m to b }
    val vents = listOf(4 to "Parabrisas", 1 to "Cara", 2 to "Pies").map { (bit, name) ->
        pill(name) {
            val cur = bitsOf[StateHub.state?.climate?.mode] ?: 0
            val next = cur xor bit
            if (next == 0) toast("El aire tiene que salir por algún sitio") else climate(mapOf("mode" to modeOf.getValue(next)))
        }.also { ventRow.addView(it.apply { setPadding(dp(4), 0, dp(4), 0) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(6) }) } to bit
    }
    clima.addView(ventRow)
    val dfRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val dfF = pill("Desempañar delante") { climate(mapOf("defrostFront" to (StateHub.state?.climate?.defrostFront != true))) }
    val dfR = pill("Desempañar detrás") { climate(mapOf("defrostRear" to (StateHub.state?.climate?.defrostRear != true))) }
    val recirc = pill("Recircular") { climate(mapOf("recirc" to (StateHub.state?.climate?.recirc != true))) }
    dfRow.addView(dfF, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(6) })
    dfRow.addView(dfR, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(6) })
    dfRow.addView(recirc, LinearLayout.LayoutParams(0, dp(46), 0.8f))
    clima.addView(dfRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    onBtn.setOnClickListener { climate(mapOf("on" to (StateHub.state?.climate?.on != true))) }
    minus.setOnClickListener { StateHub.state?.climate?.temp?.let { climate(mapOf("temp" to (it - 1).coerceIn(17, 33))) } }
    plus.setOnClickListener { StateHub.state?.climate?.temp?.let { climate(mapOf("temp" to (it + 1).coerceIn(17, 33))) } }
    val climaNo = label("Este coche no deja manejar el clima desde apps.", 16f, MUTED)
    clima.addView(climaNo)

    // asientos y retrovisores
    val seats = card(); row.addView(seats, LinearLayout.LayoutParams(0, -1, 1f))
    seats.addView(h3("Asientos"))
    val seatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    seats.addView(seatBox)
    val levels = buildList {
        add(0 to "No")
        if (caps["seatHeat"] != false) { add(1 to "Calor 1"); add(2 to "Calor 2") }
        if (caps["seatVent"] != false) { add(-1 to "Frío 1"); add(-2 to "Frío 2") }
    }
    val seatBtns = HashMap<String, List<Pair<Int, TextView>>>()
    listOf("driver" to "Conductor", "passenger" to "Acompañante").forEach { (who, name) ->
        seatBox.addView(label(name, 15f, MUTED).apply { setPadding(0, dp(8), 0, dp(6)) })
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        seatBtns[who] = levels.map { (lv, t) ->
            lv to pill(t) { send("setSeatComfort", mapOf(who to lv), null) }.apply { textSize = 14f; setPadding(0, 0, 0, 0) }
                .also { r.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5) }) }
        }
        seatBox.addView(r)
    }
    val seatNo = label("Tu coche no tiene calefacción ni ventilación de asientos (o no deja usarlas desde apps).", 15f, MUTED).apply { setPadding(0, dp(4), 0, 0) }
    seats.addView(seatNo)
    seats.addView(label("Retrovisores", 15f, MUTED).apply { setPadding(0, dp(16), 0, dp(6)) })
    seats.addView(pill("Calentar retrovisores") { send("setMirrorHeat", mapOf("on" to true), "Calentando retrovisores") }, LinearLayout.LayoutParams(-1, dp(50)))

    refreshTab = {
        val s = StateHub.state; val c = s?.climate
        val hasClima = caps["climate"] != false
        for (i in 1 until clima.childCount - 1) clima.getChildAt(i).visibility = if (hasClima) View.VISIBLE else View.GONE
        onBtn.visibility = if (hasClima) View.VISIBLE else View.GONE
        climaNo.visibility = if (hasClima) View.GONE else View.VISIBLE
        cState.text = if (c?.on == true) "Clima encendido" else "Clima apagado"
        onBtn.text = if (c?.on == true) "Apagar" else "Encender"; active(onBtn, c?.on == true)
        tTxt.text = c?.temp?.let { "$it°" } ?: "--°"
        inT.text = listOfNotNull(s?.inTemp?.let { "Dentro $it°" }, s?.outTemp?.let { "Fuera $it°" }).joinToString(" · ")
        fanBtns.forEachIndexed { n, b -> active(b, if (n == 0) c?.auto == true else c?.auto != true && c?.fan == n) }
        val bits = bitsOf[c?.mode] ?: 0
        vents.forEach { (b, bit) -> active(b, bits and bit != 0) }
        active(dfF, c?.defrostFront == true); active(dfR, c?.defrostRear == true); active(recirc, c?.recirc == true)
        val sc = s?.seatComfort
        seatBtns.forEach { (who, btns) -> val cur = if (who == "driver") sc?.driver else sc?.passenger; btns.forEach { (lv, b) -> active(b, (cur ?: 0) == lv && sc != null) } }
        val noSeats = caps["seatHeat"] == false && caps["seatVent"] == false
        seatBox.visibility = if (noSeats) View.GONE else View.VISIBLE
        seatNo.visibility = if (noSeats) View.VISIBLE else View.GONE
    }
}

// ═════════════════════════ Luces ═════════════════════════
private fun HomeActivity.lightsBody(col: LinearLayout) {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    col.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
    val left = card(); row.addView(left, LinearLayout.LayoutParams(0, -1, 1.4f).apply { rightMargin = dp(16) })
    left.addView(h3("Luces ahora"))
    val grid = GridLayout(this).apply { columnCount = 3 }
    left.addView(grid)
    val tiles = listOf("position" to "Posición", "lowBeam" to "Cortas", "highBeam" to "Largas", "rearFog" to "Antiniebla", "drl" to "Diurnas", "hazard" to "Warning")
        .associate { (k, name) ->
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(CARD2, 14); setPadding(dp(16), dp(14), dp(16), dp(14)) }
            val ic = iconView("light", MUTED); t.addView(ic, LinearLayout.LayoutParams(dp(26), dp(26)))
            t.addView(label(name, 17f, Color.WHITE).apply { setPadding(0, dp(10), 0, 0) })
            val v = label("--", 15f, MUTED); t.addView(v)
            grid.addView(t, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; setMargins(0, 0, dp(10), dp(10)) })
            k to (ic to v)
        }
    val right = card(); row.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
    right.addView(h3("Acciones"))
    val locate = pill("Localizar el coche (ráfagas)") { send("flashLights", emptyMap(), "Haciendo ráfagas") }
    right.addView(locate, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(10) })
    val inOn = pill("Encender luz interior") { send("setInteriorLight", mapOf("on" to true), null) }
    val inOff = pill("Apagar luz interior") { send("setInteriorLight", mapOf("on" to false), null) }
    right.addView(inOn, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(10) })
    right.addView(inOff, LinearLayout.LayoutParams(-1, dp(54)))
    right.addView(label("Las luces de fuera las maneja el coche: aquí ves cómo están.", 14f, MUTED).apply { setPadding(0, dp(14), 0, 0) })
    refreshTab = {
        val l = StateHub.state?.lights
        tiles.forEach { (k, p) ->
            val on = when (k) { "position" -> l?.position; "lowBeam" -> l?.lowBeam; "highBeam" -> l?.highBeam; "rearFog" -> l?.rearFog; "drl" -> l?.drl; else -> l?.hazard }
            p.second.text = when (on) { true -> "Encendidas"; false -> "Apagadas"; null -> "Sin dato" }
            p.second.setTextColor(if (on == true) ACCENT else MUTED)
            p.first.setImageDrawable(LineIcon("light", if (on == true) (if (k == "hazard") AMBER else ACCENT) else MUTED))
        }
        locate.visibility = if (caps["locate"] == false) View.GONE else View.VISIBLE
    }
}

// ═════════════════════════ Conducción ═════════════════════════
private fun HomeActivity.drivingBody(col: LinearLayout) {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    col.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
    val a = card(); row.addView(a, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(16) })
    a.addView(h3("Ahora"))
    val speed = kv(a, "Velocidad"); val gear = kv(a, "Marcha"); val brake = kv(a, "Freno de mano"); val power = kv(a, "Potencia")
    val pState = kv(a, "Encendido"); val mode = kv(a, "Modo de conducción")
    val b = card(); row.addView(b, LinearLayout.LayoutParams(0, -1, 1f))
    b.addView(h3("Viaje y ocupantes"))
    val cons = kv(b, "Consumo medio"); val last = kv(b, "Último viaje"); val odo = kv(b, "Kilómetros")
    val belt = kv(b, "Cinturón del conductor"); val belt2 = kv(b, "Cinturón del acompañante"); val ver = kv(b, "Versión de BYDLink")
    refreshTab = {
        val s = StateHub.state
        speed.text = s?.let { "${it.speed} km/h" } ?: "--"
        gear.text = s?.gear ?: "--"
        brake.text = s?.parkBrake?.let { if (it) "Echado" else "Quitado" } ?: "--"
        power.text = s?.power?.let { if (it < 0) "${-it} kW recuperando" else "$it kW" } ?: "--"
        power.setTextColor(if ((s?.power ?: 0) < 0) ACCENT else Color.WHITE)
        pState.text = when (s?.powerState) { "OFF" -> "Apagado"; "ACC" -> "Accesorios"; "ON", "OK" -> "Encendido"; null -> "--"; else -> s.powerState }
        mode.text = s?.driveMode?.let { "Código $it" } ?: "--"
        cons.text = s?.consumptionKwh100?.let { "${fmt1(it)} kWh/100 km" } ?: "--"
        last.text = s?.lastTripKwh100?.let { "${fmt1(it)} kWh/100 km" } ?: "--"
        odo.text = s?.odometer?.let { String.format(Locale.getDefault(), "%,d km", it) } ?: "--"
        belt.text = s?.seatbelts?.driver?.let { if (it) "Puesto" else "Sin poner" } ?: "--"
        belt.setTextColor(if (s?.seatbelts?.driver == false && moving(s)) AMBER else Color.WHITE)
        belt2.text = s?.seatbelts?.passenger?.let { if (it) "Puesto" else "Sin poner" } ?: "--"
        ver.text = BuildConfigCompat.appVersion(this)
    }
}

/**
 * El coche visto desde arriba (como el boceto): carrocería plateada, cristales, luces, ruedas con su presión y
 * temperatura señaladas con una raya, y lo abierto (puertas, ventanillas, maletero, capó) en ámbar.
 */
internal class CarTopView(a: HomeActivity) : View(a) {
    var state: CarState? = null
    private val dpx = a.resources.displayMetrics.density
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFB9C4CF.toInt(); strokeWidth = 1.5f * dpx }
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B2733.toInt() }
    private val roof = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF66727E.toInt() }
    private val wheel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A0E13.toInt() }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    private val headL = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFD8F3FF.toInt(); strokeWidth = 3f * dpx; strokeCap = Paint.Cap.ROUND }
    private val tailL = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFE0565B.toInt(); strokeWidth = 3f * dpx; strokeCap = Paint.Cap.ROUND }
    private val openFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCB8873A.toInt() }
    private val openEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = AMBER; strokeWidth = 2.5f * dpx; strokeJoin = Paint.Join.ROUND }
    private val lead = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF3A4856.toInt(); strokeWidth = 1.2f * dpx }
    private val leadW = Paint(lead).apply { color = AMBER }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f * dpx }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = 13f * dpx }
    private val warn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AMBER; textSize = 15f * dpx }
    private val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDCE3EA.toInt(); textSize = 13f * dpx; textAlign = Paint.Align.CENTER; letterSpacing = 0.3f; typeface = Typeface.DEFAULT_BOLD }

    override fun onDraw(c: Canvas) {
        val s = state
        val w = width.toFloat(); val h = height.toFloat()
        val ch = h * 0.92f; val cw = minOf(ch * 0.46f, w * 0.26f)
        val l = (w - cw) / 2; val t = (h - ch) / 2; val r = l + cw; val b = t + ch
        val cx = w / 2
        c.drawOval(RectF(l - cw * 0.18f, t + ch * 0.04f, r + cw * 0.18f, b + ch * 0.02f), shadow)
        // ruedas
        val ww = cw * 0.17f; val wh = ch * 0.15f
        val wy = floatArrayOf(t + ch * 0.15f, t + ch * 0.70f)
        for (y in wy) {
            c.drawRoundRect(RectF(l - ww * 0.42f, y, l + ww * 0.58f, y + wh), 6 * dpx, 6 * dpx, wheel)
            c.drawRoundRect(RectF(r - ww * 0.58f, y, r + ww * 0.42f, y + wh), 6 * dpx, 6 * dpx, wheel)
        }
        // carrocería
        body.shader = LinearGradient(l, 0f, r, 0f, intArrayOf(0xFF7D8995.toInt(), 0xFFAEB8C2.toInt(), 0xFF7D8995.toInt()), null, Shader.TileMode.CLAMP)
        val bodyR = RectF(l, t, r, b)
        c.drawRoundRect(bodyR, cw * 0.42f, cw * 0.36f, body)
        c.drawRoundRect(bodyR, cw * 0.42f, cw * 0.36f, edge)
        // retrovisores
        c.drawRoundRect(RectF(l - cw * 0.1f, t + ch * 0.30f, l + cw * 0.02f, t + ch * 0.34f), 4 * dpx, 4 * dpx, Paint(body).apply { shader = null; color = 0xFF8C98A4.toInt() })
        c.drawRoundRect(RectF(r - cw * 0.02f, t + ch * 0.30f, r + cw * 0.1f, t + ch * 0.34f), 4 * dpx, 4 * dpx, Paint(body).apply { shader = null; color = 0xFF8C98A4.toInt() })
        // luces
        c.drawLine(l + cw * 0.14f, t + ch * 0.035f, l + cw * 0.32f, t + ch * 0.05f, headL)
        c.drawLine(r - cw * 0.14f, t + ch * 0.035f, r - cw * 0.32f, t + ch * 0.05f, headL)
        c.drawLine(l + cw * 0.12f, b - ch * 0.03f, l + cw * 0.3f, b - ch * 0.022f, tailL)
        c.drawLine(r - cw * 0.12f, b - ch * 0.03f, r - cw * 0.3f, b - ch * 0.022f, tailL)
        // parabrisas, techo, luneta y ventanillas laterales
        c.drawPath(Path().apply { moveTo(l + cw * 0.12f, t + ch * 0.36f); lineTo(l + cw * 0.2f, t + ch * 0.25f); lineTo(r - cw * 0.2f, t + ch * 0.25f)
            lineTo(r - cw * 0.12f, t + ch * 0.36f); close() }, glass)
        c.drawRoundRect(RectF(l + cw * 0.16f, t + ch * 0.38f, r - cw * 0.16f, t + ch * 0.66f), 8 * dpx, 8 * dpx, roof)
        c.drawText("BYD", cx, t + ch * 0.53f, brand)
        c.drawPath(Path().apply { moveTo(l + cw * 0.14f, t + ch * 0.68f); lineTo(r - cw * 0.14f, t + ch * 0.68f); lineTo(r - cw * 0.2f, t + ch * 0.79f)
            lineTo(l + cw * 0.2f, t + ch * 0.79f); close() }, glass)
        val sideW = cw * 0.07f
        c.drawRect(l + cw * 0.05f, t + ch * 0.38f, l + cw * 0.05f + sideW, t + ch * 0.64f, glass)
        c.drawRect(r - cw * 0.05f - sideW, t + ch * 0.38f, r - cw * 0.05f, t + ch * 0.64f, glass)

        // lo abierto
        val d = s?.doors; val wp = s?.windowsPct; val wo = s?.windows
        fun door(left: Boolean, y0: Float, y1: Float) {
            val x = if (left) l else r; val dir = if (left) -1 else 1
            val p = Path().apply { moveTo(x, t + ch * y0); lineTo(x + dir * cw * 0.34f, t + ch * (y0 + 0.03f)); lineTo(x + dir * cw * 0.30f, t + ch * y1); lineTo(x, t + ch * y1); close() }
            c.drawPath(p, openFill); c.drawPath(p, openEdge)
        }
        if (d?.fl == true) door(true, 0.36f, 0.50f)
        if (d?.rl == true) door(true, 0.51f, 0.64f)
        if (d?.fr == true) door(false, 0.36f, 0.50f)
        if (d?.rr == true) door(false, 0.51f, 0.64f)
        if (d?.trunk == true) { val p = RectF(l + cw * 0.18f, b - ch * 0.02f, r - cw * 0.18f, b + ch * 0.05f); c.drawRect(p, openFill); c.drawRect(p, openEdge) }
        if (d?.hood == true) { val p = RectF(l + cw * 0.18f, t - ch * 0.05f, r - cw * 0.18f, t + ch * 0.02f); c.drawRect(p, openFill); c.drawRect(p, openEdge) }
        fun winOpen(k: String) = ((when (k) { "fl" -> wp?.fl; "fr" -> wp?.fr; "rl" -> wp?.rl; else -> wp?.rr }) ?: 0) > 0 ||
            when (k) { "fl" -> wo?.fl; "fr" -> wo?.fr; "rl" -> wo?.rl; else -> wo?.rr } == true
        val winY = mapOf("fl" to 0.43f, "rl" to 0.58f, "fr" to 0.43f, "rr" to 0.58f)
        for ((k, y) in winY) if (winOpen(k)) {
            val left = k.endsWith("l")
            val x = if (left) l + cw * 0.08f else r - cw * 0.08f
            c.drawLine(x, t + ch * (y - 0.05f), x, t + ch * (y + 0.05f), Paint(openEdge).apply { strokeWidth = 4 * dpx })
        }

        // ruedas: presión y temperatura con su raya
        val ty = s?.tyres; val tt = s?.tyreTemps
        val names = mapOf("fl" to "Del. izq.", "fr" to "Del. dcha.", "rl" to "Tras. izq.", "rr" to "Tras. dcha.")
        val gap = cw * 0.55f
        for ((k, name) in names) {
            val left = k.endsWith("l"); val front = k.startsWith("f")
            val p = when (k) { "fl" -> ty?.fl; "fr" -> ty?.fr; "rl" -> ty?.rl; else -> ty?.rr }
            val temp = when (k) { "fl" -> tt?.fl; "fr" -> tt?.fr; "rl" -> tt?.rl; else -> tt?.rr }
            val yMid = (if (front) wy[0] else wy[1]) + wh / 2
            val xWheel = if (left) l - ww * 0.42f else r + ww * 0.42f
            val xText = if (left) xWheel - gap else xWheel + gap
            c.drawLine(xWheel + (if (left) -6 else 6) * dpx, yMid, xText + (if (left) 8 else -8) * dpx, yMid, lead)
            val bad = p != null && (p < 2.0 || p > 3.0)
            val bp = Paint(big).apply { color = if (bad) AMBER else Color.WHITE; textAlign = if (left) Paint.Align.RIGHT else Paint.Align.LEFT }
            val sp = Paint(small).apply { textAlign = if (left) Paint.Align.RIGHT else Paint.Align.LEFT }
            c.drawText(p?.let { String.format(Locale.getDefault(), "%.1f bar", it) } ?: "-- bar", xText, yMid - 4 * dpx, bp)
            c.drawText(listOfNotNull(temp?.let { "$it °C" }, name).joinToString(" · "), xText, yMid + 16 * dpx, sp)
        }
        // etiquetas de lo abierto
        val openDoorLabel = listOfNotNull(if (d?.fl == true || d?.rl == true) true else null, if (d?.fr == true || d?.rr == true) false else null)
        for (left in openDoorLabel) {
            val y = t + ch * 0.47f
            val x0 = if (left) l - cw * 0.34f else r + cw * 0.34f
            val x1 = if (left) x0 - cw * 0.35f else x0 + cw * 0.35f
            c.drawLine(x0, y, x1, y, leadW)
            c.drawText("Puerta abierta", x1 + (if (left) -6 else 6) * dpx, y + 5 * dpx, Paint(warn).apply { textAlign = if (left) Paint.Align.RIGHT else Paint.Align.LEFT })
        }
        val winLabel = listOf("fl", "fr", "rl", "rr").firstOrNull { winOpen(it) && d?.let { dd -> when (it) { "fl" -> dd.fl; "fr" -> dd.fr; "rl" -> dd.rl; else -> dd.rr } } != true }
        if (winLabel != null) {
            val left = winLabel.endsWith("l")
            val y = t + ch * (winY.getValue(winLabel) + 0.0f)
            val x0 = if (left) l + cw * 0.08f else r - cw * 0.08f
            val x1 = if (left) l - cw * 0.55f else r + cw * 0.55f
            c.drawLine(x0, y, x1, y, leadW)
            val pct = when (winLabel) { "fl" -> wp?.fl; "fr" -> wp?.fr; "rl" -> wp?.rl; else -> wp?.rr }
            c.drawText("Ventanilla" + (pct?.takeIf { it > 0 }?.let { " $it%" } ?: ""), x1 + (if (left) -6 else 6) * dpx, y + 5 * dpx,
                Paint(warn).apply { textAlign = if (left) Paint.Align.RIGHT else Paint.Align.LEFT })
        }
    }
}

