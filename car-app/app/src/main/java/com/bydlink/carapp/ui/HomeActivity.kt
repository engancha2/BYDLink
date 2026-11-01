package com.bydlink.carapp.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Pantalla de inicio de BYDLink (opcional, ver LauncherMode), según el boceto del usuario:
 *  - barra de arriba fina: BYDLink, estado del coche (marcha), red, nube, batería y hora;
 *  - el contenido de la pestaña (con sus sub-pestañas);
 *  - barra de abajo fija: clima a mano (temperatura y ventilador, si el coche tiene clima) y las pestañas
 *    Inicio, Coche, Batería, Mapa, Música, Cámara, Dividir y Menú (en Menú: Clima, Ajustes, apps, Android Auto…).
 * No se mete en las apps: navegación, música, etc. se abren tal cual.
 */
class HomeActivity : Activity() {

    internal val ui = Handler(Looper.getMainLooper())
    internal val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    internal var portrait = false

    // Barra de arriba
    private lateinit var stateChip: TextView
    private lateinit var netIcon: ImageView
    private lateinit var cloudIcon: ImageView
    private lateinit var battTop: TextView
    private lateinit var battIcon: ImageView
    // Barra de abajo
    private lateinit var climaBox: View
    private lateinit var tempTxt: TextView
    private lateinit var fanTxt: TextView
    private val tabs = LinkedHashMap<String, Pair<LinearLayout, ImageView>>()
    // Contenido
    private lateinit var content: FrameLayout
    internal var tab = "inicio"
    /** Sub-pestaña elegida de cada pestaña (se recuerda al volver). */
    internal val subOf = HashMap<String, String>()
    private var overlay: FrameLayout? = null
    internal var refreshTab: (() -> Unit)? = null
    /** Lo que hay que soltar al salir de la página (p. ej. la cámara en directo). */
    internal var leaveTab: (() -> Unit)? = null
    internal var onOverlayClosed: (() -> Unit)? = null
    /** Lo llama la música al cambiar (canción, pausa…) para que la página lo pinte. */
    internal var onMusic: (() -> Unit)? = null
    internal var caps: Map<String, Boolean> = emptyMap()

