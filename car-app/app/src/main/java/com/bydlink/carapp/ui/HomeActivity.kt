package com.bydlink.carapp.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.car.Capabilities
import com.bydlink.carapp.model.CarState
import com.bydlink.carapp.service.MainService
import com.bydlink.carapp.service.MediaListener
import com.bydlink.carapp.service.StateHub

/**
 * Pantalla de inicio de BYDLink (opcional, ver LauncherMode), al estilo de los lanzadores para BYD:
 *  - columna izquierda fija: Estado (velocidad, marcha, autonomía, batería, 12 V, ruedas), Música (solo enseña y controla
 *    lo que ya suena: no reproduce nada) y un dock de apps;
 *  - panel derecho con pestañas: Inicio (reloj, avisos y tarjeta de carga/viaje), Coche (mandos según lo que tiene este
 *    coche; en marcha solo el clima), Apps y Ajustes.
 * No se mete en las apps: navegación, música, etc. se abren tal cual. Pulsación larga en el dock → elegir la app.
 */
class HomeActivity : Activity() {

    internal val ui = Handler(Looper.getMainLooper())
    internal val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    private var portrait = false

    // Estado
    private lateinit var speedRing: SpeedRing
    private lateinit var gearViews: Map<String, TextView>
    private lateinit var outTxt: TextView
    private lateinit var rangeTxt: TextView
    private lateinit var battTxt: TextView
    private lateinit var battBar: Bar
    private lateinit var auxTxt: TextView
    private lateinit var tyreTxt: Map<String, TextView>
    // Música
    private lateinit var musicArt: ImageView
    private lateinit var musicTitle: TextView
    private lateinit var musicArtist: TextView
    private lateinit var musicBar: Bar
    private lateinit var musicPlay: ImageView
    private lateinit var musicControls: View
    private lateinit var musicEmpty: View
    // Panel
    private lateinit var dock: LinearLayout
    private lateinit var content: FrameLayout
    private val tabs = LinkedHashMap<String, TextView>()
    private var tab = "inicio"
    private var tabView: View? = null
    private var overlay: FrameLayout? = null
    internal var refreshTab: (() -> Unit)? = null
    /** Lo que hay que soltar al salir de la página (p. ej. la cámara en directo). */
    internal var leaveTab: (() -> Unit)? = null
    internal var onOverlayClosed: (() -> Unit)? = null
    internal var caps: Map<String, Boolean> = emptyMap()

