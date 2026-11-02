package com.bydlink.carapp.ui

import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.service.StateHub
import com.bydlink.carapp.ui.HomeActivity.Companion.INK
import com.bydlink.carapp.ui.HomeActivity.Companion.MUTED

/** Ajustes del lanzador: pantalla de inicio, abrir al arrancar, botones del volante y estado de BYDLink. */
internal fun HomeActivity.settingsPage(): View {
    val col = page()
    pageHead(col, "Ajustes")
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    col.addView(ScrollView(this).apply { addView(row) }, LinearLayout.LayoutParams(-1, 0, 1f))
    val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    row.addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(16) })

    // Idioma de la pantalla: los 13, cada uno con su nombre; se cambia al momento con el mismo círculo que las skins.
    val langCard = card(); left.addView(langCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    langCard.addView(h3("Idioma"))
    val cols = 4
    val colW = (resources.displayMetrics.widthPixels - dp(if (portrait) 60 else 110)) / (if (portrait) 1 else 2)
    val cellW = (colW - dp(40) - dp(8) * cols) / cols
    val langGrid = android.widget.GridLayout(this).apply { columnCount = cols }
    var lx = 0f; var ly = 0f
    I18n.LANGS.forEach { (code, name) ->
        val on = code == I18n.lang
        val b = android.widget.TextView(this).apply {   // sin traducir: cada idioma con su propio nombre
            text = name; textSize = 16f; gravity = android.view.Gravity.CENTER; maxLines = 1
            setTextColor(if (on) HomeActivity.ON_ACCENT else INK); background = round(if (on) HomeActivity.ACCENT else HomeActivity.CARD2, 13)
            isClickable = true; contentDescription = name
            setOnTouchListener { _, e -> if (e.action == android.view.MotionEvent.ACTION_DOWN) { lx = e.rawX; ly = e.rawY }; false }
            setOnClickListener { if (code != I18n.lang) { I18n.set(this@settingsPage, code); reskin(lx, ly, false) } }
        }
        langGrid.addView(b, android.widget.GridLayout.LayoutParams().apply { width = cellW; height = dp(50); setMargins(0, 0, dp(8), dp(8)) })
    }
    langCard.addView(langGrid)

    val home = card(); left.addView(home)
    home.addView(h3("Pantalla de inicio"))
    val homeTxt = label("", 15f, MUTED).apply { setPadding(0, 0, 0, dp(12)) }; home.addView(homeTxt)
    val homeBtn = pill("") {}; home.addView(homeBtn, LinearLayout.LayoutParams(-1, dp(54)))
    fun homeRefresh() {
        val on = LauncherMode.isOn(this)
        homeTxt.text = if (on) "BYDLink es la pantalla de inicio: la casita te trae aquí." else "Ahora la casita lleva a tu lanzador de antes. Esto es una vista previa."
        homeBtn.text = if (on) "Volver al lanzador de antes" else "Usar BYDLink como pantalla de inicio"
    }
    homeRefresh()
    homeBtn.setOnClickListener { homeBtn.isEnabled = false; Thread { val m = LauncherMode.set(applicationContext, !LauncherMode.isOn(this)); ui.post { homeBtn.isEnabled = true; homeRefresh(); toast(m) } }.start() }
    val openBtn = pill("") {}
    fun openRefresh() { openBtn.text = if (OpenOnStart.enabled(this)) "✓ Abrir BYDLink al arrancar el coche" else "Abrir BYDLink al arrancar el coche: no"; active(openBtn, OpenOnStart.enabled(this)) }
    openRefresh()
    openBtn.setOnClickListener { OpenOnStart.set(this, !OpenOnStart.enabled(this)); openRefresh() }
    home.addView(openBtn, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(10) })

    val keys = card(); left.addView(keys, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    keys.addView(h3("Botones del volante"))
    keys.addView(label("Asigna a un botón (pulsación corta, larga o doble) abrir una página, guardar un vídeo, la foto, el centinela o el aire.", 15f, MUTED)
        .apply { setPadding(0, 0, 0, dp(12)) })
    keys.addView(pill("Configurar botones") { keysPanel() }, LinearLayout.LayoutParams(-1, dp(54)))

    val st = card(); row.addView(st, LinearLayout.LayoutParams(0, -2, 1f))
    st.addView(h3("Estado de BYDLink"))
    val rows = LinkedHashMap<String, TextView>()
    listOf("Lectura del coche", "Nube (móvil)", "Música", "Versión").forEach { rows[it] = kv(st, it) }
    listOf("Vincular móvil y diagnóstico" to { startActivity(Intent(this, MainActivity::class.java)) },
        "Permisos y arranque" to { startActivity(Intent(this, PermissionsActivity::class.java)) },
        "Ajustes del coche" to { if (!SystemScreens.systemSettings(this)) toast("No se pudo abrir") }
    ).forEachIndexed { i, (t, go) -> st.addView(pill(t) { go() }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(if (i == 0) 16 else 10) }) }
    refreshTab = {
        val age = (System.currentTimeMillis() - StateHub.readAt) / 1000
        rows["Lectura del coche"]?.text = when { StateHub.state == null -> "Esperando…"; !StateHub.sdkOk -> "Dormido (hace ${dur((age / 60).toInt())})"; else -> "Al día" }
        rows["Nube (móvil)"]?.text = if (StateHub.cloudOk) "Conectada" else "Sin conexión"
        rows["Música"]?.text = if (currentSessions() != null) "Con permiso" else "Sin permiso (se da solo con ADB)"
        rows["Versión"]?.text = BuildConfigCompat.appVersion(this)
        rows["Nube (móvil)"]?.setTextColor(if (StateHub.cloudOk) INK else MUTED)
    }
    return col
}
