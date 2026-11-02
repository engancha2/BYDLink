package com.bydlink.carapp.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.update.Updater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pantalla «Actualizaciones»: versión instalada, estado de la actualización, novedades e historial. */
class UpdatesActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var bar: ProgressBar
    private lateinit var installBtn: Button
    private lateinit var notesTitle: TextView
    private lateinit var notes: TextView
    private lateinit var history: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var shown = ""
    private val tick = object : Runnable { override fun run() { render(); ui.postDelayed(this, 1000) } }
    private val fmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("es", "ES"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(24)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TrText(this).apply {
            text = "Actualizaciones"; textSize = 26f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Button(this).apply { text = "Volver"; isAllCaps = false; setOnClickListener { finish() } })
        root.addView(head)

        // Versión instalada + estado
        val card = card()
        card.addView(TrText(this).apply { text = "Versión instalada"; textSize = 15f; setTextColor(MUTED) })
        card.addView(TrText(this).apply {
            text = BuildConfigCompat.appVersion(this@UpdatesActivity); textSize = 44f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        })
        status = TrText(this).apply { textSize = 19f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(6), 0, 0) }
        detail = TrText(this).apply { textSize = 15f; setTextColor(TEXT); setPadding(0, dp(4), 0, 0) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        card.addView(status); card.addView(detail)
        card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(10) })

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        btns.addView(Button(this).apply {
            text = "Buscar ahora"; isAllCaps = false
            setOnClickListener {
                val u = Updater.instance
                if (u == null) Toast.makeText(this@UpdatesActivity, "El servicio de BYDLink aún está arrancando", Toast.LENGTH_SHORT).show()
                else { u.checkNow(); Toast.makeText(this@UpdatesActivity, "Buscando…", Toast.LENGTH_SHORT).show() }
            }
        })
        installBtn = Button(this).apply {
            text = "Instalar ahora"; isAllCaps = false; setTextColor(0xFF05240F.toInt()); visibility = View.GONE
            background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(10).toFloat() }
            setOnClickListener { Updater.installNow(this@UpdatesActivity) }
        }
        btns.addView(installBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
        card.addView(btns)
        root.addView(card)

        // Instalar automáticamente
        val autoCard = card(horizontal = true)
        val autoTxt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        autoTxt.addView(TrText(this).apply { text = "Instalar automáticamente"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        autoTxt.addView(TrText(this).apply {
            text = "Se instalan solas cuando el coche está parado. Si lo desactivas, se descargan y esperan a que pulses «Instalar ahora»."
            textSize = 14.5f; setTextColor(MUTED); setPadding(0, dp(3), dp(12), 0)
        })
        autoCard.addView(autoTxt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        autoCard.addView(Switch(this).apply {
            isChecked = Updater.autoInstall(this@UpdatesActivity)
            setOnCheckedChangeListener { _, on -> Updater.setAutoInstall(this@UpdatesActivity, on) }
        })
        root.addView(autoCard)

        // Novedades
        val notesCard = card()
        notesTitle = TrText(this).apply { textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) }
        notes = TrText(this).apply { textSize = 15f; setTextColor(TEXT); setPadding(0, dp(6), 0, 0); setLineSpacing(0f, 1.15f) }
        notesCard.addView(notesTitle); notesCard.addView(notes)
        root.addView(notesCard)

        // Historial
        val histCard = card()
        histCard.addView(TrText(this).apply { text = "Historial"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        history = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        histCard.addView(history)
        root.addView(histCard)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); shown = ""; ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    private fun render() {
        val s = Updater.state(this)
        val ready = Updater.readyToInstall(this)
        val hist = Updater.history(this)
        val key = "$s|$ready|${hist.size}"
        if (key == shown) return
        shown = key

        val v = s.version ?: s.latestName ?: ""
        val (title, color) = when (s.state) {
            "descargando" -> "Descargando la $v… ${s.progress.coerceAtLeast(0)} %" to AMBER
            "esperando" -> "La $v está lista: se instalará cuando el coche esté parado" to AMBER
            "instalando" -> "Instalando la $v… BYDLink se reiniciará sola" to AMBER
            "lista" -> "La $v está lista para instalar" to AMBER
            "error" -> "No se pudo actualizar a la $v" to RED
            "actualizada", "aldia" -> "Al día ✓  Tienes la última versión" to ACCENT
            else -> "Comprobando…" to MUTED
        }
        status.text = title; status.setTextColor(color)
        detail.text = listOfNotNull(
            s.msg?.takeIf { s.state == "error" || s.state == "lista" },
            if (s.checkedAt > 0) "Comprobado: ${fmt.format(Date(s.checkedAt))}" else null
        ).joinToString("\n")
        bar.visibility = if (s.state == "descargando") View.VISIBLE else View.GONE
        bar.progress = s.progress.coerceAtLeast(0)
        installBtn.visibility = if (ready != null && s.state != "instalando") View.VISIBLE else View.GONE
        installBtn.text = if (ready.isNullOrEmpty()) "Instalar ahora" else "Instalar la $ready ahora"

        notesTitle.text = if (s.latestName != null) "Novedades de la ${s.latestName}" +
            (if (s.latestTs > 0) "  ·  ${fmt.format(Date(s.latestTs))}" else "") else "Novedades"
        notes.text = s.latestNotes?.takeIf { it.isNotBlank() } ?: "Sin notas para esta versión."

        history.removeAllViews()
        if (hist.isEmpty()) history.addView(TrText(this).apply { text = "Aún no se ha actualizado ninguna vez desde aquí."; textSize = 15f; setTextColor(MUTED) })
        hist.forEach { (hv, ts) ->
            history.addView(TrText(this).apply { text = "BYDLink $hv  ·  ${fmt.format(Date(ts))}"; textSize = 15f; setTextColor(TEXT); setPadding(0, dp(4), 0, 0) })
        }
    }

    private fun card(horizontal: Boolean = false) = LinearLayout(this).apply {
        orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        gravity = if (horizontal) Gravity.CENTER_VERTICAL else Gravity.START
        setPadding(dp(20), dp(16), dp(20), dp(16))
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(16).toFloat() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val CARD = 0xFF151B23.toInt()
        val TEXT = 0xFFC9D1D9.toInt()
        val MUTED = 0xFF8B96A1.toInt()
        val ACCENT = 0xFF2FD27A.toInt()
        val AMBER = 0xFFF5B740.toInt()
        val RED = 0xFFFF6B6B.toInt()
    }
}