    private val tick = object : Runnable {
        override fun run() { renderStatus(); renderMusicProgress(); refreshTab?.invoke(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // El servicio vive aunque no se abra la app (al ser la pantalla de inicio, arranca con el coche).
        try { ContextCompat.startForegroundService(this, Intent(this, MainService::class.java)) } catch (e: Throwable) {}
        portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        build()
        // Qué tiene este coche (multimodelo), para la pestaña Coche. En su hilo: pregunta al SDK.
        if (BuildConfigCompat.USE_BYD_SDK) Thread { try { caps = Capabilities.local(applicationContext) } catch (e: Throwable) {} }.start()
    }

    override fun onResume() {
        super.onResume(); renderDock(); startMusic(); ui.post(tick)
        resumeTab?.let { if (it == "camara" && tab == "camara") showTab(it) }; resumeTab = null
    }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick); stopMusic(); leaveTab?.invoke(); leaveTab = null; resumeTab = tab }
    /** Página a rehacer al volver (si soltó algo al salir, como la cámara). */
    private var resumeTab: String? = null
    // Casita: al inicio. Los botones del volante pueden pedir otra página (extra "tab").
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); closeOverlay(); showTab(intent?.getStringExtra("tab") ?: "inicio") }
    override fun onBackPressed() { if (overlay != null) closeOverlay() else if (tab != "inicio") showTab("inicio") }

    // ═════════════════════════ estructura ═════════════════════════
    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(statusCard(), LinearLayout.LayoutParams(-1, 0, 1f))
        left.addView(musicCard(), LinearLayout.LayoutParams(-1, dp(150)).apply { topMargin = dp(12) })
        dock = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        left.addView(dock, LinearLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(10) })
        root.addView(left, if (portrait) LinearLayout.LayoutParams(-1, dp(620)) else LinearLayout.LayoutParams(dp(324), -1))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        right.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        right.addView(tabBar(), LinearLayout.LayoutParams(-2, dp(56)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
        root.addView(right, if (portrait) LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) }
            else LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(16) })

        val frame = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0B1118.toInt(), 0xFF0E1622.toInt(), 0xFF0A0E13.toInt()))
            addView(root, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(frame)
        showTab(intent?.getStringExtra("tab") ?: "inicio")
    }

    // ═════════════════════════ Estado ═════════════════════════
    private fun statusCard(): View {
        val c = card()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("Estado", 16f, MUTED, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        outTxt = label("", 15f, TEXT); head.addView(outTxt)
        c.addView(head)
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        speedRing = SpeedRing()
        mid.addView(speedRing, LinearLayout.LayoutParams(dp(132), dp(132)))
        val gears = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        gearViews = listOf("P", "R", "N", "D").associateWith { g ->
            label(g, 17f, MUTED, bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(4), dp(12), dp(4)) }
                .also { gears.addView(it) }
        }
        mid.addView(gears, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        c.addView(mid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        rangeTxt = kv(c, "Autonomía")
        battTxt = kv(c, "Batería")
        battBar = Bar(ACCENT); c.addView(battBar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(4) })
        auxTxt = kv(c, "Batería 12 V")
        val tg = GridLayout(this).apply { columnCount = 2 }
        tyreTxt = linkedMapOf("fl" to "Del. izq.", "fr" to "Del. der.", "rl" to "Tras. izq.", "rr" to "Tras. der.").mapValues { (_, name) ->
            val cell = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), dp(8), 0) }
            cell.addView(label(name, 13f, MUTED), LinearLayout.LayoutParams(0, -2, 1f))
            val v = label("--", 14f, TEXT, bold = true); cell.addView(v)
            tg.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
            v
        }
        c.addView(tg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        c.setOnClickListener { showTab("coche") }
        return c
    }

    internal fun kv(parent: LinearLayout, k: String): TextView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, 0) }
        row.addView(label(k, 15f, MUTED), LinearLayout.LayoutParams(0, -2, 1f))
        val v = label("--", 15f, Color.WHITE, bold = true); row.addView(v)
        parent.addView(row)
        return v
    }

    private fun renderStatus() {
        val s = StateHub.state
        val moving = s != null && (s.speed > 0 || s.gear == "D" || s.gear == "R")
        speedRing.speed = s?.speed ?: 0; speedRing.invalidate()
        gearViews.forEach { (g, v) ->
            val on = s?.gear == g
            v.setTextColor(if (on) Color.WHITE else MUTED)
            v.background = if (on) round(ACCENT_DIM, 10) else null
        }
        outTxt.text = s?.outTemp?.let { "$it °${s.tempUnit}" } ?: ""
        rangeTxt.text = s?.rangeKm?.let { "$it km" } ?: "--"
        val soc = s?.soc
        battTxt.text = buildString {
            append(soc?.let { "$it %" } ?: "--")
            s?.energyLeftKwh?.let { append(" · ${fmt1(it)} kWh") }
        }
        battBar.set(soc ?: 0, when { soc == null -> MUTED; soc <= 15 -> RED; soc <= 30 -> AMBER; else -> ACCENT })
        val v12 = s?.aux12v
        auxTxt.text = v12?.let { "${fmt1(it)} V" } ?: "--"
        auxTxt.setTextColor(if (v12 != null && v12 < 12.0) AMBER else Color.WHITE)
        val ty = s?.tyres; val tt = s?.tyreTemps
        tyreTxt.forEach { (k, v) ->
            val p = when (k) { "fl" -> ty?.fl; "fr" -> ty?.fr; "rl" -> ty?.rl; else -> ty?.rr }
            val t = when (k) { "fl" -> tt?.fl; "fr" -> tt?.fr; "rl" -> tt?.rl; else -> tt?.rr }
            v.text = (p?.let { fmt1(it) } ?: "--") + (t?.let { " · $it°" } ?: "")
            v.setTextColor(if (p != null && (p < 2.0 || p > 3.0)) AMBER else Color.WHITE)
        }
        speedRing.alpha = if (moving || s == null) 1f else 0.85f
    }

    // ═════════════════════════ Música ═════════════════════════
    private var msm: MediaSessionManager? = null
    internal var controller: MediaController? = null
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> ui.post { pickController(list) } }
    private val controllerCb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { renderMusic() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { renderMusic() }
        override fun onSessionDestroyed() { ui.post { pickController(currentSessions()) } }
    }

    private fun musicCard(): View {
        val c = card()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        musicArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = round(CARD2, 10); clipToOutline = true }
        row.addView(musicArt, LinearLayout.LayoutParams(dp(56), dp(56)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        musicTitle = label("", 16f, Color.WHITE, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        musicArtist = label("", 14f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        col.addView(musicTitle); col.addView(musicArtist)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(row)
        musicBar = Bar(TEXT); c.addView(musicBar, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(10) })
        val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun btn(kind: Int, onClick: () -> Unit) = ImageView(this).apply {
            setImageDrawable(MediaIcon(kind)); setPadding(dp(10), dp(6), dp(10), dp(6)); isClickable = true; setOnClickListener { onClick() }
        }.also { ctl.addView(it, LinearLayout.LayoutParams(dp(64), dp(40))) }
        btn(MediaIcon.PREV) { controller?.transportControls?.skipToPrevious() }
        musicPlay = btn(MediaIcon.PLAY) {
            val c2 = controller ?: return@btn
            if (c2.playbackState?.state == PlaybackState.STATE_PLAYING) c2.transportControls.pause() else c2.transportControls.play()
        }
        btn(MediaIcon.NEXT) { controller?.transportControls?.skipToNext() }
        musicControls = ctl
        c.addView(ctl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        musicEmpty = label("Nada sonando · toca para abrir la música", 14f, MUTED).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        c.addView(musicEmpty, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        c.setOnClickListener {
            val pkg = controller?.packageName
            val i = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
            if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else slotTarget(1)?.let { launch(it) }
        }
        return c
    }

    internal fun currentSessions(): List<MediaController>? = try {
        msm?.getActiveSessions(ComponentName(this, MediaListener::class.java))
    } catch (e: SecurityException) { null }

    private fun startMusic() {
        msm = getSystemService(MediaSessionManager::class.java)
        try { msm?.addOnActiveSessionsChangedListener(sessionsListener, ComponentName(this, MediaListener::class.java)) } catch (e: SecurityException) {}
        pickController(currentSessions())
    }

    private fun stopMusic() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Throwable) {}
        controller?.unregisterCallback(controllerCb); controller = null
    }

    /** Elegir a mano qué app que suena se controla (página Música). */
    internal fun selectSession(mc: MediaController) = pickController(listOf(mc))

    private fun pickController(list: List<MediaController>?) {
        val pick = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (pick?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCb)
            controller = pick
            pick?.registerCallback(controllerCb, ui)
        }
        renderMusic()
    }

    private fun renderMusic() {
        val c = controller
        val md = c?.metadata
        val has = c != null && md != null
        musicControls.visibility = if (has) View.VISIBLE else View.GONE
        musicBar.visibility = if (has) View.VISIBLE else View.INVISIBLE
        musicEmpty.visibility = if (has) View.GONE else View.VISIBLE
        musicTitle.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "Música"
        musicArtist.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ?: c?.packageName?.let { appName(it) } ?: ""
        val art: Bitmap? = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (art != null) musicArt.setImageBitmap(art)
        else musicArt.setImageDrawable(c?.packageName?.let { try { packageManager.getApplicationIcon(it) } catch (e: Throwable) { null } } ?: MediaIcon(MediaIcon.NOTE))
        musicPlay.setImageDrawable(MediaIcon(if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) MediaIcon.PAUSE else MediaIcon.PLAY))
        renderMusicProgress()
    }

    private fun renderMusicProgress() {
        val c = controller ?: return
        val st = c.playbackState ?: return
        val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (dur <= 0) { musicBar.set(0, TEXT); return }
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0)
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        musicBar.set((pos * 100 / dur).toInt(), TEXT)
    }

    // ═════════════════════════ dock y apps ═════════════════════════
    private class Slot(val label: String, val candidates: List<String>)

    private val SLOTS = listOf(
        Slot("Navegación", listOf("com.waze", "com.google.android.apps.maps", "com.btet.automap")),
        Slot("Música", listOf("com.spoti.plus", "com.spotify.music", "com.byd.mediacenter", "app.revanced.android.apps.youtube.music")),
        Slot("Radio", listOf("com.byd.mediacenter/com.byd.mediacenter.radio.view.RadioActivity", "radiotime.player")),
        Slot("Teléfono", listOf("com.byd.bluetoothcall")),
        Slot("Cámaras", listOf("com.byd.avc", "com.byd.auto_camera", "com.byd.cdr")),
    )

    internal fun resolve(c: String): ComponentName? {
        if ('/' in c) return ComponentName.unflattenFromString(c)?.takeIf {
            try { packageManager.getActivityInfo(it, 0); true } catch (e: PackageManager.NameNotFoundException) { false }
        }
        return packageManager.getLaunchIntentForPackage(c)?.component
    }

    private fun slotTarget(i: Int): ComponentName? =
        prefs.getString("slot$i", null)?.let { ComponentName.unflattenFromString(it) }?.takeIf { resolve(it.flattenToString()) != null }
            ?: SLOTS[i].candidates.firstNotNullOfOrNull { resolve(it) }

    private fun renderDock() {
        dock.removeAllViews()
        SLOTS.indices.forEach { i ->
            val cn = slotTarget(i)
            dock.addView(dockItem(cn?.let { icon(it) }, if (cn != null) appLabel(cn) else SLOTS[i].label,
                onClick = { if (cn == null) pickApp(i) else launch(cn) }, onLong = { pickApp(i) }), LinearLayout.LayoutParams(0, -1, 1f))
        }
        dock.addView(dockItem(DotsIcon(TEXT), "Apps", onClick = { showTab("apps") }, onLong = {}), LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun dockItem(ic: Drawable?, label: String, onClick: () -> Unit, onLong: () -> Unit): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true
            setOnClickListener { onClick() }; setOnLongClickListener { onLong(); true }
        }
        // Sin la app instalada: un hueco con "+" (tocar o mantener para elegir otra).
        val placeholder = GradientDrawable().apply { setColor(0); cornerRadius = dp(10).toFloat(); setStroke(dp(2), MUTED) }
        v.addView(ImageView(this).apply { setImageDrawable(ic ?: placeholder) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        v.addView(label(label, 11f, TEXT).apply { maxLines = 1; gravity = Gravity.CENTER; setPadding(dp(2), dp(3), dp(2), 0) })
        return v
    }

    internal fun launch(cn: ComponentName) {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (e: Throwable) {
            try { packageManager.getLaunchIntentForPackage(cn.packageName)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            catch (e2: Throwable) { toast("No se pudo abrir") }
        }
    }

    internal fun icon(cn: ComponentName): Drawable? = try { packageManager.getActivityIcon(cn) } catch (e: Throwable) { null }
    private fun appLabel(cn: ComponentName): String =
        try { packageManager.getActivityInfo(cn, 0).loadLabel(packageManager).toString() } catch (e: Throwable) { cn.packageName }
    internal fun appName(pkg: String): String =
        try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Throwable) { pkg }

    private fun allApps() = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) to it.loadLabel(packageManager).toString() }
        .sortedBy { it.second.lowercase() }

    /** Rejilla de apps (pestaña Apps o, con [onPick], para elegir la app de un hueco del dock). */
    private fun appsGrid(cols: Int, width: Int, onPick: ((ComponentName) -> Unit)?): View {
        val g = GridLayout(this).apply { columnCount = cols }
        val cellW = width / cols
        allApps().forEach { (cn, name) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(12), dp(4), dp(12)); isClickable = true
                setOnClickListener { if (onPick != null) onPick(cn) else launch(cn) }
            }
            cell.addView(ImageView(this).apply { setImageDrawable(icon(cn)) }, LinearLayout.LayoutParams(dp(56), dp(56)))
            cell.addView(label(name, 13f, TEXT).apply { gravity = Gravity.CENTER; maxLines = 2; setPadding(0, dp(6), 0, 0) })
            g.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED)).apply { this.width = cellW })
        }
        return ScrollView(this).apply { addView(g) }
    }

    private fun pickApp(slot: Int) {
        closeOverlay()
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xF00B0F14.toInt()); setPadding(dp(28), dp(16), dp(28), dp(16)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("Elige la app para «${SLOTS[slot].label}»", 22f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
        if (prefs.contains("slot$slot")) head.addView(pill("Por defecto") { prefs.edit().remove("slot$slot").apply(); closeOverlay(); renderDock() })
        head.addView(pill("Cerrar") { closeOverlay() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        panel.addView(head)
        val w = resources.displayMetrics.widthPixels - dp(56)
        panel.addView(appsGrid(if (portrait) 4 else 8, w) { cn ->
            prefs.edit().putString("slot$slot", cn.flattenToString()).apply(); closeOverlay(); renderDock()
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        openOverlay(panel)
    }

    /** Pone [panel] encima de todo (lo quita [closeOverlay] o el botón atrás). */
    internal fun openOverlay(panel: View) {
        closeOverlay()
        val frame = window.decorView.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout
        overlay = FrameLayout(this).apply { addView(panel, FrameLayout.LayoutParams(-1, -1)); isClickable = true }
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    }

    internal fun closeOverlay() {
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }; overlay = null
        onOverlayClosed?.invoke(); onOverlayClosed = null
    }

    // ═════════════════════════ pestañas ═════════════════════════
    private fun tabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(6)); background = round(CARD, 28)
        }
        listOf("inicio" to "Inicio", "clima" to "Clima", "energia" to "Energía", "coche" to "Coche", "camara" to "Cámara", "mapa" to "Mapa", "musica" to "Música", "apps" to "Apps", "ajustes" to "Ajustes").forEach { (k, name) ->
            val t = label(name, 15f, MUTED, bold = true).apply {
                gravity = Gravity.CENTER; setPadding(dp(13), 0, dp(13), 0); isClickable = true; setOnClickListener { showTab(k) }
            }
            tabs[k] = t; bar.addView(t, LinearLayout.LayoutParams(-2, -1))
        }
        return bar
    }

    private fun showTab(k: String) {
        tab = k
        tabs.forEach { (key, v) -> val on = key == k; v.setTextColor(if (on) Color.WHITE else MUTED); v.background = if (on) round(ACCENT_DIM, 22) else null }
        refreshTab = null
        leaveTab?.invoke(); leaveTab = null
        tabView?.let { content.removeView(it) }
        val v = when (k) { "clima" -> carTab(); "energia" -> energyPage(); "coche" -> infoPage(); "camara" -> cameraPage(); "mapa" -> mapPage(); "musica" -> mediaPage()
            "apps" -> appsTab(); "ajustes" -> settingsTab(); else -> homeTab() }
        tabView = v
        content.addView(v, FrameLayout.LayoutParams(-1, -1))
        refreshTab?.invoke()
    }

    // ── Inicio: reloj, avisos, tarjeta de carga / viaje y tres datos fijos ──
    private fun homeTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(8), 0) }
        col.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"; textSize = 76f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        val datePattern = android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEEdMMMM")
        col.addView(TextClock(this).apply { format24Hour = datePattern; format12Hour = datePattern; textSize = 20f; setTextColor(MUTED) })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(HorizontalScroll(chips), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        val ctx = card()
        col.addView(ctx, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val title = label("", 15f, MUTED, bold = true); val big = label("", 40f, Color.WHITE, bold = true); val sub = label("", 16f, TEXT)
        ctx.addView(title); ctx.addView(big); ctx.addView(sub)
        val bar = Bar(ACCENT); ctx.addView(bar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(12) })
        // Tres datos fijos debajo: consumo medio, energía que queda y kilómetros totales.
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        fun stat(k: String, last: Boolean = false): TextView {
            val c = card(); val v = label("--", 24f, Color.WHITE, bold = true)
            c.addView(label(k, 14f, MUTED)); c.addView(v)
            stats.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { if (!last) rightMargin = dp(12) })
            return v
        }
        val sCons = stat("Consumo medio"); val sKwh = stat("Energía en la batería"); val sOdo = stat("Kilómetros", last = true)
        refreshTab = {
            val s = StateHub.state
            chips.removeAllViews()
            warnings(s).forEach { (t, c) -> chips.addView(chip(t, c), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }) }
            sCons.text = s?.consumptionKwh100?.let { "${fmt1(it)} kWh/100" } ?: "--"
            sKwh.text = s?.energyLeftKwh?.let { "${fmt1(it)} kWh" } ?: "--"
            sOdo.text = s?.odometer?.let { String.format(java.util.Locale.getDefault(), "%,d km", it) } ?: "--"
            when {
                s == null -> { title.text = "BYDLINK"; big.text = "Esperando al coche…"; sub.text = ""; bar.visibility = View.GONE }
                s.charging -> {
                    title.text = "CARGANDO"
                    big.text = (s.chargeKw?.let { "${fmt1(it)} kW" } ?: s.chargingPower?.let { "$it kW" } ?: "${s.soc ?: "--"} %")
                    sub.text = listOfNotNull(
                        s.soc?.let { soc -> "$soc %" + (s.chargeLimit?.takeIf { it < 100 }?.let { " → $it %" } ?: "") },
                        s.chargedKwh?.let { "+${fmt1(it)} kWh" },
                        s.chargeRestMin?.takeIf { it in 1..2880 }?.let { "lleno en ${dur(it)}" }).joinToString("  ·  ")
                    bar.visibility = View.VISIBLE; bar.set(s.soc ?: 0, ACCENT)
                }
                s.speed > 0 || s.gear == "D" || s.gear == "R" -> {
                    title.text = "CONDUCIENDO"; big.text = "${s.speed} km/h"
                    sub.text = listOfNotNull(s.rangeKm?.let { "$it km de autonomía" }, s.power?.let { "$it kW" }).joinToString("  ·  ")
                    bar.visibility = View.GONE
                }
                else -> {
                    title.text = "ÚLTIMO VIAJE"
                    big.text = s.lastTripKwh100?.let { "${fmt1(it)} kWh/100 km" } ?: "Aparcado"
                    sub.text = listOfNotNull(s.rangeKm?.let { "$it km de autonomía" },
                        s.parkBrake?.let { if (it) "freno de mano echado" else "sin freno de mano" }).joinToString("  ·  ")
                    bar.visibility = View.GONE
                }
            }
        }
        return ScrollView(this).apply { addView(col) }
    }

    private fun warnings(s: CarState?): List<Pair<String, Int>> {
        if (s == null) return emptyList()
        val w = ArrayList<Pair<String, Int>>()
        val d = s.doors
        val open = listOfNotNull(d.fl.takeIf { it == true }?.let { "del. izq." }, d.fr.takeIf { it == true }?.let { "del. der." },
            d.rl.takeIf { it == true }?.let { "tras. izq." }, d.rr.takeIf { it == true }?.let { "tras. der." })
        if (open.isNotEmpty()) w += "Puerta abierta (${open.joinToString(", ")})" to AMBER
        if (d.trunk == true) w += "Maletero abierto" to AMBER
        val ty = s.tyres
        if (listOfNotNull(ty.fl, ty.fr, ty.rl, ty.rr).any { it < 2.0 }) w += "Presión de ruedas baja" to AMBER
        s.aux12v?.let { if (it < 12.0) w += "Batería 12 V baja (${fmt1(it)} V)" to AMBER }
        s.outTemp?.let { if ((if (s.tempUnit == "F") it <= 37 else it <= 3)) w += "Posible hielo ($it°)" to AMBER }
        when (s.locked) { true -> w += "Cerrado" to ACCENT; false -> w += "Abierto" to TEXT; null -> {} }
        s.climate?.on?.let { if (it) w += "Clima a ${s.climate.temp ?: "--"}°" to ACCENT }
        if (!StateHub.sdkOk) w += "Coche dormido · datos de hace ${dur(((System.currentTimeMillis() - StateHub.readAt) / 60000).toInt())}" to MUTED
        return w
    }

    // ── Coche: mandos según lo que tiene este coche; en marcha solo el clima ──
    private fun carTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), dp(8)) }
        // Clima
        val clima = card(); col.addView(clima)
        val cHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val cState = label("Clima", 18f, Color.WHITE, bold = true); cHead.addView(cState, LinearLayout.LayoutParams(0, -2, 1f))
        val onBtn = pill("Encender") {}; cHead.addView(onBtn)
        clima.addView(cHead)
        val tRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val minus = pill("−") {}; val tTxt = label("--°", 34f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
        val plus = pill("+") {}
        tRow.addView(minus, LinearLayout.LayoutParams(dp(72), dp(56))); tRow.addView(tTxt, LinearLayout.LayoutParams(dp(120), -2)); tRow.addView(plus, LinearLayout.LayoutParams(dp(72), dp(56)))
        clima.addView(tRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        clima.addView(label("Ventilador", 14f, MUTED).apply { setPadding(0, dp(12), 0, dp(6)) })
        val fanRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fanBtns = (0..7).map { n -> pill(if (n == 0) "Auto" else "$n") { climate(if (n == 0) mapOf("auto" to true) else mapOf("fan" to n, "auto" to false)) }
            .also { fanRow.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4) }) } }
        clima.addView(fanRow)
        clima.addView(label("Salida del aire y desempañado", 14f, MUTED).apply { setPadding(0, dp(12), 0, dp(6)) })
        val ventRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // Salida: cara=1, pies=2, parabrisas=4 (se combinan) → modo 1..7 del coche.
        val modeOf = mapOf(1 to 1, 3 to 2, 2 to 3, 6 to 4, 4 to 5, 7 to 6, 5 to 7)
        val bitsOf = modeOf.entries.associate { (b, m) -> m to b }
        val vents = listOf(4 to "Parabrisas", 1 to "Cara", 2 to "Pies").map { (bit, name) ->
            pill(name) {
                val cur = bitsOf[StateHub.state?.climate?.mode] ?: 0
                val next = cur xor bit
                if (next == 0) toast("El aire tiene que salir por algún sitio") else climate(mapOf("mode" to modeOf.getValue(next)))
            }.also { ventRow.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) }) } to bit
        }
        val dfF = pill("Desemp. delante") { climate(mapOf("defrostFront" to (StateHub.state?.climate?.defrostFront != true))) }
        val dfR = pill("Desemp. detrás") { climate(mapOf("defrostRear" to (StateHub.state?.climate?.defrostRear != true))) }
        ventRow.addView(dfF, LinearLayout.LayoutParams(0, dp(48), 1.3f).apply { rightMargin = dp(6) }); ventRow.addView(dfR, LinearLayout.LayoutParams(0, dp(48), 1.3f))
        clima.addView(ventRow)
        onBtn.setOnClickListener { climate(mapOf("on" to (StateHub.state?.climate?.on != true))) }
        minus.setOnClickListener { StateHub.state?.climate?.temp?.let { climate(mapOf("temp" to (it - 1).coerceIn(17, 33))) } }
        plus.setOnClickListener { StateHub.state?.climate?.temp?.let { climate(mapOf("temp" to (it + 1).coerceIn(17, 33))) } }

        val moving = label("En marcha: solo lo imprescindible.", 14f, AMBER).apply { setPadding(dp(4), dp(10), 0, 0) }
        col.addView(moving)

        refreshTab = {
            val s = StateHub.state; val c = s?.climate
            val run = s != null && (s.speed > 0 || s.gear == "D" || s.gear == "R")
            cState.text = if (c?.on == true) "Clima encendido" else "Clima apagado"
            onBtn.text = if (c?.on == true) "Apagar" else "Encender"
            tTxt.text = c?.temp?.let { "$it°" } ?: "--°"
            fanBtns.forEachIndexed { n, b -> active(b, if (n == 0) c?.auto == true else c?.auto != true && c?.fan == n) }
            val bits = bitsOf[c?.mode] ?: 0
            vents.forEach { (b, bit) -> active(b, bits and bit != 0) }
            active(dfF, c?.defrostFront == true); active(dfR, c?.defrostRear == true)
            moving.visibility = if (run) View.VISIBLE else View.GONE
            clima.visibility = if (caps["climate"] == false) View.GONE else View.VISIBLE
        }
        return ScrollView(this).apply { addView(col) }
    }

    internal fun climate(p: Map<String, Any?>) = send("setClimate", p, null)

    /** Manda una orden al coche por el mismo camino que el móvil (CarCommands, con sus comprobaciones de seguridad). */
    internal fun send(action: String, payload: Map<String, Any?>, okMsg: String?) {
        val cmd = StateHub.commander ?: return toast("BYDLink aún está arrancando")
        Thread {
            val msg = try {
                val r = cmd(action, payload)
                when { r?.get("applied") == false -> (r["error"] as? String) ?: "El coche no confirma el cambio"; else -> okMsg }
            } catch (e: Throwable) { e.message ?: "No se pudo" }
            if (msg != null) ui.post { toast(msg) }
        }.start()
    }

    // ── Apps ──
    private fun appsTab(): View = appsGrid(if (portrait) 4 else 6, (content.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(372))), null)

    // ── Ajustes ──
    private fun settingsTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), dp(8)) }
        val home = card(); col.addView(home)
        home.addView(label("Pantalla de inicio", 18f, Color.WHITE, bold = true))
        val homeTxt = label("", 14f, MUTED).apply { setPadding(0, dp(6), 0, dp(10)) }; home.addView(homeTxt)
        val homeBtn = pill("") {}; home.addView(homeBtn, LinearLayout.LayoutParams(-2, dp(52)))
        fun homeRefresh() {
            val on = LauncherMode.isOn(this)
            homeTxt.text = if (on) "BYDLink es la pantalla de inicio: la casita te trae aquí." else "Ahora la casita lleva a tu lanzador de antes. Esto es una vista previa."
            homeBtn.text = if (on) "Volver al lanzador de antes" else "Usar BYDLink como pantalla de inicio"
        }
        homeRefresh()
        homeBtn.setOnClickListener { homeBtn.isEnabled = false; Thread { val m = LauncherMode.set(applicationContext, !LauncherMode.isOn(this)); ui.post { homeBtn.isEnabled = true; homeRefresh(); toast(m) } }.start() }
        val openBtn = pill("") {}
        fun openRefresh() { openBtn.text = if (OpenOnStart.enabled(this)) "✓ Abrir BYDLink al arrancar el coche" else "Abrir BYDLink al arrancar el coche: no" }
        openRefresh()
        openBtn.setOnClickListener { OpenOnStart.set(this, !OpenOnStart.enabled(this)); openRefresh() }
        home.addView(openBtn, LinearLayout.LayoutParams(-2, dp(52)).apply { topMargin = dp(10) })

        val keys = card(); col.addView(keys, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        keys.addView(label("Botones del volante", 18f, Color.WHITE, bold = true))
        keys.addView(label("Asigna a un botón (pulsación corta, larga o doble) abrir una página, guardar un vídeo, la foto, el centinela o el aire.", 14f, MUTED)
            .apply { setPadding(0, dp(6), 0, dp(10)) })
        keys.addView(pill("Configurar botones") { keysPanel() }, LinearLayout.LayoutParams(-2, dp(52)))

        val st = card(); col.addView(st, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        st.addView(label("Estado de BYDLink", 18f, Color.WHITE, bold = true))
        val rows = LinkedHashMap<String, TextView>()
        listOf("Lectura del coche", "Nube (móvil)", "Música", "Versión").forEach { rows[it] = kv(st, it) }
        val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        links.addView(pill("Vincular móvil y diagnóstico") { startActivity(Intent(this, MainActivity::class.java)) }, LinearLayout.LayoutParams(-2, dp(52)))
        links.addView(pill("Permisos") { startActivity(Intent(this, PermissionsActivity::class.java)) }, LinearLayout.LayoutParams(-2, dp(52)).apply { leftMargin = dp(10) })
        links.addView(pill("Ajustes del coche") { if (!SystemScreens.systemSettings(this)) toast("No se pudo abrir") }, LinearLayout.LayoutParams(-2, dp(52)).apply { leftMargin = dp(10) })
        st.addView(links)
        refreshTab = {
            val age = (System.currentTimeMillis() - StateHub.readAt) / 1000
            rows["Lectura del coche"]?.text = when { StateHub.state == null -> "Esperando…"; !StateHub.sdkOk -> "Dormido (hace ${dur((age / 60).toInt())})"; else -> "Al día" }
            rows["Nube (móvil)"]?.text = if (StateHub.cloudOk) "Conectada" else "Sin conexión"
            rows["Música"]?.text = if (currentSessions() != null) "Con permiso" else "Sin permiso (se activa solo con ADB)"
            rows["Versión"]?.text = BuildConfigCompat.appVersion(this)
        }
        return ScrollView(this).apply { addView(col) }
    }

    // ═════════════════════════ piezas ═════════════════════════
    internal fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14))
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(20).toFloat(); setStroke(1, LINE) }
        isClickable = true
    }

    internal fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    internal fun pill(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), dp(10))
        background = round(CARD2, 14); isClickable = true; setOnClickListener { onClick() }
    }

    internal fun active(v: TextView, on: Boolean) { v.background = round(if (on) ACCENT_DIM else CARD2, 14); v.setTextColor(if (on) ACCENT else Color.WHITE) }

    internal fun chip(t: String, color: Int) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(color); setPadding(dp(14), dp(9), dp(14), dp(9)); background = round(CARD, 16); maxLines = 1
    }

    @Suppress("FunctionName")
    internal fun HorizontalScroll(child: View) = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(child) }

    internal fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    internal fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    internal fun fmt1(v: Double) = String.format(java.util.Locale.getDefault(), "%.1f", v)
    internal fun dur(min: Int) = when { min < 60 -> "$min min"; min < 1440 -> "${min / 60} h ${min % 60} min"; else -> "${min / 1440} d" }

    /** Barra de progreso fina con esquinas redondas. */
    internal inner class Bar(private var color: Int) : View(this@HomeActivity) {
        private var pct = 0
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0x33FFFFFF }
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG)
        fun set(p: Int, c: Int) { pct = p.coerceIn(0, 100); color = c; invalidate() }
        override fun onDraw(c: Canvas) {
            val r = height / 2f
            c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, bg)
            fg.color = color
            if (pct > 0) c.drawRoundRect(RectF(0f, 0f, width * pct / 100f, height.toFloat()), r, r, fg)
        }
    }

    /** Velocidad en un anillo (0–200 km/h). */
    private inner class SpeedRing : View(this@HomeActivity) {
        var speed = 0
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x22FFFFFF; strokeCap = Paint.Cap.ROUND }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT; strokeCap = Paint.Cap.ROUND }
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        private val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textAlign = Paint.Align.CENTER }
        override fun onDraw(c: Canvas) {
            val w = dp(8).toFloat(); track.strokeWidth = w; arc.strokeWidth = w
            val r = RectF(w, w, width - w, height - w)
            c.drawArc(r, 135f, 270f, false, track)
            c.drawArc(r, 135f, 270f * (speed.coerceIn(0, 200) / 200f), false, arc)
            num.textSize = height * 0.34f; unit.textSize = height * 0.11f
            c.drawText("$speed", width / 2f, height * 0.56f, num)
            c.drawText("km/h", width / 2f, height * 0.74f, unit)
        }
    }

    /** Iconos de música dibujados (sin depender de fuentes del sistema). */
    internal class MediaIcon(private val kind: Int) : Drawable() {
        companion object { const val PREV = 0; const val PLAY = 1; const val PAUSE = 2; const val NEXT = 3; const val NOTE = 4 }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        override fun draw(c: Canvas) {
            val b = bounds; val s = minOf(b.width(), b.height()) * 0.8f
            val cx = b.exactCenterX(); val cy = b.exactCenterY(); val h = s / 2
            val path = Path()
            when (kind) {
                PLAY -> { path.moveTo(cx - h * .6f, cy - h); path.lineTo(cx + h * .9f, cy); path.lineTo(cx - h * .6f, cy + h); path.close() }
                PAUSE -> { c.drawRect(cx - h * .7f, cy - h, cx - h * .2f, cy + h, p); c.drawRect(cx + h * .2f, cy - h, cx + h * .7f, cy + h, p) }
                PREV -> { path.moveTo(cx + h * .7f, cy - h * .8f); path.lineTo(cx - h * .3f, cy); path.lineTo(cx + h * .7f, cy + h * .8f); path.close()
                          c.drawRect(cx - h * .7f, cy - h * .8f, cx - h * .4f, cy + h * .8f, p) }
                NEXT -> { path.moveTo(cx - h * .7f, cy - h * .8f); path.lineTo(cx + h * .3f, cy); path.lineTo(cx - h * .7f, cy + h * .8f); path.close()
                          c.drawRect(cx + h * .4f, cy - h * .8f, cx + h * .7f, cy + h * .8f, p) }
                else -> { p.color = MUTED; c.drawCircle(cx - h * .3f, cy + h * .5f, h * .35f, p); c.drawRect(cx, cy - h, cx + h * .15f, cy + h * .5f, p) }
            }
            if (!path.isEmpty) c.drawPath(path, p)
        }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Icono de "todas las apps": rejilla de 3×3 puntos. */
    private class DotsIcon(color: Int) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        override fun draw(c: Canvas) {
            val b = bounds; val step = b.width() / 3f; val r = step * 0.16f
            for (i in 0..2) for (j in 0..2) c.drawCircle(b.left + step * (i + 0.5f), b.top + step * (j + 0.5f), r, p)
        }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    internal companion object {
        val CARD = 0xE6151C26.toInt()
        val CARD2 = 0xFF1C2532.toInt()
        val LINE = 0x22FFFFFF
        val TEXT = 0xFFC9D1D9.toInt()
        val MUTED = 0xFF8B96A1.toInt()
        val ACCENT = 0xFF2FD27A.toInt()
        val ACCENT_DIM = 0x332FD27A
        val AMBER = 0xFFF5B740.toInt()
        val RED = 0xFFFF5A5F.toInt()
    }
}
