package com.bydlink.carapp.ui

import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bydlink.carapp.keys.WheelKeys
import com.bydlink.carapp.ui.HomeActivity.Companion.INK
import com.bydlink.carapp.ui.HomeActivity.Companion.ACCENT
import com.bydlink.carapp.ui.HomeActivity.Companion.AMBER
import com.bydlink.carapp.ui.HomeActivity.Companion.MUTED
import com.bydlink.carapp.ui.HomeActivity.Companion.TEXT
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Botones del volante en el lanzador: activar, "Aprender botón" (enseña todo lo que llega al pulsar: teclas de Android,
 * eventos del SDK y, por ADB, lo que manda el hardware) y asignar a cada botón visto una acción por tipo de pulsación.
 */
internal fun HomeActivity.keysPanel() {
    WheelKeys.start(applicationContext)
    val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xF00B0F14.toInt()); setPadding(dp(28), dp(16), dp(28), dp(16)) }
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(label("Botones del volante", 22f, INK, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
    val onBtn = pill("") { WheelKeys.setEnabled(!WheelKeys.enabled) }
    head.addView(onBtn, LinearLayout.LayoutParams(-2, dp(50)))
    head.addView(pill("Cerrar") { closeOverlay() }, LinearLayout.LayoutParams(-2, dp(50)).apply { leftMargin = dp(10) })
    panel.addView(head)

    val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    panel.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })

    // ── izquierda: estado y aprender ──
    val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    body.addView(left, LinearLayout.LayoutParams(0, -1, 0.95f).apply { rightMargin = dp(12) })
    val st = card(); left.addView(st)
    val stTxt = label("", 14f, TEXT); st.addView(stTxt)
    val learnBtn = pill("") { WheelKeys.learn(!WheelKeys.learning) }
    st.addView(learnBtn, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(12) })
    val learnHint = label("", 13f, MUTED).apply { setPadding(0, dp(8), 0, 0) }; st.addView(learnHint)

    val logCard = card(); left.addView(logCard, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
    logCard.addView(label("Lo último que ha llegado", 16f, INK, bold = true))
    val logBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    logCard.addView(ScrollView(this).apply { addView(logBox) }, LinearLayout.LayoutParams(-1, -1).apply { topMargin = dp(8) })
    val hhmmss = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    fun renderLog() {
        logBox.removeAllViews()
        val items = WheelKeys.logList().take(14)
        if (items.isEmpty()) logBox.addView(label("Nada todavía. Pulsa «Aprender botón» y luego los botones del volante, uno a uno.", 14f, MUTED))
        items.forEach { e ->
            val src = when (e["src"]) { "android" -> "tecla"; "sdk", "sdk-voz" -> "SDK"; "getevent" -> "hardware"; "accion" -> "acción"; else -> e["src"] }
            logBox.addView(label("${hhmmss.format(Date((e["ts"] as? Long) ?: 0L))}  ${e["name"]}  ·  $src  ·  ${e["info"]}", 13f,
                if (e["src"] == "accion") ACCENT else TEXT).apply { setPadding(0, dp(3), 0, dp(3)) })
        }
    }

    // ── derecha: botones vistos y su acción ──
    val rightCard = card()
    body.addView(rightCard, LinearLayout.LayoutParams(0, -1, 1.25f))
    rightCard.addView(label("Botones vistos", 16f, INK, bold = true))
    val keysBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    rightCard.addView(ScrollView(this).apply { addView(keysBox) }, LinearLayout.LayoutParams(-1, -1).apply { topMargin = dp(8) })
    var shownIds = ""
    fun renderKeys() {
        val keys = WheelKeys.learnedList().sortedByDescending { (it["last"] as? Long) ?: 0L }
        shownIds = keys.joinToString { it["id"].toString() } + WheelKeys.bindings.hashCode()
        keysBox.removeAllViews()
        if (keys.isEmpty()) keysBox.addView(label("Cuando pulses un botón que BYDLink pueda usar, saldrá aquí para asignarle una acción.", 14f, MUTED))
        keys.forEach { k ->
            val id = k["id"].toString()
            val b = WheelKeys.bindings[id]
            keysBox.addView(label(k["name"].toString(), 16f, INK, bold = true).apply { setPadding(0, dp(10), 0, 0) })
            keysBox.addView(label("$id · ${(k["count"] as? Number)?.toInt() ?: 0} veces", 12f, MUTED))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            // Del SDK no se sabe cuándo se suelta el botón: no hay pulsación larga.
            val presses = if (id.startsWith("k")) listOf(WheelKeys.SHORT, WheelKeys.LONG, WheelKeys.DOUBLE) else listOf(WheelKeys.SHORT, WheelKeys.DOUBLE)
            presses.forEach { press ->
                val a = b?.get(press)
                val name = when (press) { WheelKeys.LONG -> "Larga"; WheelKeys.DOUBLE -> "Doble"; else -> "Corta" }
                val p = pill("$name: ${a?.let { WheelKeys.label(it) } ?: "lo normal"}") { pickKeyAction(id, k["name"].toString(), press) }
                p.textSize = 13f; active(p, a != null)
                row.addView(p, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
            }
            keysBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
    }

    fun refresh() {
        val on = WheelKeys.enabled
        onBtn.text = if (on) "Activados" else "Desactivados"; active(onBtn, on)
        val s = WheelKeys.status()
        stTxt.text = "Servicio de teclas: " + (if (WheelKeys.serviceOn) "activo" else if (on || WheelKeys.learning) "activándose…" else "apagado") +
            "\nEventos del coche (SDK): " + when (s["sdk"]) { "ok" -> "escuchando"; "no" -> "—"; else -> s["sdk"] } +
            (s["adb"]?.let { "\nADB del coche: $it" } ?: "")
        val learning = WheelKeys.learning
        learnBtn.text = if (learning) "Dejar de aprender" else "Aprender botón"; active(learnBtn, learning)
        learnHint.text = if (learning) "Pulsa los botones del volante uno a uno (corto y largo). Lo que llegue sale abajo; " +
            "lo del hardware tarda unos segundos." else "Los botones siguen haciendo lo de siempre mientras aprendes."
        learnHint.setTextColor(if (learning) AMBER else MUTED)
        val ids = WheelKeys.learnedList().sortedByDescending { (it["last"] as? Long) ?: 0L }.joinToString { it["id"].toString() } + WheelKeys.bindings.hashCode()
        if (ids != shownIds) renderKeys()
    }

    renderLog(); renderKeys(); refresh()
    WheelKeys.onEvent = { renderLog(); refresh() }
    val loop = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 1000) } }
    ui.postDelayed(loop, 1000)
    openOverlay(panel)
    onOverlayClosed = { WheelKeys.onEvent = null; ui.removeCallbacks(loop) }
}

