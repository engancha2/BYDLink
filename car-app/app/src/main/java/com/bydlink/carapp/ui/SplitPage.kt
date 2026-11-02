package com.bydlink.carapp.ui

import android.content.ComponentName
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bydlink.carapp.ui.HomeActivity.Companion.INK
import com.bydlink.carapp.ui.HomeActivity.Companion.ACCENT
import com.bydlink.carapp.ui.HomeActivity.Companion.CARD2
import com.bydlink.carapp.ui.HomeActivity.Companion.MUTED

/**
 * Dividir (boceto "Pantalla partida"): elegir la app de cada lado y el tamaño, y abrirlas a la vez. En DiLink 3.0
 * (Android 10) lo hace Android con el ADB del coche; las apps no se pueden meter dentro del lanzador.
 */
internal fun HomeActivity.splitPage(): View {
    val col = page()
    pageHead(col, "Pantalla partida")
    fun pick(key: String, def: ComponentName?) = prefs.getString(key, null)?.let { ComponentName.unflattenFromString(it) }
        ?.takeIf { resolve(it.flattenToString()) != null } ?: def
    var left = pick("splitL", navApp())
    var right = pick("splitR", musicApp())
    var ratio = prefs.getInt("splitRatio", 50)
    var doRender: () -> Unit = {}

    // ── controles: izquierda, derecha, tamaño ──
    val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
    col.addView(ctl)
    fun chooser(title: String): Pair<LinearLayout, LinearLayout> {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(label(title, 15f, MUTED).apply { setPadding(0, 0, 0, dp(6)) })
        val b = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = round(CARD2, 13); setPadding(dp(14), 0, dp(14), 0); isClickable = true }
        c.addView(b, LinearLayout.LayoutParams(dp(230), dp(54)))
        ctl.addView(c, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(14) })
        return c to b
    }
    val (_, lBtn) = chooser("Izquierda")
    val (_, rBtn) = chooser("Derecha")
    ctl.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    val ratios = listOf(70, 50, 30).associateWith { r ->
        pill("$r / ${100 - r}") { ratio = r; prefs.edit().putInt("splitRatio", r).apply(); doRender() }.apply { textSize = 17f }
            .also { ctl.addView(it, LinearLayout.LayoutParams(dp(98), dp(54)).apply { leftMargin = dp(8) }) }
    }
    val swap = ImageView(this).apply { setImageDrawable(LineIcon("swap", INK)); background = round(CARD2, 13); setPadding(dp(15), dp(15), dp(15), dp(15)); isClickable = true }
    ctl.addView(swap, LinearLayout.LayoutParams(dp(54), dp(54)).apply { leftMargin = dp(14) })

    // ── vista previa con las proporciones ──
    val prev = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    col.addView(prev, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })
    fun side(): Pair<LinearLayout, Triple<ImageView, TextView, TextView>> {
        val p = card().apply { setPadding(0, 0, 0, 0) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val hName = label("", 16f, INK); head.addView(hName, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(iconView("expand", MUTED), LinearLayout.LayoutParams(dp(18), dp(18)))
        p.addView(head)
        p.addView(View(this).apply { setBackgroundColor(HomeActivity.LINE) }, LinearLayout.LayoutParams(-1, 1))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val ic = ImageView(this); body.addView(ic, LinearLayout.LayoutParams(dp(64), dp(64)))
        val name = label("", 19f, INK).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(12), dp(10), 0) }; body.addView(name)
        body.addView(label("Se abrirá en esta parte de la pantalla", 14f, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(6), dp(10), 0) })
        p.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        return p to Triple(ic, name, hName)
    }
    val (lp, lv) = side(); val (rp, rv) = side()
    prev.addView(lp); prev.addView(rp)

    val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    col.addView(foot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    foot.addView(label("Android pone una app a cada lado. Si tu coche no deja elegir el tamaño, se quedan a medias.", 14f, MUTED), LinearLayout.LayoutParams(0, -2, 1f))
    val go = iconPill("split", "Abrir pantalla partida") {
        val l = left ?: return@iconPill toast("Elige la app de la izquierda")
        Thread { val m = SplitScreen.open(applicationContext, l, right, ratio); ui.post { toast(m) } }.start()
    }.apply { background = round(ACCENT, 13); (getChildAt(1) as TextView).setTextColor(HomeActivity.ON_ACCENT); (getChildAt(0) as ImageView).setImageDrawable(LineIcon("split", HomeActivity.ON_ACCENT)) }
    foot.addView(go, LinearLayout.LayoutParams(dp(290), dp(56)))

    fun fill(b: LinearLayout, cn: ComponentName?) {
        b.removeAllViews()
        b.addView(ImageView(this).apply { setImageDrawable(cn?.let { icon(it) } ?: LineIcon("plus", MUTED)) }, LinearLayout.LayoutParams(dp(30), dp(30)))
        b.addView(label(cn?.let { appLabel(it) } ?: "Elegir app", 16f, INK).apply { setPadding(dp(12), 0, 0, 0); maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
        b.addView(iconView("right", MUTED), LinearLayout.LayoutParams(dp(16), dp(16)))
    }
    fun render() {
        fill(lBtn, left); fill(rBtn, right)
        ratios.forEach { (r, v) -> active(v, r == ratio) }
        lp.layoutParams = LinearLayout.LayoutParams(0, -1, ratio.toFloat()).apply { rightMargin = dp(14) }
        rp.layoutParams = LinearLayout.LayoutParams(0, -1, (100 - ratio).toFloat())
        listOf(left to lv, right to rv).forEach { (cn, v) ->
            v.first.setImageDrawable(cn?.let { icon(it) } ?: LineIcon("plus", MUTED))
            v.second.text = cn?.let { appLabel(it) } ?: "Sin elegir"
            v.third.text = cn?.let { appLabel(it) } ?: "—"
        }
        prev.requestLayout()
    }
    doRender = { render() }
    fun choose(key: String, set: (ComponentName) -> Unit) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Skins.cur.sheet); setPadding(dp(28), dp(16), dp(28), dp(16)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("Elige la app", 22f, INK), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(pill("Cerrar") { closeOverlay() })
        panel.addView(head)
        panel.addView(appsGrid(8, resources.displayMetrics.widthPixels - dp(56)) { cn ->
            prefs.edit().putString(key, cn.flattenToString()).apply(); set(cn); closeOverlay(); render()
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        openOverlay(panel)
    }
    lBtn.setOnClickListener { choose("splitL") { left = it } }
    rBtn.setOnClickListener { choose("splitR") { right = it } }
    swap.setOnClickListener {
        val t = left; left = right; right = t
        prefs.edit().putString("splitL", left?.flattenToString()).putString("splitR", right?.flattenToString()).apply(); render()
    }
    render()
    return col
}
