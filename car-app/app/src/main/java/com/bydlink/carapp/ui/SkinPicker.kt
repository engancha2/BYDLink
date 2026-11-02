package com.bydlink.carapp.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.bydlink.carapp.ui.HomeActivity.Companion.ACCENT
import com.bydlink.carapp.ui.HomeActivity.Companion.INK
import com.bydlink.carapp.ui.HomeActivity.Companion.MUTED

/**
 * Hoja de skins: Clásicas, Flúor y Medianoche (negro con el neón que se elija). Al tocar una, la pantalla cambia
 * con un círculo que se abre desde el dedo y la hoja sigue abierta para probar otra.
 */
@SuppressLint("ClickableViewAccessibility")
internal fun HomeActivity.openSkins() {
    val p = Skins.cur
    val dm = resources.displayMetrics
    var tx = dm.widthPixels / 2f; var ty = dm.heightPixels / 2f
    val touch = View.OnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) { tx = e.rawX; ty = e.rawY }; false }

    val panel = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()); isClickable = true; setOnClickListener { closeOverlay() } }
    val sheetW = minOf(dm.widthPixels - dp(40), dp(1120))
    val sheet = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; isClickable = true
        background = GradientDrawable().apply { setColor(p.sheet); cornerRadius = dp(28).toFloat(); setStroke(maxOf(1, dp(1)), p.glassLine) }
        setPadding(dp(24), dp(18), dp(24), dp(20))
    }
    panel.addView(sheet, FrameLayout.LayoutParams(sheetW, -2, Gravity.CENTER))

    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(label("Skins", 26f, INK), LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(pill("Cerrar") { closeOverlay() })
    sheet.addView(head)

    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    sheet.addView(ScrollView(this).apply { addView(body); isVerticalScrollBarEnabled = false },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    val cols = if (portrait) 3 else 6
    val gap = dp(10)
    val cellW = (sheetW - dp(48) - gap * cols) / cols
    val prevH = dp(if (portrait) 70 else 64)

    fun skinCard(s: Palette, onPick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(10)); isClickable = true
        background = GradientDrawable().apply { setColor(p.chip); cornerRadius = dp(18).toFloat(); if (s.id == p.id) setStroke(dp(2), ACCENT) }
        setOnTouchListener(touch); setOnClickListener { onPick() }
        contentDescription = "Skin ${s.name}"
        addView(View(context).apply { background = Skins.preview(s, cellW - dp(16), prevH, dp(12).toFloat()) }, LinearLayout.LayoutParams(-1, prevH))
        addView(label(s.name, 16f, INK, bold = true).apply { setPadding(dp(4), dp(8), 0, 0); maxLines = 1 })
        addView(label(s.note, 12f, MUTED).apply { setPadding(dp(4), dp(2), 0, 0); maxLines = 1 })
    }
    fun groupTitle(t: String) = body.addView(label(t, 15f, MUTED).apply { setPadding(dp(4), dp(12), 0, dp(8)) })

    val all = Skins.all()
    Skins.GROUPS.forEach { (title, ids) ->
        groupTitle(title)
        val grid = GridLayout(this).apply { columnCount = cols }
        ids.mapNotNull { id -> all.firstOrNull { it.id == id } }.forEach { s ->
            grid.addView(skinCard(s) { Skins.select(this@openSkins, s.id); reskin(tx, ty, true) },
                GridLayout.LayoutParams().apply { width = cellW; rightMargin = gap; bottomMargin = gap })
        }
        body.addView(grid)
    }

    // Medianoche: su tarjeta y los neones al lado.
    groupTitle("Medianoche")
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val mid = all.first { it.id == "medianoche" }
    row.addView(skinCard(mid) { Skins.select(this@openSkins, "medianoche"); reskin(tx, ty, true) }, LinearLayout.LayoutParams(cellW, -2).apply { rightMargin = gap })
    val pick = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12))
        background = GradientDrawable().apply { setColor(p.chip); cornerRadius = dp(18).toFloat() }
    }
    pick.addView(label("Color neón", 18f, INK, bold = true))
    pick.addView(label("Negro profundo y toda la pantalla en el neón que elijas", 14f, MUTED).apply { setPadding(0, dp(2), 0, dp(10)) })
    val neons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    Skins.NEONS.forEach { (id, n) ->
        val chosen = Skins.skin == "medianoche" && Skins.neon == id
        neons.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(n[1])); if (chosen) setStroke(dp(3), INK) }
            isClickable = true; contentDescription = "Neón ${n[0]}"
            setOnTouchListener(touch); setOnClickListener { Skins.select(this@openSkins, "medianoche", id); reskin(tx, ty, true) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
    }
    pick.addView(HorizontalScroll(neons))
    row.addView(pick, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = gap; bottomMargin = gap })
    body.addView(row)

    // Automática día / noche.
    val autoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(14), 0, 0) }
    val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    txt.addView(label("Automática día / noche", 18f, INK, bold = true))
    txt.addView(label("Porcelana de día y Océano de noche, según el modo noche del coche", 14f, MUTED).apply { setPadding(0, dp(2), 0, 0) })
    autoRow.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
    val tog = pill(if (Skins.auto) "Activada" else "Desactivada") {}.apply { active(this, Skins.auto); setOnTouchListener(touch) }
    tog.setOnClickListener { Skins.setAuto(this@openSkins, !Skins.auto); reskin(tx, ty, true) }
    autoRow.addView(tog, LinearLayout.LayoutParams(-2, dp(50)))
    body.addView(autoRow)

    openOverlay(panel)
}
