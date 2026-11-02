package com.bydlink.carapp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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
import com.bydlink.carapp.ui.HomeActivity.Companion.INK
import com.bydlink.carapp.ui.HomeActivity.Companion.MUTED
import com.bydlink.carapp.ui.HomeActivity.Companion.OK
import com.bydlink.carapp.ui.HomeActivity.Companion.ON_ACCENT
import com.bydlink.carapp.ui.HomeActivity.Companion.RED
import com.bydlink.carapp.ui.HomeActivity.Companion.TEXT
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import org.json.JSONObject

/**
 * Inicio "Estela": tarjetas de cristal. A la izquierda, ¿adónde vamos? (casa, trabajo, buscar); en el centro la energía
 * (anillo con la autonomía o la carga), la seguridad (cierre, puertas, centinela), el viaje con las ruedas y cuatro
 * accesos; a la derecha lo que suena. Nada se anima solo: se repinta una vez por segundo con los datos del coche.
 */
internal fun HomeActivity.homePage(): View {
    val pal = Skins.cur
    val light = Typeface.create("sans-serif-light", Typeface.NORMAL)

    // ── energía: la tarjeta que manda ──
    val energy = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(14), dp(24), dp(14)) }
    energy.setOnClickListener { showTab("bateria") }
    val ring = SquareFrame(this)
    val gauge = Gauge(this, pal, resources.displayMetrics.density)
    ring.addView(gauge, FrameLayout.LayoutParams(-1, -1))
    val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    val gK = label("Autonomía", 14f, TEXT).apply { gravity = Gravity.CENTER }
    val gNum = label("--", 46f, INK).apply {
        gravity = Gravity.CENTER; typeface = light; includeFontPadding = false; letterSpacing = -0.02f
        if (pal.neon) setShadowLayer(dp(12).toFloat(), 0f, 0f, Skins.alpha(pal.accent, .75f))
    }
    val gSub = label("", 14f, TEXT).apply { gravity = Gravity.CENTER }
    inner.addView(gK); inner.addView(gNum); inner.addView(gSub)
    ring.addView(inner, FrameLayout.LayoutParams(-1, -1))
    energy.addView(ring, LinearLayout.LayoutParams(-2, -1))
    val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, 0, 0); gravity = Gravity.CENTER_VERTICAL }
    val stateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val dot = View(this)
    stateRow.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))
    val stateTxt = label("…", 21f, INK).apply { typeface = light; setPadding(dp(10), 0, 0, 0); maxLines = 2 }
    stateRow.addView(stateTxt, LinearLayout.LayoutParams(0, -2, 1f))
    info.addView(stateRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    fun kvRow(): Pair<TextView, TextView> {
        info.addView(View(this).apply { setBackgroundColor(pal.divider) }, LinearLayout.LayoutParams(-1, 1))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(9), 0, dp(9)) }
        val k = label("", 15f, MUTED).apply { maxLines = 1 }; val v = label("--", 18f, INK).apply { gravity = Gravity.END; maxLines = 1 }
        row.addView(k, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(v)
        info.addView(row); return k to v
    }
    val kv1 = kvRow(); val kv2 = kvRow(); val kv3 = kvRow()
    energy.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

    // ── seguridad ──
    val sec = card().apply { setOnClickListener { showTab("camara") } }
    val secHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    secHead.addView(label("Seguridad", 17f, INK, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
    val lockPill = label("—", 14f, MUTED).apply { setPadding(dp(12), dp(5), dp(12), dp(5)) }
    secHead.addView(lockPill)
    sec.addView(secHead)
    val sentTxt = label("Centinela…", 15f, TEXT).apply { setPadding(0, dp(10), 0, 0) }; sec.addView(sentTxt)
    val doorsTxt = label("", 15f, MUTED).apply { setPadding(0, dp(6), 0, 0); maxLines = 2 }; sec.addView(doorsTxt)

    // ── viaje y ruedas ──
    val trip = card().apply { setOnClickListener { showTab("bateria") } }
    val tripHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val tripTitle = label("Viaje", 17f, INK, bold = true); tripHead.addView(tripTitle, LinearLayout.LayoutParams(0, -2, 1f))
    trip.addView(tripHead)
    val nums = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
    fun num(unit: String): TextView {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val v = label("--", 26f, INK).apply { typeface = light; includeFontPadding = false }
        c.addView(v); c.addView(label(unit, 13f, MUTED)); nums.addView(c, LinearLayout.LayoutParams(0, -2, 1f)); return v
    }
    val tKm = num("km"); val tMin = num("min"); val tCons = num("kWh/100")
    trip.addView(nums)
    val tyreRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
    val tyreIco = iconView("tyre", ACCENT); tyreRow.addView(tyreIco, LinearLayout.LayoutParams(dp(18), dp(18)))
    val tyreTxt = label("Ruedas --", 14f, TEXT).apply { setPadding(dp(8), 0, 0, 0); maxLines = 1 }; tyreRow.addView(tyreTxt)
    trip.addView(tyreRow)

    // ── accesos: solo lo que la app ya sabe hacer ──
    var sentryOn: Boolean? = null
    val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    fun qa(icon: String, text: String, onClick: () -> Unit): Pair<LinearLayout, ImageView> {
        val ic = iconView(icon, INK)
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; setOnClickListener { onClick() }
            background = Skins.glass(pal, dp(20).toFloat(), maxOf(1, dp(1)))
            addView(ic, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(label(text, 14f, INK).apply { gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0); maxLines = 1 })
        }
        quick.addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = if (quick.childCount == 0) 0 else dp(12) })
        return b to ic
    }
    fun lit(b: Pair<LinearLayout, ImageView>, on: Boolean, icon: String) {
        b.first.background = if (on) Skins.tinted(pal, dp(20).toFloat(), maxOf(1, dp(1))) else Skins.glass(pal, dp(20).toFloat(), maxOf(1, dp(1)))
        b.second.setImageDrawable(LineIcon(icon, if (on) ACCENT else INK))
    }
    val qSentry = qa("shield", "Centinela") {
        run("toggleSentry", emptyMap()) { ok, d -> if (ok) { sentryOn = d["on"] == true; refreshTab?.invoke(); toast(if (sentryOn == true) "Centinela activado" else "Centinela desactivado") } }
    }
    val qClima = qa("fan", "Climatizar") { climate(mapOf("on" to (StateHub.state?.climate?.on != true))) }
    qa("window", "Ventanillas") { send("closeWindows", emptyMap(), "Cerrando ventanillas") }
    qa("camera", "Cámaras") { showTab("camara") }
    try {
        App.db.getReference("cars/${BuildConfigCompat.carId(this)}/config/sentry/on").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { sentryOn = s.value as? Boolean ?: false; refreshTab?.invoke() }
            override fun onCancelled(e: DatabaseError) {}
        })
    } catch (e: Throwable) {}

    // ── ¿adónde vamos? ──
    val nav = card()
    val navApp = navApp()
    nav.addView(label("¿Adónde vamos?", 30f, INK).apply { typeface = light; includeFontPadding = false })
    nav.addView(label(navApp?.let { "Con ${appName(it.packageName)}" } ?: "Instala Waze o Google Maps", 14f, MUTED).apply { setPadding(0, dp(4), 0, dp(14)) })
    nav.addView(iconPill("search", "Buscar destino") { searchDestination() }, LinearLayout.LayoutParams(-1, dp(56)))
    fun place(icon: String, name: String, onClick: () -> Unit) {
        nav.addView(View(this).apply { setBackgroundColor(pal.divider) }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = if (nav.childCount <= 3) dp(12) else 0 })
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isClickable = true; setOnClickListener { onClick() }; setPadding(0, dp(10), 0, dp(10)) }
        val ico = ImageView(this).apply { setImageDrawable(LineIcon(icon, ACCENT)); setPadding(dp(10), dp(10), dp(10), dp(10)); background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CARD2) } }
        r.addView(ico, LinearLayout.LayoutParams(dp(42), dp(42)))
        r.addView(label(name, 18f, INK).apply { setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(iconView("right", MUTED), LinearLayout.LayoutParams(dp(18), dp(18)))
        nav.addView(r)
    }
    place("home", "Casa") { goHome() }
    place("leaf", "Trabajo") { goFavorite("work") }
    place("nav", "Abrir el navegador") { navApp?.let { launch(it) } ?: toast("Instala una app de navegación (Waze o Google Maps)") }

    // ── lo que suena ──
    val music = card()
    val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = round(CARD2, 18); clipToOutline = true }
    music.addView(art, LinearLayout.LayoutParams(-1, 0, 1f))
    val mTitle = label("", 20f, INK, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(12), 0, 0) }
    val mArtist = label("", 15f, TEXT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) }
    music.addView(mTitle); music.addView(mArtist)
    val prog = Bar(ACCENT); music.addView(prog, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(12) })
    val mCtl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    fun round2(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    val prev = ImageView(this).apply { setImageDrawable(HomeActivity.MediaIcon(HomeActivity.MediaIcon.PREV, INK)); setPadding(dp(17), dp(17), dp(17), dp(17)); background = round2(CARD2); isClickable = true; setOnClickListener { controller?.transportControls?.skipToPrevious() } }
    val play = ImageView(this).apply { setPadding(dp(22), dp(22), dp(22), dp(22)); background = round2(ACCENT); isClickable = true; setOnClickListener { playPause() } }
    val next = ImageView(this).apply { setImageDrawable(HomeActivity.MediaIcon(HomeActivity.MediaIcon.NEXT, INK)); setPadding(dp(17), dp(17), dp(17), dp(17)); background = round2(CARD2); isClickable = true; setOnClickListener { controller?.transportControls?.skipToNext() } }
    mCtl.addView(prev, LinearLayout.LayoutParams(dp(54), dp(54)))
    mCtl.addView(play, LinearLayout.LayoutParams(dp(70), dp(70)).apply { leftMargin = dp(18); rightMargin = dp(18) })
    mCtl.addView(next, LinearLayout.LayoutParams(dp(54), dp(54)))
    val openMusic = pill("Abrir música") { musicApp()?.let { launch(it) } ?: showTab("musica") }
    music.addView(mCtl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    music.addView(openMusic, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
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
            else listOfNotNull(md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM), c?.packageName?.let { appName(it) }).joinToString(" · ")
        val bmp: Bitmap? = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) { art.setImageBitmap(bmp); art.setPadding(0, 0, 0, 0) }
        else { art.setImageDrawable(LineIcon("music", MUTED)); art.setPadding(dp(56), dp(56), dp(56), dp(56)) }
        play.setImageDrawable(HomeActivity.MediaIcon(if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) HomeActivity.MediaIcon.PAUSE else HomeActivity.MediaIcon.PLAY, ON_ACCENT))
        mCtl.visibility = if (has) View.VISIBLE else View.GONE
        prog.visibility = if (has) View.VISIBLE else View.INVISIBLE
        openMusic.visibility = if (has) View.GONE else View.VISIBLE
    }
    fun progress(): Int? {
        val c = controller ?: return null; val st = c.playbackState ?: return null
        val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (dur <= 0L) return null
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING) pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        return (pos * 100 / dur).toInt().coerceIn(0, 100)
    }
    onMusic = { renderMusic() }
    renderMusic()

    // ── montaje ──
    val root: View
    if (portrait) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(energy, LinearLayout.LayoutParams(-1, dp(250)))
        val duo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        duo.addView(sec, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(12) }); duo.addView(trip, LinearLayout.LayoutParams(0, -1, 1f))
        col.addView(duo, LinearLayout.LayoutParams(-1, dp(176)).apply { topMargin = dp(12) })
        col.addView(quick, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(12) })
        val pair = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pair.addView(nav, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(12) }); pair.addView(music, LinearLayout.LayoutParams(0, -1, 1f))
        col.addView(pair, LinearLayout.LayoutParams(-1, dp(470)).apply { topMargin = dp(12) })
        root = ScrollView(this).apply { addView(col); isVerticalScrollBarEnabled = false }
    } else {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(nav, LinearLayout.LayoutParams(0, -1, 0.95f))
        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        center.addView(energy, LinearLayout.LayoutParams(-1, 0, 1.2f))
        val duo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        duo.addView(sec, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(14) }); duo.addView(trip, LinearLayout.LayoutParams(0, -1, 1f))
        center.addView(duo, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })
        center.addView(quick, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(14) })
        row.addView(center, LinearLayout.LayoutParams(0, -1, 1.55f).apply { leftMargin = dp(16); rightMargin = dp(16) })
        row.addView(music, LinearLayout.LayoutParams(0, -1, 0.95f))
        root = row
    }

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
        val soc = s?.soc
        // energía
        if (s?.charging == true) {
            gK.text = "Cargando"; gNum.text = soc?.let { "$it %" } ?: "--"; gSub.text = s.chargeKw?.let { "${fmt1(it)} kW" } ?: s.chargingPower?.let { "$it kW" } ?: ""
            stateTxt.text = s.chargeRestMin?.takeIf { it in 1..2880 }?.let { "Cargando · ${dur(it)} para llenar" } ?: "Cargando"
            kv1.first.text = "Potencia"; kv1.second.text = s.chargeKw?.let { "${fmt1(it)} kW" } ?: s.chargingPower?.let { "$it kW" } ?: "--"
            kv2.first.text = "En esta carga"; kv2.second.text = s.chargedKwh?.let { "+${fmt1(it)} kWh" } ?: "--"
            kv3.first.text = "Límite de carga"; kv3.second.text = s.chargeLimit?.let { "$it %" } ?: "--"
        } else {
            gK.text = "Autonomía"; gNum.text = s?.rangeKm?.toString() ?: "--"; gSub.text = soc?.let { "km · $it %" } ?: "km"
            stateTxt.text = when {
                s == null -> "Esperando al coche…"
                w.isNotEmpty() -> w.first().first
                moving(s) -> "En marcha · ${s.speed} km/h"
                else -> "Listo para salir"
            }
            kv1.first.text = "Energía"; kv1.second.text = s?.energyLeftKwh?.let { "${fmt1(it)} kWh" } ?: "--"
            kv2.first.text = "Consumo"; kv2.second.text = (s?.consumptionKwh100 ?: s?.lastTripKwh100)?.let { "${fmt1(it)} kWh/100" } ?: "--"
            kv3.first.text = "Límite de carga"; kv3.second.text = s?.chargeLimit?.let { "$it %" } ?: "--"
        }
        gauge.set((soc ?: 0) / 100f, s?.charging == true)
        val stColor = when { s == null -> MUTED; w.isNotEmpty() -> AMBER; s.charging -> OK; moving(s) -> ACCENT; else -> OK }
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(stColor) }
        stateTxt.setTextColor(if (w.isNotEmpty()) AMBER else INK)
        if (soc != null && soc <= 15) gSub.setTextColor(RED) else gSub.setTextColor(TEXT)
        // seguridad
        val locked = s?.locked
        lockPill.text = when (locked) { true -> "Cerrado"; false -> "Abierto"; null -> "—" }
        lockPill.setTextColor(when (locked) { true -> OK; false -> AMBER; null -> MUTED })
        lockPill.background = round(Skins.alpha(when (locked) { true -> OK; false -> AMBER; null -> MUTED }, .16f), 20)
        sentTxt.text = when (sentryOn) { true -> "Centinela activo · graba si alguien se acerca"; false -> "Centinela en pausa"; null -> "Centinela…" }
        sentTxt.setTextColor(if (sentryOn == true) ACCENT else TEXT)
        val win = s?.windows
        val openWin = win != null && (win.fl || win.fr || win.rl || win.rr || win.sunroof)
        doorsTxt.text = when {
            s == null -> ""
            w.isNotEmpty() -> w.first().first
            openWin -> "Hay una ventanilla abierta"
            else -> "Puertas y ventanillas cerradas"
        }
        doorsTxt.setTextColor(if (w.isNotEmpty() || openWin) AMBER else MUTED)
        lit(qSentry, sentryOn == true, "shield")
        lit(qClima, s?.climate?.on == true, "fan")
        // viaje
        val t = currentTrip(this)
        val lt = lastTrip
        if (t != null) {
            tripTitle.text = "Este viaje"; tKm.text = fmt1(t.first); tMin.text = t.second.toString()
            tCons.text = (s?.lastTripKwh100 ?: s?.consumptionKwh100)?.let { fmt1(it) } ?: "--"
        } else {
            tripTitle.text = "Último viaje"
            tKm.text = (lt?.get("distanceKm") as? Number)?.toDouble()?.let { fmt1(it) } ?: "--"
            tMin.text = ((lt?.get("durationSec") as? Number)?.toInt())?.let { (it / 60).toString() } ?: "--"
            tCons.text = ((lt?.get("kwh100") as? Number)?.toDouble() ?: s?.lastTripKwh100)?.let { fmt1(it) } ?: "--"
        }
        val ty = s?.tyres
        val bars = listOfNotNull(ty?.fl, ty?.fr, ty?.rl, ty?.rr)
        tyreTxt.text = if (bars.isEmpty()) "Ruedas: sin dato" else "Ruedas " + bars.joinToString(" · ") { fmt1(it) } + " bar"
        val low = bars.any { it < 2.0 }
        tyreTxt.setTextColor(if (low) AMBER else TEXT); tyreIco.setImageDrawable(LineIcon("tyre", if (low) AMBER else ACCENT))
        // música y tiempo
        val pr = progress(); if (pr != null) prog.set(pr, ACCENT)
        val loc = s?.location
        if (loc != null && !(loc.lat == 0.0 && loc.lng == 0.0)) Weather.refresh(applicationContext, loc.lat, loc.lng) { }
    }
    return root
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
    val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Skins.cur.sheet); setPadding(dp(60), dp(40), dp(60), dp(40)) }
    panel.addView(label("¿Adónde vamos?", 34f, INK))
    val box = android.widget.EditText(this).apply {
        hint = "Calle, sitio o ciudad…"; setHintTextColor(MUTED); setTextColor(INK); textSize = 22f; isSingleLine = true
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
    btns.addView(iconPill("nav", "Ir") { go() }.apply { background = round(ACCENT, 13); (getChildAt(1) as TextView).setTextColor(ON_ACCENT)
        (getChildAt(0) as ImageView).setImageDrawable(LineIcon("nav", ON_ACCENT)) }, LinearLayout.LayoutParams(dp(180), dp(56)))
    btns.addView(iconPill("home", "Casa") { closeOverlay(); goHome() }, LinearLayout.LayoutParams(dp(160), dp(56)).apply { leftMargin = dp(12) })
    btns.addView(iconPill("x", "Cerrar") { closeOverlay() }, LinearLayout.LayoutParams(dp(160), dp(56)).apply { leftMargin = dp(12) })
    panel.addView(btns, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
    openOverlay(panel)
    box.requestFocus()
    ui.postDelayed({ (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(box, 0) }, 200)
}