/** Elegir qué hace [id] con una pulsación [press]. Al terminar se vuelve a la página de botones. */
private fun HomeActivity.pickKeyAction(id: String, name: String, press: String) {
    val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xF00B0F14.toInt()); setPadding(dp(28), dp(16), dp(28), dp(16)) }
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val pressName = when (press) { WheelKeys.LONG -> "pulsación larga"; WheelKeys.DOUBLE -> "doble pulsación"; else -> "pulsación corta" }
    head.addView(label("«$name», $pressName", 22f, INK, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(pill("Volver") { keysPanel() }, LinearLayout.LayoutParams(-2, dp(50)))
    panel.addView(head)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    panel.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
    val cur = WheelKeys.bindings[id]?.get(press)
    fun choose(a: String?) {
        try { WheelKeys.setBinding(id, press, a); toast(if (a == null) "Ese botón vuelve a lo normal" else "Listo: ${WheelKeys.label(a)}") }
        catch (e: Throwable) { toast(e.message ?: "No se pudo") }
        keysPanel()
    }
    fun grid(items: List<Pair<String?, String>>) {
        var row: LinearLayout? = null
        items.forEachIndexed { i, (a, t) ->
            if (i % 3 == 0) row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { list.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
            val p = pill(t) { choose(a) }
            active(p, a == cur || (a == null && cur == null))
            row!!.addView(p, LinearLayout.LayoutParams(0, dp(58), 1f).apply { rightMargin = dp(8) })
        }
        val rest = items.size % 3
        if (rest != 0) repeat(3 - rest) { row!!.addView(TrText(this), LinearLayout.LayoutParams(0, dp(58), 1f).apply { rightMargin = dp(8) }) }
    }
    grid(listOf<Pair<String?, String>>(null to "Lo normal (no tocar)") + WheelKeys.ACTIONS.map { it.key to it.value })
    list.addView(label("Abrir una app", 16f, INK, bold = true).apply { setPadding(0, dp(18), 0, 0) })
    grid(WheelKeys.apps().map { "app:${it["pkg"]}" to (it["label"] ?: "") })
    openOverlay(panel)
}