    private val tick = object : Runnable {
        override fun run() { renderTop(); renderDock(); refreshTab?.invoke(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // El servicio vive aunque no se abra la app (al ser la pantalla de inicio, arranca con el coche).
        try { ContextCompat.startForegroundService(this, Intent(this, MainService::class.java)) } catch (e: Throwable) {}
        portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        build()
        // Qué tiene este coche (multimodelo). En su hilo: pregunta al SDK.
        if (BuildConfigCompat.USE_BYD_SDK) Thread {
            try { caps = Capabilities.local(applicationContext); ui.post { renderDock() } } catch (e: Throwable) {}
        }.start()
    }

    override fun onResume() {
        super.onResume(); startMusic(); ui.post(tick)
        resumeTab?.let { if (it == "camara" && tab == "camara") showTab(it) }; resumeTab = null
    }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick); stopMusic(); leaveTab?.invoke(); leaveTab = null; resumeTab = tab }
    /** Página a rehacer al volver (si soltó algo al salir, como la cámara). */
    private var resumeTab: String? = null
    // Casita: al inicio. Los botones del volante pueden pedir otra página (extra "tab", y "sub" para la sub-pestaña).
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); closeOverlay(); showTab(intent?.getStringExtra("tab") ?: "inicio", intent?.getStringExtra("sub")) }
    override fun onBackPressed() { if (overlay != null) closeOverlay() else if (tab != "inicio") showTab("inicio") }

    // ═════════════════════════ estructura ═════════════════════════
    private fun build() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.addView(topBar(), LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, 1))
        content = FrameLayout(this).apply { setPadding(dp(if (portrait) 14 else 24), dp(14), dp(if (portrait) 14 else 24), dp(12)) }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, 1))
        root.addView(dock(), LinearLayout.LayoutParams(-1, dp(if (portrait) 150 else 88)))
        val frame = FrameLayout(this).apply { addView(root, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(frame)
        showTab(intent?.getStringExtra("tab") ?: "inicio", intent?.getStringExtra("sub"))
    }

    // ── barra de arriba ──
    private fun topBar(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(26), 0, dp(26), 0) }
        bar.addView(ImageView(this).apply { setImageResource(com.bydlink.carapp.R.drawable.ic_bydlink_mark); setOnClickListener { showTab("inicio") } },
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(12) })
        bar.addView(label("BYDLINK", 17f, Color.WHITE, bold = true).apply { letterSpacing = 0.22f; setOnClickListener { showTab("inicio") } })
        stateChip = badge("…")
        bar.addView(stateChip, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(20) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        netIcon = ImageView(this); bar.addView(netIcon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(14) })
        cloudIcon = ImageView(this); bar.addView(cloudIcon, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(18) })
        battTop = label("--%", 17f, Color.WHITE, bold = true); bar.addView(battTop)
        battIcon = ImageView(this).apply { setImageDrawable(LineIcon("battery", Color.WHITE)) }
        bar.addView(battIcon, LinearLayout.LayoutParams(dp(24), dp(24)).apply { leftMargin = dp(6); rightMargin = dp(22) })
        bar.addView(TextClock(this).apply { format24Hour = "HH:mm"; format12Hour = "HH:mm"; textSize = 18f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
        return bar
    }

    /** "P · Aparcado", "D · 52 km/h", "Cargando 7,2 kW"… */
    internal fun stateText(s: CarState?): String = when {
        s == null -> "Esperando al coche…"
        s.charging -> "Cargando" + (s.chargeKw?.let { " · ${fmt1(it)} kW" } ?: "")
        s.speed > 0 || s.gear == "D" || s.gear == "R" -> "${s.gear} · ${s.speed} km/h"
        !StateHub.sdkOk -> "Coche apagado"
        else -> "${s.gear} · Aparcado"
    }

    private fun renderTop() {
        val s = StateHub.state
        stateChip.text = stateText(s)
        val soc = s?.soc
        battTop.text = soc?.let { "$it%" } ?: "--%"
        battIcon.setImageDrawable(LineIcon("battery", when { soc == null -> MUTED; soc <= 15 -> RED; soc <= 30 -> AMBER; else -> Color.WHITE }))
        val cm = getSystemService(ConnectivityManager::class.java)
        val nc = try { cm?.getNetworkCapabilities(cm.activeNetwork) } catch (e: Throwable) { null }
        netIcon.setImageDrawable(when {
            nc == null -> LineIcon("signal", 0x55FFFFFF)
            nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LineIcon("wifi", Color.WHITE)
            else -> LineIcon("signal", Color.WHITE)
        })
        cloudIcon.setImageDrawable(LineIcon("cloud", if (StateHub.cloudOk) ACCENT else 0x55FFFFFF))
    }

    // ── barra de abajo: clima a mano + pestañas ──
    private val TABS = listOf("inicio" to ("Inicio" to "home"), "coche" to ("Coche" to "car"), "bateria" to ("Batería" to "battery"),
        "mapa" to ("Mapa" to "map"), "musica" to ("Música" to "music"), "camara" to ("Cámara" to "camera"),
        "dividir" to ("Dividir" to "split"), "menu" to ("Menú" to "grid"))

    private fun dock(): View {
        val bar = LinearLayout(this).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(DOCK); setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val clima = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun stepper(icon: String, onIcon: () -> Unit, minus: () -> Unit, plus: () -> Unit): TextView {
            val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = round(CARD2, 14) }
            box.addView(ImageView(this).apply { setImageDrawable(LineIcon(icon, MUTED)); setPadding(dp(14), dp(18), dp(6), dp(18)); isClickable = true; setOnClickListener { onIcon() } },
                LinearLayout.LayoutParams(dp(40), -1))
            box.addView(iconBtn("minus", minus), LinearLayout.LayoutParams(dp(42), -1))
            val v = label("--", 22f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; isClickable = true; setOnClickListener { onIcon() } }
            box.addView(v, LinearLayout.LayoutParams(dp(54), -1))
            box.addView(iconBtn("plus", plus), LinearLayout.LayoutParams(dp(42), -1))
            clima.addView(box, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = dp(10) })
            return v
        }
        tempTxt = stepper("thermo", { showTab("clima") },
            { StateHub.state?.climate?.temp?.let { climate(mapOf("temp" to (it - 1).coerceIn(17, 33))) } },
            { StateHub.state?.climate?.temp?.let { climate(mapOf("temp" to (it + 1).coerceIn(17, 33))) } })
        fanTxt = stepper("fan", { showTab("clima") },
            { StateHub.state?.climate?.fan?.let { climate(mapOf("fan" to (it - 1).coerceIn(1, 7), "auto" to false)) } },
            { climate(mapOf("fan" to ((StateHub.state?.climate?.fan ?: 0) + 1).coerceIn(1, 7), "auto" to false)) })
        climaBox = clima
        bar.addView(clima, if (portrait) LinearLayout.LayoutParams(-2, dp(56)).apply { bottomMargin = dp(8) } else LinearLayout.LayoutParams(-2, -1).apply { rightMargin = dp(2) })

        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        TABS.forEach { (k, v) ->
            val (name, ic) = v
            val icon = ImageView(this).apply { setImageDrawable(LineIcon(ic, TAB_TXT)) }
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = round(TAB_BG, 14); isClickable = true
                setOnClickListener { showTab(k) }
                addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
                addView(label(name, 14f, TAB_TXT).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(4), 0, 0) })
            }
            tabs[k] = t to icon
            tabRow.addView(t, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(8) })
        }
        bar.addView(tabRow, if (portrait) LinearLayout.LayoutParams(-1, dp(66)) else LinearLayout.LayoutParams(0, -1, 1f))
        return bar
    }

    private fun renderDock() {
        val c = StateHub.state?.climate
        climaBox.visibility = if (caps["climate"] == false) View.GONE else View.VISIBLE
        tempTxt.text = c?.temp?.let { "$it°" } ?: "--"
        tempTxt.setTextColor(if (c?.on == true) Color.WHITE else MUTED)
        fanTxt.text = when { c == null -> "--"; c.auto == true -> "A"; else -> "${c.fan ?: 0}" }
        fanTxt.setTextColor(if (c?.on == true) Color.WHITE else MUTED)
    }

    private fun iconBtn(kind: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageDrawable(LineIcon(kind, Color.WHITE)); setPadding(dp(11), dp(18), dp(11), dp(18)); isClickable = true; setOnClickListener { onClick() }
    }

    // ═════════════════════════ pestañas ═════════════════════════
    /** Abre una página. Nombres viejos (botones del volante): energia → bateria. */
    internal fun showTab(key: String, sub: String? = null) {
        val k = when (key) { "energia" -> "bateria"; "info" -> "coche"; else -> key }
        if (sub != null) subOf[k] = sub
        tab = k
        val lit = if (k in tabs) k else "menu"
        tabs.forEach { (key2, v) ->
            val on = key2 == lit
            v.first.background = round(if (on) ACCENT else TAB_BG, 14)
            (v.first.getChildAt(1) as TextView).setTextColor(if (on) ON_ACCENT else TAB_TXT)
            v.second.setImageDrawable(LineIcon(TABS.first { it.first == key2 }.second.second, if (on) ON_ACCENT else TAB_TXT))
        }
        refreshTab = null; onMusic = null
        leaveTab?.invoke(); leaveTab = null
        content.removeAllViews()
        val v = when (k) {
            "coche" -> carPage(); "bateria" -> batteryPage(); "mapa" -> mapPage(); "musica" -> mediaPage(); "camara" -> cameraPage()
            "dividir" -> splitPage(); "menu" -> menuPage(); "clima" -> climatePage(); "ajustes" -> settingsPage(); "apps" -> appsPage()
            else -> homePage()
        }
        content.addView(v, FrameLayout.LayoutParams(-1, -1))
        refreshTab?.invoke()
    }

    /** Cabecera de página como el boceto: título y, si hay, sub-pestañas. Devuelve la sub-pestaña elegida. */
    internal fun pageHead(parent: LinearLayout, title: String, subs: List<Pair<String, String>> = emptyList()): String {
        val cur = subOf[tab]?.takeIf { s -> subs.any { it.first == s } } ?: subs.firstOrNull()?.first ?: ""
        parent.addView(label(title, 26f, Color.WHITE).apply { setPadding(0, 0, 0, dp(10)) })
        if (subs.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            subs.forEach { (k, name) ->
                row.addView(pill(name) { showTab(tab, k) }.apply { textSize = 15f; setPadding(dp(18), 0, dp(18), 0); active(this, k == cur) },
                    LinearLayout.LayoutParams(-2, dp(46)).apply { rightMargin = dp(8) })
            }
            parent.addView(HorizontalScroll(row), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        return cur
    }

    /** Columna de página con cabecera. */
    internal fun page(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    // ═════════════════════════ música ═════════════════════════
    private var msm: MediaSessionManager? = null
    internal var controller: MediaController? = null
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> ui.post { pickController(list) } }
    private val controllerCb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { onMusic?.invoke() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { onMusic?.invoke() }
        override fun onSessionDestroyed() { ui.post { pickController(currentSessions()) } }
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
        onMusic?.invoke()
    }

    internal fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    // ═════════════════════════ apps ═════════════════════════
    internal class Slot(val label: String, val candidates: List<String>)

    internal val SLOTS = listOf(
        Slot("Navegación", listOf("com.waze", "com.google.android.apps.maps", "com.btet.automap")),
        Slot("Música", listOf("com.spoti.plus", "com.spotify.music", "com.byd.mediacenter", "app.revanced.android.apps.youtube.music")),
        Slot("Radio", listOf("com.byd.mediacenter/com.byd.mediacenter.radio.view.RadioActivity", "radiotime.player")),
        Slot("Teléfono", listOf("com.byd.bluetoothcall")),
        Slot("Cámaras", listOf("com.byd.avc", "com.byd.auto_camera", "com.byd.cdr")),
        Slot("Ajustes del coche", listOf("com.byd.carsettings")),
    )

    internal fun resolve(c: String): ComponentName? {
        if ('/' in c) return ComponentName.unflattenFromString(c)?.takeIf {
            try { packageManager.getActivityInfo(it, 0); true } catch (e: PackageManager.NameNotFoundException) { false }
        }
        return packageManager.getLaunchIntentForPackage(c)?.component
    }

    internal fun slotTarget(i: Int): ComponentName? =
        prefs.getString("slot$i", null)?.let { ComponentName.unflattenFromString(it) }?.takeIf { resolve(it.flattenToString()) != null }
            ?: SLOTS[i].candidates.firstNotNullOfOrNull { resolve(it) }

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
    internal fun appLabel(cn: ComponentName): String =
        try { packageManager.getActivityInfo(cn, 0).loadLabel(packageManager).toString() } catch (e: Throwable) { cn.packageName }
    internal fun appName(pkg: String): String =
        try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Throwable) { pkg }

    internal fun allApps() = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .filter { it.activityInfo.packageName != packageName }
        .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) to it.loadLabel(packageManager).toString() }
        .sortedBy { it.second.lowercase() }

    /** Rejilla de apps (página Apps o, con [onPick], para elegir la app de un acceso). */
    internal fun appsGrid(cols: Int, width: Int, onPick: ((ComponentName) -> Unit)?): View {
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

    /** Elegir la app de un acceso (pulsación larga en Menú → Tus aplicaciones). */
    internal fun pickApp(slot: Int, then: () -> Unit) {
        closeOverlay()
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xF20B1016.toInt()); setPadding(dp(28), dp(16), dp(28), dp(16)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("Elige la app para «${SLOTS[slot].label}»", 22f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
        if (prefs.contains("slot$slot")) head.addView(pill("Por defecto") { prefs.edit().remove("slot$slot").apply(); closeOverlay(); then() })
        head.addView(pill("Cerrar") { closeOverlay() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        panel.addView(head)
        val w = resources.displayMetrics.widthPixels - dp(56)
        panel.addView(appsGrid(if (portrait) 4 else 8, w) { cn ->
            prefs.edit().putString("slot$slot", cn.flattenToString()).apply(); closeOverlay(); then()
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

    // ═════════════════════════ mandos ═════════════════════════
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

    /** Cualquier orden de BYDLink (también las del servicio: centinela…), como si llegara del móvil. */
    internal fun run(action: String, payload: Map<String, Any?>, done: (Boolean, Map<String, Any?>) -> Unit) {
        val r = StateHub.run ?: return toast("BYDLink aún está arrancando")
        r(action, payload) { ok, data -> ui.post { done(ok, data) } }
    }

    internal fun moving(s: CarState? = StateHub.state) = s != null && (s.speed > 0 || s.gear == "D" || s.gear == "R")

    internal fun warnings(s: CarState?): List<Pair<String, Int>> {
        if (s == null) return emptyList()
        val w = ArrayList<Pair<String, Int>>()
        val d = s.doors
        val open = listOfNotNull(d.fl.takeIf { it == true }?.let { "del. izq." }, d.fr.takeIf { it == true }?.let { "del. dcha." },
            d.rl.takeIf { it == true }?.let { "tras. izq." }, d.rr.takeIf { it == true }?.let { "tras. dcha." })
        if (open.isNotEmpty()) w += "Puerta abierta (${open.joinToString(", ")})" to AMBER
        if (d.trunk == true) w += "Maletero abierto" to AMBER
        if (d.hood == true) w += "Capó abierto" to AMBER
        val ty = s.tyres
        if (listOfNotNull(ty.fl, ty.fr, ty.rl, ty.rr).any { it < 2.0 }) w += "Presión de ruedas baja" to AMBER
        s.aux12v?.let { if (it < 12.0) w += "Batería 12 V baja (${fmt1(it)} V)" to AMBER }
        s.outTemp?.let { if ((if (s.tempUnit == "F") it <= 37 else it <= 3)) w += "Posible hielo ($it°)" to AMBER }
        return w
    }

    // ═════════════════════════ piezas ═════════════════════════
    internal fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16))
        background = round(CARD, 18)
        isClickable = true
    }

    internal fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** Título de un panel ("Estado de carga", "Consumo"…). */
    internal fun h3(t: String) = label(t, 17f, Color.WHITE, bold = true).apply { setPadding(0, 0, 0, dp(8)) }

    internal fun pill(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), dp(10))
        background = round(CARD2, 13); isClickable = true; setOnClickListener { onClick() }
    }

    /** Botón con icono a la izquierda (como "Ir a casa" del boceto). */
    internal fun iconPill(icon: String, t: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; background = round(CARD2, 13); isClickable = true
        setPadding(dp(14), 0, dp(14), 0); setOnClickListener { onClick() }
        addView(ImageView(context).apply { setImageDrawable(LineIcon(icon, Color.WHITE)) }, LinearLayout.LayoutParams(dp(20), dp(20)))
        addView(label(t, 16f, Color.WHITE).apply { setPadding(dp(10), 0, 0, 0); maxLines = 1 })
    }

    internal fun active(v: TextView, on: Boolean) { v.background = round(if (on) ACCENT else CARD2, 13); v.setTextColor(if (on) ON_ACCENT else Color.WHITE) }

    internal fun chip(t: String, color: Int) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(color); setPadding(dp(12), dp(7), dp(12), dp(7)); background = round(CARD2, 10); maxLines = 1
    }

    /** Etiqueta verde del boceto ("P · Aparcado"). */
    internal fun badge(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(BADGE_TXT); setPadding(dp(12), dp(7), dp(12), dp(7)); background = round(BADGE_BG, 9); maxLines = 1
    }

    /** Fila "clave … valor" con raya debajo, como las listas del boceto. */
    internal fun kv(parent: LinearLayout, k: String): TextView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
        row.addView(label(k, 16f, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        val v = label("--", 16f, Color.WHITE).apply { gravity = Gravity.END }; row.addView(v)
        parent.addView(row)
        parent.addView(View(this).apply { setBackgroundColor(LINE2) }, LinearLayout.LayoutParams(-1, 1))
        return v
    }

    /** Fila de aviso ámbar (puerta abierta…) con icono, título y detalle. */
    internal fun alertRow(icon: String, title: String, detail: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = round(WARN_BG, 13); setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(ImageView(context).apply { setImageDrawable(LineIcon(icon, AMBER)) }, LinearLayout.LayoutParams(dp(20), dp(20)))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        col.addView(label(title, 16f, AMBER)); col.addView(label(detail, 15f, AMBER).apply { alpha = .85f; setPadding(0, dp(4), 0, 0) })
        addView(col)
    }

    internal fun iconView(kind: String, color: Int) = ImageView(this).apply { setImageDrawable(LineIcon(kind, color)) }

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
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = METER_BG }
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG)
        fun set(p: Int, c: Int) { pct = p.coerceIn(0, 100); color = c; invalidate() }
        override fun onDraw(c: Canvas) {
            val r = height / 2f
            c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, bg)
            fg.color = color
            if (pct > 0) c.drawRoundRect(RectF(0f, 0f, width * pct / 100f, height.toFloat()), r, r, fg)
        }
    }

    /** Iconos de música dibujados (sin depender de fuentes del sistema). */
    internal class MediaIcon(private val kind: Int, color: Int = Color.WHITE) : Drawable() {
        companion object { const val PREV = 0; const val PLAY = 1; const val PAUSE = 2; const val NEXT = 3; const val NOTE = 4 }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
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

    internal companion object {
        // Colores del boceto (byd-launcher-demo.html): azul marino, paneles, acento verde agua.
        val BG = 0xFF0B1016.toInt()
        val DOCK = 0xFF101820.toInt()
        val CARD = 0xFF151E28.toInt()
        val CARD2 = 0xFF202C38.toInt()
        val TAB_BG = 0xFF1A2531.toInt()
        val TAB_TXT = 0xFFB4C2CF.toInt()
        val LINE = 0xFF24303C.toInt()
        val LINE2 = 0xFF27323D.toInt()
        val METER_BG = 0xFF2C3C47.toInt()
        val TEXT = 0xFFD3DDE7.toInt()
        val MUTED = 0xFF93A5B6.toInt()
        val ACCENT = 0xFF74E1CE.toInt()
        val ON_ACCENT = 0xFF082D28.toInt()
        val ACCENT_DIM = 0xFF203A39.toInt()
        val BADGE_BG = 0xFF1D3835.toInt()
        val BADGE_TXT = 0xFFA4F0DE.toInt()
        val AMBER = 0xFFF2C063.toInt()
        val WARN_BG = 0xFF3A3123.toInt()
        val RED = 0xFFFF6764.toInt()
    }
}
