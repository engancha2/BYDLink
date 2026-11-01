package com.bydlink.carapp.ui

import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import com.bydlink.carapp.service.StateHub
import com.bydlink.carapp.ui.HomeActivity.Companion.ACCENT
import com.bydlink.carapp.ui.HomeActivity.Companion.CARD2
import com.bydlink.carapp.ui.HomeActivity.Companion.MUTED
import com.bydlink.carapp.ui.HomeActivity.Companion.TEXT

/**
 * Página Música del lanzador: lo que suena en grande (carátula, título, progreso, mandos), volumen, elegir entre las
 * apps que están sonando y abrir las fuentes de música. No reproduce nada por su cuenta: manda a la app que suena.
 * Las apps de vídeo solo se abren con el coche parado.
 */
internal fun HomeActivity.mediaPage(): View {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), dp(8)) }

    // ── lo que suena ──
    val now = card(); col.addView(now)
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = round(CARD2, 18); clipToOutline = true }
    row.addView(art, LinearLayout.LayoutParams(dp(170), dp(170)))
    val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), 0, 0, 0) }
    val src = label("", 14f, MUTED, bold = true); val title = label("Nada sonando", 26f, Color.WHITE, bold = true).apply { maxLines = 2 }
    val artist = label("", 18f, TEXT).apply { maxLines = 1 }
    info.addView(src); info.addView(title); info.addView(artist)
    val bar = Bar(Color.WHITE); info.addView(bar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(16) })
    val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val tPos = label("", 13f, MUTED); val tDur = label("", 13f, MUTED).apply { gravity = Gravity.END }
    times.addView(tPos, LinearLayout.LayoutParams(0, -2, 1f)); times.addView(tDur, LinearLayout.LayoutParams(0, -2, 1f))
    info.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun cbtn(kind: Int, size: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageDrawable(HomeActivity.MediaIcon(kind)); background = round(CARD2, 40); setPadding(dp(16), dp(16), dp(16), dp(16))
        isClickable = true; setOnClickListener { onClick() }
    }.also { ctl.addView(it, LinearLayout.LayoutParams(dp(size), dp(size)).apply { rightMargin = dp(16) }) }
    cbtn(HomeActivity.MediaIcon.PREV, 64) { controller?.transportControls?.skipToPrevious() }
    val play = cbtn(HomeActivity.MediaIcon.PLAY, 76) {
        val c = controller ?: return@cbtn
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }
    cbtn(HomeActivity.MediaIcon.NEXT, 64) { controller?.transportControls?.skipToNext() }
    info.addView(ctl, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
    row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
    now.addView(row)

    // volumen (música)
    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val vRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(16), 0, 0) }
    vRow.addView(label("Volumen", 15f, MUTED), LinearLayout.LayoutParams(dp(90), -2))
    val vol = SeekBar(this).apply {
        max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC); progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) try { am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0) } catch (e: Throwable) {}
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }
    vRow.addView(vol, LinearLayout.LayoutParams(0, dp(48), 1f))
    now.addView(vRow)

    // ── qué está sonando (si hay varias apps) ──
    val sessions = card(); col.addView(sessions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    sessions.addView(label("Sonando ahora", 16f, MUTED, bold = true))
    val sRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
    sessions.addView(HorizontalScroll(sRow))

    // ── fuentes ──
    val srcCard = card(); col.addView(srcCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    srcCard.addView(label("Fuentes", 16f, MUTED, bold = true))
    val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
    srcCard.addView(HorizontalScroll(grid))
    val videoBtns = ArrayList<View>()
    MEDIA_SOURCES.forEach { (name, cands, video) ->
        val cn = cands.firstNotNullOfOrNull { resolve(it) } ?: return@forEach
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(14), dp(10), dp(14), dp(10)); background = round(CARD2, 16)
            isClickable = true
            setOnClickListener {
                val s = StateHub.state
                if (video && s != null && (s.speed > 0 || s.gear == "D" || s.gear == "R")) toast("Los vídeos solo con el coche parado") else launch(cn)
            }
        }
        v.addView(ImageView(this).apply { setImageDrawable(icon(cn)) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        v.addView(label(name + if (video) " (parado)" else "", 13f, TEXT).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        grid.addView(v, LinearLayout.LayoutParams(dp(130), -2).apply { rightMargin = dp(10) })
        if (video) videoBtns += v
    }
    if (grid.childCount == 0) grid.addView(label("No hay apps de música instaladas.", 14f, MUTED))

    var lastSessions = ""
    refreshTab = {
        val c = controller; val md = c?.metadata
        src.text = c?.packageName?.let { appName(it).uppercase() } ?: ""
        title.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: if (c == null) "Nada sonando" else "Música"
        artist.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (bmp != null) art.setImageBitmap(bmp) else art.setImageDrawable(c?.packageName?.let { try { packageManager.getApplicationIcon(it) } catch (e: Throwable) { null } })
        val st = c?.playbackState
        play.setImageDrawable(HomeActivity.MediaIcon(if (st?.state == PlaybackState.STATE_PLAYING) HomeActivity.MediaIcon.PAUSE else HomeActivity.MediaIcon.PLAY))
        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        var pos = st?.position ?: 0L
        if (st?.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        bar.set(if (dur > 0) (pos * 100 / dur).toInt() else 0, Color.WHITE)
        tPos.text = if (dur > 0) mmss(pos) else ""; tDur.text = if (dur > 0) mmss(dur) else ""
        if (!vol.isPressed) vol.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        // botones de las apps que suenan
        val list = currentSessions().orEmpty()
        val key = list.joinToString { it.packageName } + "|" + c?.packageName
        if (key != lastSessions) {
            lastSessions = key
            sRow.removeAllViews()
            if (list.isEmpty()) sRow.addView(label(if (currentSessions() == null) "BYDLink aún no tiene permiso para ver la música (se da solo con el ADB)." else "Ninguna app está sonando.", 14f, MUTED))
            list.forEach { mc ->
                sRow.addView(pill(appName(mc.packageName)) { selectSession(mc) }.also { active(it, mc.sessionToken == c?.sessionToken) },
                    LinearLayout.LayoutParams(-2, dp(48)).apply { rightMargin = dp(8) })
            }
        }
        val moving = StateHub.state?.let { it.speed > 0 || it.gear == "D" || it.gear == "R" } == true
        videoBtns.forEach { it.alpha = if (moving) .35f else 1f }
    }
    return ScrollView(this).apply { addView(col) }
}

private fun mmss(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

/** Fuentes de música conocidas (la primera instalada de cada lista); true = vídeo (solo parado). */
private val MEDIA_SOURCES = listOf(
    Triple("Spotify", listOf("com.spoti.plus", "com.spotify.music"), false),
    Triple("YouTube Music", listOf("app.revanced.android.apps.youtube.music", "com.google.android.apps.youtube.music"), false),
    Triple("Radio", listOf("com.byd.mediacenter/com.byd.mediacenter.radio.view.RadioActivity"), false),
    Triple("Multimedia BYD", listOf("com.byd.mediacenter"), false),
    Triple("TuneIn", listOf("radiotime.player"), false),
    Triple("OpenTune", listOf("com.Arturo254.opentune"), false),
    Triple("YouTube", listOf("app.revanced.android.youtube", "com.google.android.youtube"), true),
    Triple("Netflix", listOf("com.netflix.mediaclient"), true),
    Triple("Prime Video", listOf("com.amazon.avod.thirdpartyclient"), true),
)
