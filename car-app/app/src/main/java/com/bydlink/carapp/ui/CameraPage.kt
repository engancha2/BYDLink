package com.bydlink.carapp.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import com.bydlink.carapp.camera.CamError
import com.bydlink.carapp.camera.VideoStore
import com.bydlink.carapp.service.StateHub
import com.bydlink.carapp.ui.HomeActivity.Companion.ACCENT
import com.bydlink.carapp.ui.HomeActivity.Companion.AMBER
import com.bydlink.carapp.ui.HomeActivity.Companion.CARD2
import com.bydlink.carapp.ui.HomeActivity.Companion.MUTED
import com.bydlink.carapp.ui.HomeActivity.Companion.RED
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Página Cámara del lanzador: la cámara en directo (solo con el coche parado), grabar a mano, dónde se guardan los
 * vídeos (memoria del coche, SD o USB), la grabación continua (dashcam) y los vídeos guardados, que se ven aquí mismo.
 */
internal fun HomeActivity.cameraPage(): View {
    val cam = StateHub.camera
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, dp(4), dp(8)) }

    // ── directo ──
    val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    row.addView(left, LinearLayout.LayoutParams(0, -1, 1.45f).apply { rightMargin = dp(12) })
    val screen = FrameLayout(this).apply { background = round(0xFF05080C.toInt(), 18); clipToOutline = true }
    left.addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
    val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    screen.addView(img, FrameLayout.LayoutParams(-1, -1))
    val msg = label(if (cam == null) "BYDLink aún está arrancando…" else "Abriendo la cámara…", 17f, MUTED).apply {
        gravity = Gravity.CENTER; setPadding(dp(30), 0, dp(30), 0)
    }
    screen.addView(msg, FrameLayout.LayoutParams(-1, -1))
    val recBadge = label("", 15f, Color.WHITE, bold = true).apply {
        background = round(RED, 14); setPadding(dp(12), dp(5), dp(12), dp(5)); visibility = View.GONE
    }
    screen.addView(recBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(14), dp(14), 0, 0) })
    val camName = label("Cámara trasera", 13f, Color.WHITE).apply { background = round(0x99000000.toInt(), 12); setPadding(dp(10), dp(4), dp(10), dp(4)) }
    screen.addView(camName, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(14), dp(14), 0) })

    val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    left.addView(btns, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(12) })
    fun video(action: String, payload: Map<String, Any?>, ok: ((Map<String, Any?>) -> Unit)? = null) {
        val c = StateHub.camera ?: return toast("BYDLink aún está arrancando")
        Thread {
            try { val r = c.handle(action, payload).orEmpty(); ui.post { ok?.invoke(r) } }
            catch (e: CamError) { ui.post { toast(e.message ?: "No se pudo") } }
            catch (e: Throwable) { ui.post { toast("No se pudo: ${e.message}") } }
        }.start()
    }
    var manual = false
    val rec1 = pill("●  Grabar 1 minuto") {
        video("recordVideo", mapOf("seconds" to 60)) { r -> toast(if (r["already"] == true) "Ya se está grabando" else "Grabando 1 minuto en ${r["store"] ?: "la memoria"}") }
    }.apply { textSize = 17f }
    val recLong = pill("Grabar hasta parar") {
        if (manual) video("stopVideo", emptyMap()) { toast("Vídeo guardado") }
        else video("recordVideo", mapOf("seconds" to 0)) { toast("Grabando… pulsa Parar para terminar (como mucho 30 min)") }
    }.apply { textSize = 17f }
    btns.addView(rec1, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(10) })
    btns.addView(recLong, LinearLayout.LayoutParams(0, -1, 1f))

    // ── ajustes y vídeos ──
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    row.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(0, -1, 1f))

    val storeCard = card(); col.addView(storeCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    storeCard.addView(label("Guardar los vídeos en", 16f, MUTED, bold = true))
    val storeList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
    storeCard.addView(storeList)
    val storeNote = label("", 13f, MUTED).apply { setPadding(dp(2), dp(6), 0, 0) }
    storeCard.addView(storeNote)

    val dashCard = card(); col.addView(dashCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    dashCard.addView(label("Grabación continua (dashcam)", 16f, MUTED, bold = true))
    fun chips(parent: LinearLayout, items: List<Pair<String, String>>, onPick: (String) -> Unit): Map<String, TextView> {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        parent.addView(r)
        return items.withIndex().associate { (i, it) ->
            val (k, t) = it
            k to pill(t) { onPick(k) }.apply { textSize = 14f; setPadding(dp(4), 0, dp(4), 0); maxLines = 1 }
                .also { v -> r.addView(v, LinearLayout.LayoutParams(0, dp(46), 1f).apply { if (i > 0) leftMargin = dp(6) }) }
        }
    }
    var refreshStatus: () -> Unit = {}
    val modeChips = chips(dashCard, listOf("off" to "Apagada", "parked" to "Aparcado", "drive" to "En marcha", "always" to "Siempre")) { m ->
        video("setVideoConfig", mapOf("mode" to m)) { refreshStatus() }
    }
    val modeNote = label("", 13f, MUTED).apply { setPadding(dp(2), dp(6), 0, 0) }
    dashCard.addView(modeNote)
    dashCard.addView(label("Calidad: normal 640 px (≈10 MB/min) o alta 768 px (≈19 MB/min)", 14f, MUTED).apply { setPadding(0, dp(10), 0, 0) })
    val qChips = chips(dashCard, listOf("normal" to "Normal", "alta" to "Alta")) { q -> video("setVideoConfig", mapOf("quality" to q)) { refreshStatus() } }
    dashCard.addView(label("Espacio máximo para vídeos (GB)", 14f, MUTED).apply { setPadding(0, dp(10), 0, 0) })
    val gbChips = chips(dashCard, listOf("0" to "Auto", "4" to "4", "8" to "8", "16" to "16", "32" to "32", "64" to "64")) { g ->
        video("setVideoConfig", mapOf("quotaGb" to g.toInt())) { refreshStatus() }
    }
    val usage = label("", 13f, MUTED).apply { setPadding(dp(2), dp(6), 0, 0) }
    dashCard.addView(usage)

    val listCard = card(); col.addView(listCard, LinearLayout.LayoutParams(-1, -2))
    listCard.addView(label("Vídeos guardados", 16f, MUTED, bold = true))
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
    listCard.addView(list)

    // ── estado ──
    var alive = true
    var lastKey = ""
    var lastPoll = 0L
    refreshStatus = {
        val c = StateHub.camera
        if (c != null) Thread {
            val st = try { c.videoStatus(false) } catch (e: Throwable) { null }
            val vids = try { VideoStore.list(applicationContext, 40) } catch (e: Throwable) { emptyList() }
            ui.post {
                if (alive && st != null) renderVideoState(st, vids, storeList, storeNote, modeChips, modeNote, qChips, gbChips, usage, list, lastKey,
                    setKey = { lastKey = it }, send = { (a, p) -> video(a, p) { refreshStatus() } })
            }
        }.start()
    }

    // Directo: se pide al entrar y, si no se pudo (coche en marcha, cámara en prueba…), se reintenta cada 5 s.
    var previewErr: String? = null
    var lastTry = 0L
    fun startPreview() {
        val c = StateHub.camera ?: return
        lastTry = System.currentTimeMillis()
        previewErr = c.screenPreview { bmp -> ui.post { if (alive) { img.setImageBitmap(bmp); msg.visibility = View.GONE } } }
    }
    startPreview()
    leaveTab = { alive = false; StateHub.camera?.screenPreview(null) }

    refreshTab = {
        val c = StateHub.camera
        if (c != null && !c.screenActive() && System.currentTimeMillis() - lastTry > 5000) startPreview()
        val st = if (c?.screenActive() == true) c.screenState() else previewErr ?: c?.screenState()
        if (st != null && st.isNotEmpty()) { msg.text = st; msg.visibility = View.VISIBLE; img.setImageDrawable(null) }
        val recs = c?.recordings().orEmpty()
        val m = recs.firstOrNull { it.kind == VideoStore.MANUAL }
        manual = m != null
        recLong.text = if (manual) "■  Parar y guardar" else "Grabar hasta parar"
        active(recLong, manual)
        val shown = m ?: recs.firstOrNull()
        if (shown != null) {
            val s = (System.currentTimeMillis() - shown.startedAt) / 1000
            recBadge.text = "●  ${if (shown.kind == VideoStore.DASHCAM) "DASHCAM" else if (shown.kind == VideoStore.SENTRY) "CENTINELA" else "REC"}  %d:%02d".format(s / 60, s % 60)
            recBadge.visibility = View.VISIBLE
        } else recBadge.visibility = View.GONE
        val now = System.currentTimeMillis()
        if (now - lastPoll > 5000) { lastPoll = now; refreshStatus() }
    }
    return row
}

private fun HomeActivity.renderVideoState(
    st: Map<String, Any?>, vids: List<VideoStore.Video>,
    storeList: LinearLayout, storeNote: TextView, modeChips: Map<String, TextView>, modeNote: TextView,
    qChips: Map<String, TextView>, gbChips: Map<String, TextView>, usage: TextView, list: LinearLayout,
    lastKey: String, setKey: (String) -> Unit, send: (Pair<String, Map<String, Any?>>) -> Unit
) {
    @Suppress("UNCHECKED_CAST")
    val vols = st["volumes"] as? List<Map<String, Any?>> ?: emptyList()
    val cur = st["current"] as? String
    storeList.removeAllViews()
    for (v in vols) {
        val id = v["id"] as? String ?: continue
        val t = pill("${v["label"]}  ·  ${size(v["free"])} libres") { send("setVideoConfig" to mapOf("store" to id)) }.apply {
            textSize = 15f; gravity = Gravity.CENTER_VERTICAL or Gravity.START; setPadding(dp(16), 0, dp(16), 0)
        }
        active(t, id == cur)
        storeList.addView(t, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(8) })
    }
    if (vols.size <= 1) storeList.addView(label("Conecta una tarjeta SD o un USB al coche y aparecerá aquí.", 13f, MUTED))
    val curVol = vols.firstOrNull { it["id"] == cur }
    storeNote.text = listOfNotNull(
        "El soporte elegido no está conectado: se graba en la memoria del coche.".takeIf { st["missing"] == true },
        "Carpeta: ${curVol?.get("path") ?: "BYDLink"}",
        "(Carpeta de la app: se borra si desinstalas BYDLink; al actualizar no.)".takeIf { curVol?.get("lasting") == false }
    ).joinToString("\n")
    val mode = st["mode"] as? String ?: "off"
    modeChips.forEach { (k, v) -> active(v, k == mode) }
    @Suppress("UNCHECKED_CAST")
    val dash = st["dashcam"] as? Map<String, Any?>
    modeNote.text = when (mode) {
        "parked" -> "Graba sin parar con el coche aparcado, en tramos de ${st["segMin"]} min." + if (dash?.get("recording") == true) " Grabando ahora." else ""
        "drive" -> "En pruebas: graba en marcha por encima de 15 km/h y suelta la cámara por debajo de 8 (nunca con la R)."
        "always" -> "Aparcado y en marcha (en marcha, en pruebas: solo por encima de 15 km/h)."
        else -> "Apagada. El centinela sigue guardando sus clips cuando ve movimiento."
    }
    qChips.forEach { (k, v) -> active(v, k == st["quality"]) }
    val gb = (st["quotaGb"] as? Number)?.toInt() ?: 0
    gbChips.forEach { (k, v) -> active(v, k.toInt() == gb) }
    usage.text = "Ocupan ${size(curVol?.get("used"))} de ${size(st["quotaBytes"])}. Al llenarse se borra lo más viejo (dashcam primero; tus grabaciones, lo último)."

    val key = vids.joinToString { it.id + it.bytes }
    if (key == lastKey) return
    setKey(key)
    list.removeAllViews()
    if (vids.isEmpty()) { list.addView(label("Aún no hay vídeos.", 14f, MUTED)); return }
    val fmt = SimpleDateFormat("EEE d MMM · HH:mm:ss", Locale("es", "ES"))
    for (v in vids) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = round(CARD2, 12)
            setPadding(dp(12), dp(8), dp(12), dp(8)); isClickable = true; setOnClickListener { playVideo(v) }
        }
        val (name, color) = kindName(v.kind)
        r.addView(chip(name, color), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        r.addView(label(fmt.format(Date(v.ts)), 15f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(label(size(v.bytes), 13f, MUTED))
        list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }
}

private fun kindName(kind: String) = when (kind) {
    VideoStore.SENTRY -> "Centinela" to AMBER
    VideoStore.DASHCAM -> "Dashcam" to 0xFF5AA9FF.toInt()
    else -> "Grabación" to ACCENT
}

private fun size(b: Any?): String {
    val n = (b as? Number)?.toLong() ?: return "--"
    return if (n >= 1L shl 30) String.format(Locale.getDefault(), "%.1f GB", n / 1073741824.0)
    else if (n == 0L) "0 MB" else "${maxOf(1, n / 1048576)} MB"
}

/** Ver un vídeo encima de todo, con mandos para avanzar, guardarlo para siempre o borrarlo. */
private fun HomeActivity.playVideo(v: VideoStore.Video) {
    val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xF5000000.toInt()); setPadding(dp(24), dp(14), dp(24), dp(14)) }
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val (name, _) = kindName(v.kind)
    head.addView(label("$name · ${SimpleDateFormat("EEEE d MMM · HH:mm:ss", Locale("es", "ES")).format(Date(v.ts))} · ${size(v.bytes)}", 18f, Color.WHITE),
        LinearLayout.LayoutParams(0, -2, 1f))
    fun act(action: String, ok: String) {
        val c = StateHub.camera ?: return
        Thread {
            try { c.handle(action, mapOf("id" to v.id)); ui.post { toast(ok); closeOverlay(); refreshTab?.invoke() } }
            catch (e: Throwable) { ui.post { toast(e.message ?: "No se pudo") } }
        }.start()
    }
    if (v.kind != VideoStore.MANUAL) head.addView(pill("Guardar para siempre") { act("keepVideo", "Guardado en Grabaciones") }, LinearLayout.LayoutParams(-2, dp(48)).apply { leftMargin = dp(10) })
    head.addView(pill("Borrar") { act("deleteVideo", "Vídeo borrado") }.apply { setTextColor(RED) }, LinearLayout.LayoutParams(-2, dp(48)).apply { leftMargin = dp(10) })
    head.addView(pill("Cerrar") { closeOverlay() }, LinearLayout.LayoutParams(-2, dp(48)).apply { leftMargin = dp(10) })
    panel.addView(head)
    val box = FrameLayout(this)
    panel.addView(box, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
    val vv = VideoView(this)
    box.addView(vv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
    val mc = MediaController(this).apply { setAnchorView(box) }
    vv.setMediaController(mc)
    vv.setOnPreparedListener { it.isLooping = false; vv.start(); mc.show(3000) }
    vv.setOnErrorListener { _, _, _ -> toast("No se puede reproducir este vídeo (¿se está grabando aún?)"); true }
    vv.setVideoPath(v.file.path)
    openOverlay(panel)
    onOverlayClosed = { try { vv.stopPlayback() } catch (e: Throwable) {} }
}
