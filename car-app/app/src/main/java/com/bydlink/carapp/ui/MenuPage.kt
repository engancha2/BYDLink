package com.bydlink.carapp.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bydlink.carapp.ui.HomeActivity.Companion.ACCENT
import com.bydlink.carapp.ui.HomeActivity.Companion.AMBER
import com.bydlink.carapp.ui.HomeActivity.Companion.CARD2
import com.bydlink.carapp.ui.HomeActivity.Companion.MUTED
import com.bydlink.carapp.ui.HomeActivity.Companion.ON_ACCENT
import com.bydlink.carapp.ui.HomeActivity.Companion.TEXT
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.Locale

/**
 * Menú (boceto "Apps"): funciones del vehículo, tus aplicaciones (accesos editables), Android Auto y CarPlay (lo que
 * tenga el coche; si no hay, qué hacer) e "Instalar una app" desde un USB.
 */
internal fun HomeActivity.menuPage(): View {
    val col = page()
    pageHead(col, "Menú")
    val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(ScrollView(this).apply { addView(inner); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(-1, 0, 1f))

    // ── funciones del vehículo ──
    inner.addView(label("Funciones del vehículo", 17f, Color.WHITE, bold = true).apply { setPadding(0, 0, 0, dp(10)) })
    val f = GridLayout(this).apply { columnCount = 5 }
    inner.addView(f)
    listOf(Triple("Clima", "thermo") { showTab("clima") }, Triple("Batería", "battery") { showTab("bateria") },
        Triple("Coche", "car") { showTab("coche") }, Triple("Cámara", "camera") { showTab("camara") },
        Triple("Mapa", "map") { showTab("mapa") }, Triple("Música", "music") { showTab("musica") },
        Triple("Dividir", "split") { showTab("dividir") }, Triple("Botones del volante", "wheel") { keysPanel() },
        Triple("Ajustes", "gear") { showTab("ajustes") }, Triple("Todas las apps", "apps") { showTab("apps") }
    ).forEach { (name, ic, go) -> f.addView(tile(ic, name, go), cell()) }

    // ── tus aplicaciones ──
    inner.addView(label("Tus aplicaciones · mantén pulsado para cambiar", 17f, Color.WHITE, bold = true).apply { setPadding(0, dp(18), 0, dp(10)) })
    val apps = GridLayout(this).apply { columnCount = 3 }
    inner.addView(apps)
    fun renderApps() {
        apps.removeAllViews()
        SLOTS.indices.forEach { i ->
            val cn = slotTarget(i)
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = round(CARD2, 14); isClickable = true
                setOnClickListener { if (cn == null) pickApp(i) { showTab("menu") } else launch(cn) }
                setOnLongClickListener { pickApp(i) { showTab("menu") }; true }
            }
            t.addView(ImageView(this).apply { setImageDrawable(cn?.let { icon(it) } ?: LineIcon("plus", MUTED)) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            t.addView(label(cn?.let { appLabel(it) } ?: SLOTS[i].label, 17f, Color.WHITE).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(dp(6), dp(8), dp(6), 0) })
            apps.addView(t, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; height = dp(100); setMargins(0, 0, dp(12), dp(12)) })
        }
    }
    renderApps()

    // ── Android Auto y CarPlay ──
    inner.addView(label("Android Auto y CarPlay", 17f, Color.WHITE, bold = true).apply { setPadding(0, dp(18), 0, dp(10)) })
    val proj = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    inner.addView(proj)
    val aa = card(); proj.addView(aa, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(16) })
    val cp = card(); proj.addView(cp, LinearLayout.LayoutParams(0, -2, 1f))
    aa.addView(label("Buscando…", 15f, MUTED)); cp.addView(label("Buscando…", 15f, MUTED))
    Thread {
        val (aaList, cpList) = try { Projection.scan(applicationContext) } catch (e: Throwable) { emptyList<Projection.Found>() to emptyList() }
        ui.post { if (tab == "menu") { projCard(aa, "Android Auto", "smartphone", aaList, true); projCard(cp, "CarPlay", "smartphone", cpList, false) } }
    }.start()

    // ── instalar una app ──
    inner.addView(label("Instalar una app", 17f, Color.WHITE, bold = true).apply { setPadding(0, dp(18), 0, dp(10)) })
    val inst = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    inner.addView(inst, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    inst.addView(iconView("usb", ACCENT), LinearLayout.LayoutParams(dp(30), dp(30)))
    inst.addView(label("Copia el archivo .apk en un USB (o en Descargas) y mételo en el coche. BYDLink lo instala con el ADB del coche: DiLink no deja hacerlo desde su gestor de archivos.",
        15f, TEXT).apply { setPadding(dp(16), 0, dp(16), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
    inst.addView(iconPill("download", "Buscar .apk") { apkPanel() }, LinearLayout.LayoutParams(dp(200), dp(54)))
    return col
}

private fun HomeActivity.cell() = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
    width = 0; height = dp(92); setMargins(0, 0, dp(12), dp(12))
}

private fun HomeActivity.tile(ic: String, name: String, go: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = round(CARD2, 14); isClickable = true; setOnClickListener { go() }
    addView(ImageView(context).apply { setImageDrawable(LineIcon(ic, Color.WHITE)) }, LinearLayout.LayoutParams(dp(26), dp(26)))
    addView(label(name, 16f, Color.WHITE).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(dp(4), dp(8), dp(4), 0) })
}

/** Tarjeta de Android Auto o CarPlay según lo que haya en el coche. */
private fun HomeActivity.projCard(c: LinearLayout, name: String, ic: String, found: List<Projection.Found>, aa: Boolean) {
    c.removeAllViews()
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(iconView(ic, Color.WHITE), LinearLayout.LayoutParams(dp(22), dp(22)))
    head.addView(label(name, 19f, Color.WHITE, bold = true).apply { setPadding(dp(10), 0, 0, 0) })
    c.addView(head)
    val ready = found.filter { it.state == Projection.State.READY }
    val hidden = found.filter { it.state == Projection.State.HIDDEN && it.system }
    when {
        ready.isNotEmpty() -> ready.take(2).forEach { f ->
            c.addView(iconPill("smartphone", "Abrir ${f.label}") {
                try { startActivity(f.launch!!.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Throwable) { toast("No se pudo abrir") }
            }.apply { background = round(ACCENT, 13); (getChildAt(1) as TextView).setTextColor(ON_ACCENT); (getChildAt(0) as ImageView).setImageDrawable(LineIcon("smartphone", ON_ACCENT)) },
                LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(12) })
        }
        hidden.isNotEmpty() -> {
            c.addView(label("Tu coche trae $name, pero está oculto por la región del coche. Pon la región en España en los Ajustes del coche.", 16f, AMBER)
                .apply { setPadding(0, dp(10), 0, dp(12)) })
            c.addView(iconPill("gear", "Abrir los Ajustes del coche") { if (!SystemScreens.systemSettings(this)) toast("No se pudo abrir") }, LinearLayout.LayoutParams(-1, dp(54)))
        }
        aa -> {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(label("Tu coche no trae Android Auto. Hay una app libre, DiAuto, que lo añade. No es oficial de Google ni de BYD y solo se ha probado en pantallas BYD más nuevas, puede no funcionar. Si la quieres, descárgala tú desde su página (escanea el QR) e instálala con «Instalar una app».",
                15f, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            val qr = ImageView(this).apply { setImageBitmap(qrBitmap("https://github.com/shihabal3amri/DiAuto", dp(120))); background = round(Color.WHITE, 8); setPadding(dp(6), dp(6), dp(6), dp(6)) }
            row.addView(qr, LinearLayout.LayoutParams(dp(132), dp(132)).apply { leftMargin = dp(14) })
            c.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        else -> c.addView(label("Para CarPlay hace falta un adaptador USB con chip de Apple (de los que traen su propia app). Cuando lo conectes e instales su app, aparecerá aquí.",
            15f, TEXT).apply { setPadding(0, dp(10), 0, 0) })
    }
}

/** QR nítido: un píxel por módulo y luego escalado entero sin suavizado. */
private fun qrBitmap(text: String, sizePx: Int): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val w = m.width
    val px = IntArray(w * w) { if (m[it % w, it / w]) Color.BLACK else Color.WHITE }
    val small = Bitmap.createBitmap(px, w, w, Bitmap.Config.ARGB_8888)
    val k = maxOf(1, sizePx / w)
    return Bitmap.createScaledBitmap(small, w * k, w * k, false)
}

/** Lista de .apk encontrados para instalar (con confirmación). */
private fun HomeActivity.apkPanel() {
    val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xF20B1016.toInt()); setPadding(dp(28), dp(16), dp(28), dp(16)) }
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(label("Instalar una app", 22f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(pill("Cerrar") { closeOverlay() })
    panel.addView(head)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    panel.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
    list.addView(label("Buscando archivos .apk en el USB, la tarjeta SD y Descargas…", 16f, MUTED))
    openOverlay(panel)
    Thread {
        val apks = try { ApkInstaller.find(applicationContext) } catch (e: Throwable) { emptyList() }
        ui.post {
            list.removeAllViews()
            if (apks.isEmpty()) {
                list.addView(label("No hay ningún .apk. Copia el archivo en la raíz de un USB (o en una carpeta dentro), conéctalo al coche y vuelve a mirar.", 16f, MUTED))
                return@post
            }
            apks.forEach { a ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = round(CARD2, 12); setPadding(dp(16), dp(12), dp(16), dp(12)) }
                val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                txt.addView(label(a.label, 18f, Color.WHITE))
                txt.addView(label(listOfNotNull("versión ${a.version}", a.installed?.let { "tienes la $it" }, a.where,
                    String.format(Locale.getDefault(), "%.1f MB", a.bytes / 1048576.0)).joinToString(" · "), 14f, MUTED).apply { setPadding(0, dp(4), 0, 0) })
                txt.addView(label(a.file.path, 12f, MUTED))
                r.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(pill(if (a.installed != null) "Actualizar" else "Instalar") { confirmInstall(a) }, LinearLayout.LayoutParams(dp(150), dp(52)))
                list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
    }.start()
}

private fun HomeActivity.confirmInstall(a: ApkInstaller.Apk) {
    val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(0xF20B1016.toInt()); setPadding(dp(80), dp(40), dp(80), dp(40)) }
    panel.addView(label("¿Instalar ${a.label}?", 28f, Color.WHITE).apply { gravity = Gravity.CENTER })
    panel.addView(label("Versión ${a.version} · ${a.pkg}\nInstala solo apps que conozcas y de donde te fíes.", 17f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(24)) })
    val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    val yes = pill("Instalar") {}.apply { background = round(ACCENT, 13); setTextColor(ON_ACCENT) }
    yes.setOnClickListener {
        yes.isEnabled = false; yes.text = "Instalando…"
        Thread { val m = ApkInstaller.install(applicationContext, a); ui.post { toast(m); closeOverlay() } }.start()
    }
    btns.addView(yes, LinearLayout.LayoutParams(dp(220), dp(58)))
    btns.addView(pill("Cancelar") { apkPanel() }, LinearLayout.LayoutParams(dp(220), dp(58)).apply { leftMargin = dp(14) })
    panel.addView(btns)
    openOverlay(panel)
}

/** Todas las apps instaladas (Menú → Todas las apps). */
internal fun HomeActivity.appsPage(): View {
    val col = page()
    pageHead(col, "Todas las apps")
    val w = (resources.displayMetrics.widthPixels - dp(48))
    col.addView(appsGrid(if (portrait) 4 else 8, w, null), LinearLayout.LayoutParams(-1, 0, 1f))
    return col
}

