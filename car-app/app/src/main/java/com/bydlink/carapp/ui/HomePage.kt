package com.bydlink.carapp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bydlink.carapp.App
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.service.StateHub
import com.bydlink.carapp.ui.HomeActivity.Companion.ACCENT
import com.bydlink.carapp.ui.HomeActivity.Companion.AMBER
import com.bydlink.carapp.ui.HomeActivity.Companion.CARD2
import com.bydlink.carapp.ui.HomeActivity.Companion.MUTED
import com.bydlink.carapp.ui.HomeActivity.Companion.RED
import com.bydlink.carapp.ui.HomeActivity.Companion.TEXT
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import org.json.JSONObject

/**
 * Inicio (boceto del usuario): "Tu BYD" con la autonomía en grande, la batería, el viaje y las puertas; la navegación
 * ("¿Adónde vamos?": casa y buscar destino con la app del usuario, Waze en su coche); lo que suena; y la franja del
 * tiempo (Open-Meteo en la posición del coche) con la temperatura que mide el coche.
 */
internal fun HomeActivity.homePage(): View {
    val col = page()
    val row = LinearLayout(this).apply { orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
    col.addView(row, if (portrait) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(-1, 0, 1f))

    // ── Tu BYD ──
    val car = card()
    row.addView(car, if (portrait) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -1, 1.12f).apply { rightMargin = dp(16) })
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(label("Tu BYD", 17f, Color.WHITE, bold = true))
    val state = badge("…"); head.addView(state, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14) })
    head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    val warnTxt = label("", 15f, MUTED); head.addView(warnTxt)
    car.addView(head)
    val range = label("--", 58f, Color.WHITE).apply { letterSpacing = -0.03f; includeFontPadding = false }
    car.addView(range, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
    val rangeSub = label("Autonomía estimada", 15f, MUTED); car.addView(rangeSub)
    val socRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
    val soc = label("--%", 32f, Color.WHITE); socRow.addView(soc, LinearLayout.LayoutParams(0, -2, 1f))
    val socSub = label("Batería", 15f, MUTED); socRow.addView(socSub)
    car.addView(socRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    val bar = Bar(ACCENT); car.addView(bar, LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(8) })
    val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    car.addView(metrics, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    fun metric(): Pair<TextView, TextView> {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val v = label("--", 23f, Color.WHITE); val k = label("", 14f, MUTED)
        c.addView(v); c.addView(k.apply { setPadding(0, dp(4), 0, 0) })
        metrics.addView(c, LinearLayout.LayoutParams(0, -2, 1f)); return v to k
    }
    val m1 = metric(); val m2 = metric(); val m3 = metric()
    car.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
    val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val doors = label("", 15f, MUTED).apply { maxLines = 2 }; foot.addView(doors, LinearLayout.LayoutParams(0, -2, 1f))
    foot.addView(pill("Ver coche") { showTab("coche") }, LinearLayout.LayoutParams(-2, dp(52)))
    car.addView(foot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    car.setOnClickListener { showTab("coche") }

    // ── derecha: navegación + lo que suena ──
    val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    row.addView(right, if (portrait) LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) } else LinearLayout.LayoutParams(0, -1, 1f))
    val nav = card(); right.addView(nav)
    val navApp = navApp()
    nav.addView(label("Navegación" + (navApp?.let { " · ${appName(it.packageName)}" } ?: ""), 17f, Color.WHITE, bold = true))
    nav.addView(label("¿Adónde vamos?", 34f, Color.WHITE).apply { setPadding(0, dp(2), 0, dp(14)) })
    val navBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    navBtns.addView(iconPill("home", "Ir a casa") { goHome() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(12) })
    navBtns.addView(iconPill("search", "Buscar destino") { searchDestination() }, LinearLayout.LayoutParams(0, dp(52), 1f))
    nav.addView(navBtns)
    nav.setOnClickListener { navApp?.let { launch(it) } ?: toast("Instala una app de navegación (Waze o Google Maps)") }

    val music = card(); right.addView(music, if (portrait) LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        else LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })
    music.addView(label("Ahora suena", 17f, Color.WHITE, bold = true))
    val mRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = round(0xFF3A5468.toInt(), 14); clipToOutline = true }
    mRow.addView(art, LinearLayout.LayoutParams(dp(72), dp(72)))
    val mCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
    val mTitle = label("", 18f, Color.WHITE, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    val mArtist = label("", 15f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) }
    mCol.addView(mTitle); mCol.addView(mArtist)
    mRow.addView(mCol, LinearLayout.LayoutParams(0, -2, 1f))
    music.addView(mRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    val mCtl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    mCtl.addView(pill("Anterior") { controller?.transportControls?.skipToPrevious() }, LinearLayout.LayoutParams(-2, dp(52)).apply { rightMargin = dp(10) })
    val play = ImageView(this).apply { background = round(CARD2, 13); setPadding(dp(16), dp(14), dp(16), dp(14)); isClickable = true; setOnClickListener { playPause() } }
    mCtl.addView(play, LinearLayout.LayoutParams(dp(56), dp(52)).apply { rightMargin = dp(10) })
    mCtl.addView(pill("Siguiente") { controller?.transportControls?.skipToNext() }, LinearLayout.LayoutParams(-2, dp(52)))
    val openMusic = pill("Abrir música") { musicApp()?.let { launch(it) } ?: showTab("musica") }
    mCtl.addView(openMusic, LinearLayout.LayoutParams(-2, dp(52)))
    music.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
    music.addView(mCtl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    music.setOnClickListener {
        val pkg = controller?.packageName
        val i = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        if (i != null) startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) else showTab("musica")
    }
    fun renderMusic() {
        val c = controller; val md = c?.metadata
        val has = c != null && md != null
        mTitle.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "Nada sonando"
        mArtist.text = if (!has) (if (currentSessions() == null) "BYDLink aún no tiene permiso para ver la música" else "Toca para abrir la música")
            else listOfNotNull(md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                c?.packageName?.let { appName(it) }).joinToString(" · ")
        val bmp: Bitmap? = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) { art.setImageBitmap(bmp); art.setPadding(0, 0, 0, 0) }
        else { art.setImageDrawable(LineIcon("music", Color.WHITE)); art.setPadding(dp(22), dp(22), dp(22), dp(22)) }
        play.setImageDrawable(HomeActivity.MediaIcon(if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) HomeActivity.MediaIcon.PAUSE else HomeActivity.MediaIcon.PLAY))
        for (i in 0 until 3) mCtl.getChildAt(i).visibility = if (has) View.VISIBLE else View.GONE
        openMusic.visibility = if (has) View.GONE else View.VISIBLE
    }
    onMusic = { renderMusic() }
    renderMusic()

    // ── franja del tiempo ──
    val wx = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), dp(10), dp(22), dp(10)) }
    col.addView(wx, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(14) })
    val wIcon = iconView("sun", AMBER); wx.addView(wIcon, LinearLayout.LayoutParams(dp(24), dp(24)))
    val wTemp = label("--°", 24f, Color.WHITE, bold = true).apply { setPadding(dp(10), 0, dp(10), 0) }; wx.addView(wTemp)
    val wPlace = label("", 17f, TEXT).apply { maxLines = 1 }; wx.addView(wPlace, LinearLayout.LayoutParams(0, -2, 1.4f))
    val wWindI = iconView("wind", MUTED); wx.addView(wWindI, LinearLayout.LayoutParams(dp(22), dp(22)))
    val wWind = label("", 16f, TEXT).apply { setPadding(dp(8), 0, 0, 0) }; wx.addView(wWind, LinearLayout.LayoutParams(0, -2, 1f))
    val wRainI = iconView("drop", MUTED); wx.addView(wRainI, LinearLayout.LayoutParams(dp(20), dp(20)))
    val wRain = label("", 16f, TEXT).apply { setPadding(dp(8), 0, 0, 0) }; wx.addView(wRain, LinearLayout.LayoutParams(0, -2, 0.8f))
    val wCar = label("", 15f, MUTED).apply { gravity = Gravity.END }; wx.addView(wCar, LinearLayout.LayoutParams(0, -2, 1f))

    // ── datos que no están en el estado: viaje (Recorder) y último viaje (nube) ──
    var lastTrip: Map<*, *>? = null
    try {
        App.db.getReference("cars/${BuildConfigCompat.carId(this)}/trips").orderByChild("start").limitToLast(1)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { lastTrip = s.children.lastOrNull()?.value as? Map<*, *> }
                override fun onCancelled(e: DatabaseError) {}
            })
    } catch (e: Throwable) {}
    loadHomePlace()

    refreshTab = {
        val s = StateHub.state
        val w = warnings(s)
        warnTxt.text = when { s == null -> ""; w.isEmpty() -> "Sin avisos"; w.size == 1 -> "1 aviso"; else -> "${w.size} avisos" }
        warnTxt.setTextColor(if (w.isEmpty()) MUTED else AMBER)
        state.text = stateText(s)
        range.text = SpannableString("${s?.rangeKm ?: "--"} km").apply {
            val i = length - 3
            setSpan(RelativeSizeSpan(0.42f), i, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(MUTED), i, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val p = s?.soc
        soc.text = p?.let { "$it%" } ?: "--%"
        socSub.text = s?.energyLeftKwh?.let { "Batería · ${fmt1(it)} kWh" } ?: "Batería"
        bar.set(p ?: 0, when { p == null -> MUTED; p <= 15 -> RED; p <= 30 -> AMBER; else -> ACCENT })
        if (s?.charging == true) {
            m1.first.text = s.chargeKw?.let { fmt1(it) } ?: s.chargingPower?.toString() ?: "--"; m1.second.text = "kW cargando"
            m2.first.text = s.chargedKwh?.let { "+${fmt1(it)}" } ?: "--"; m2.second.text = "kWh en esta carga"
            m3.first.text = s.chargeRestMin?.takeIf { it in 1..2880 }?.let { dur(it) } ?: "--"; m3.second.text = "para llenar"
        } else {
            val t = currentTrip(this)
            val lt = lastTrip
            m1.first.text = (if (t != null) s?.lastTripKwh100 else (lt?.get("kwh100") as? Number)?.toDouble() ?: s?.lastTripKwh100)
                ?.let { fmt1(it) } ?: s?.consumptionKwh100?.let { fmt1(it) } ?: "--"
            m1.second.text = "kWh/100 km"
            if (t != null) {
                m2.first.text = fmt1(t.first); m2.second.text = "km de viaje"
                m3.first.text = dur(t.second); m3.second.text = "de viaje"
            } else {
                m2.first.text = (lt?.get("distanceKm") as? Number)?.toDouble()?.let { fmt1(it) } ?: "--"; m2.second.text = "km último viaje"
                m3.first.text = ((lt?.get("durationSec") as? Number)?.toInt())?.let { dur(it / 60) } ?: "--"; m3.second.text = "último viaje"
            }
        }
        val dd = s?.doors
        val open = dd != null && listOf(dd.fl, dd.fr, dd.rl, dd.rr).any { it == true }
        doors.text = when {
            s == null -> "Esperando al coche…"
            w.isNotEmpty() -> w.first().first
            s.locked == true -> "Puertas cerradas · coche cerrado"
            open -> "Puerta abierta"
            else -> "Puertas cerradas"
        }
        doors.setTextColor(if (w.isNotEmpty()) AMBER else MUTED)
        // tiempo
        val loc = s?.location
        if (loc != null) Weather.refresh(applicationContext, loc.lat, loc.lng) { }
        val now = Weather.last
        if (now != null) {
            wIcon.setImageDrawable(LineIcon(now.icon, if (now.icon == "sun") AMBER else Color.WHITE))
            wTemp.text = "${Math.round(now.temp)}°"
            wPlace.text = listOfNotNull(now.place, now.text).joinToString(" · ")
            wWind.text = now.wind; wRain.text = now.rainPct?.let { "$it% lluvia" } ?: "--"
            wWindI.visibility = View.VISIBLE; wRainI.visibility = View.VISIBLE
        } else {
            wIcon.setImageDrawable(LineIcon("cloud", MUTED)); wTemp.text = s?.outTemp?.let { "$it°" } ?: "--°"
            wPlace.text = if (loc == null || (loc.lat == 0.0 && loc.lng == 0.0)) "El tiempo: esperando la posición del coche" else (Weather.error ?: "Buscando el tiempo…")
            wWind.text = ""; wRain.text = ""; wWindI.visibility = View.INVISIBLE; wRainI.visibility = View.INVISIBLE
        }
        wCar.text = s?.outTemp?.let { "Sensor del coche · $it°" } ?: ""
    }
    return if (portrait) ScrollView(this).apply { addView(col) } else col
}

/** Viaje en curso según el Recorder (km, minutos), o null si ahora no hay viaje. */
private fun currentTrip(ctx: Context): Pair<Double, Int>? = try {
    ctx.getSharedPreferences("recorder", Context.MODE_PRIVATE).getString("trip", null)?.let { j ->
        val o = JSONObject(j)
        val start = o.optLong("start"); val last = o.optLong("lastTs", start)
        if (System.currentTimeMillis() - last > 15 * 60_000L) null else o.optDouble("km", 0.0) to ((last - start) / 60_000L).toInt()
    }
} catch (e: Throwable) { null }

/** "Casa" del historial (cars/{id}/historyCfg/places), guardada en el coche para usarla sin cobertura. */
private fun HomeActivity.loadHomePlace() {
    try {
        App.db.getReference("cars/${BuildConfigCompat.carId(this)}/historyCfg/places").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val home = s.children.mapNotNull { it.value as? Map<*, *> }.firstOrNull { (it["name"] as? String)?.trim()?.equals("casa", true) == true }
                    ?: s.children.mapNotNull { it.value as? Map<*, *> }.firstOrNull { it["price"] == "home" }
                val lat = (home?.get("lat") as? Number)?.toDouble(); val lng = (home?.get("lng") as? Number)?.toDouble()
                if (lat != null && lng != null) prefs.edit().putString("homePlace", "$lat,$lng").apply()
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    } catch (e: Throwable) {}
}

/** Ir a casa: el lugar "Casa" del historial; si no hay, la casa guardada en Waze / Google Maps. */
internal fun HomeActivity.goHome() {
    val p = prefs.getString("homePlace", null)?.split(",")?.mapNotNull { it.toDoubleOrNull() }
    if (p != null && p.size == 2) navTo(p[0], p[1]) else goFavorite("home")
}

/** Buscar destino: parado, se escribe aquí; en marcha, se abre el navegador para usar su voz. */
internal fun HomeActivity.searchDestination() {
    if (moving()) { navApp()?.let { launch(it) }; toast("En marcha no se escribe: usa la voz de tu navegador"); return }
    val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xF20B1016.toInt()); setPadding(dp(60), dp(40), dp(60), dp(40)) }
    panel.addView(label("¿Adónde vamos?", 34f, Color.WHITE))
    val box = android.widget.EditText(this).apply {
        hint = "Calle, sitio o ciudad…"; setHintTextColor(MUTED); setTextColor(Color.WHITE); textSize = 22f; isSingleLine = true
        inputType = android.text.InputType.TYPE_CLASS_TEXT; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
        background = round(CARD2, 14); setPadding(dp(20), 0, dp(20), 0)
    }
    panel.addView(box, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(18) })
    val go = {
        val q = box.text.toString().trim()
        if (q.isNotEmpty()) { closeOverlay(); navigate(android.net.Uri.parse("waze://?q=${android.net.Uri.encode(q)}&navigate=yes"),
            android.net.Uri.parse("google.navigation:q=${android.net.Uri.encode(q)}")) }
    }
    box.setOnEditorActionListener { _, id, _ -> if (id == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { go(); true } else false }
    val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    btns.addView(iconPill("nav", "Ir") { go() }.apply { background = round(ACCENT, 13); (getChildAt(1) as TextView).setTextColor(HomeActivity.ON_ACCENT)
        (getChildAt(0) as ImageView).setImageDrawable(LineIcon("nav", HomeActivity.ON_ACCENT)) }, LinearLayout.LayoutParams(dp(180), dp(56)))
    btns.addView(iconPill("home", "Casa") { closeOverlay(); goHome() }, LinearLayout.LayoutParams(dp(160), dp(56)).apply { leftMargin = dp(12) })
    btns.addView(iconPill("x", "Cerrar") { closeOverlay() }, LinearLayout.LayoutParams(dp(160), dp(56)).apply { leftMargin = dp(12) })
    panel.addView(btns, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
    openOverlay(panel)
    box.requestFocus()
    ui.postDelayed({ (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(box, 0) }, 200)
}
