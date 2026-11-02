package com.bydlink.carapp.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import com.bydlink.carapp.service.StateHub
import com.bydlink.carapp.ui.HomeActivity.Companion.INK
import com.bydlink.carapp.ui.HomeActivity.Companion.CARD2
import com.bydlink.carapp.ui.HomeActivity.Companion.MUTED

/**
 * Página Mapa del lanzador: mapa propio con el coche (OpenStreetMap vía Leaflet en un WebView) y accesos a la
 * navegación de verdad (Waze o Google Maps): casa, trabajo, buscar un destino y Waze + música en pantalla dividida.
 * La navegación en sí la hace la app del usuario: el lanzador no se mete en ella.
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun HomeActivity.mapPage(): View {
    val col = page()
    pageHead(col, "Mapa")
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    col.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))

    // ── mapa ──
    val mapCard = card().apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
    row.addView(mapCard, LinearLayout.LayoutParams(0, -1, 1.6f).apply { rightMargin = dp(16) })
    var ready = false
    val web = WebView(this).apply {
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
        setBackgroundColor(0xFF0E1622.toInt())
        webViewClient = object : WebViewClient() { override fun onPageFinished(v: WebView, url: String?) { ready = true } }
        // Leaflet va dentro de la app (assets/leaflet): la página abre al momento; solo los mapas necesitan internet.
        loadDataWithBaseURL("file:///android_asset/", MAP_HTML, "text/html", "utf-8", null)
    }
    mapCard.addView(web, LinearLayout.LayoutParams(-1, -1))

    // ── navegación ──
    val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    row.addView(side, LinearLayout.LayoutParams(0, -1, 1f))
    val nav = navApp()
    side.addView(label(if (nav != null) "Navegar con ${appName(nav.packageName)}" else "Navegación", 16f, MUTED, bold = true).apply { setPadding(dp(4), 0, 0, dp(8)) })
    fun big(t: String, onClick: () -> Unit) = pill(t, onClick).apply { textSize = 18f }
        .also { side.addView(it, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(10) }) }
    val homeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    side.addView(homeRow, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(10) })
    homeRow.addView(pill("Casa") { goHome() }.apply { textSize = 18f }, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(10) })
    homeRow.addView(pill("Trabajo") { goFavorite("work") }.apply { textSize = 18f }, LinearLayout.LayoutParams(0, -1, 1f))

    val search = EditText(this).apply {
        hint = "Buscar destino…"; setHintTextColor(MUTED); setTextColor(INK); textSize = 17f; isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_GO
        background = round(CARD2, 14); setPadding(dp(16), 0, dp(16), 0)
    }
    side.addView(search, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(10) })
    val goSearch = {
        val q = search.text.toString().trim()
        if (q.isNotEmpty()) navigate(Uri.parse("waze://?q=${Uri.encode(q)}&navigate=yes"), Uri.parse("google.navigation:q=${Uri.encode(q)}"))
    }
    search.setOnEditorActionListener { _, id, ev -> if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { goSearch(); true } else false }

    big(if (nav != null) "Abrir ${appName(nav.packageName)}" else "Elegir app de navegación") { nav?.let { launch(it) } ?: toast("Instala Waze o Google Maps") }
    val splitBtn = big("Navegación + música en pantalla dividida") {
        val n = nav ?: return@big toast("Instala Waze o Google Maps")
        val music = musicApp()
        Thread { val m = SplitScreen.open(applicationContext, n, music); ui.post { toast(m) } }.start()
    }
    val hint = label("", 13f, MUTED).apply { setPadding(dp(4), dp(4), 0, 0) }; side.addView(hint)

    var lastMove = 0L
    refreshTab = {
        val s = StateHub.state
        val loc = s?.location
        val now = System.currentTimeMillis()
        if (ready && loc != null && (loc.lat != 0.0 || loc.lng != 0.0) && now - lastMove > 3000) {
            lastMove = now
            web.evaluateJavascript("setCar(${loc.lat},${loc.lng},${loc.heading},${s.speed})", null)
        }
        val moving = s != null && (s.speed > 0 || s.gear == "D" || s.gear == "R")
        search.isEnabled = !moving; search.alpha = if (moving) .4f else 1f
        hint.text = if (moving) "En marcha no se escribe: usa Casa/Trabajo o la voz de tu navegador." else
            if (loc == null || (loc.lat == 0.0 && loc.lng == 0.0)) "Esperando la posición del coche…" else ""
        splitBtn.visibility = if (nav != null) View.VISIBLE else View.GONE
    }
    return col
}

/** La app de navegación: la elegida para el hueco "Navegación" del dock, o Waze / Google Maps. */
internal fun HomeActivity.navApp(): ComponentName? =
    prefs.getString("slot0", null)?.let { ComponentName.unflattenFromString(it) }?.takeIf { resolve(it.flattenToString()) != null }
        ?: listOf("com.waze", "com.google.android.apps.maps").firstNotNullOfOrNull { resolve(it) }

/** La app de música del dock (para ponerla al lado del navegador). */
internal fun HomeActivity.musicApp(): ComponentName? =
    prefs.getString("slot1", null)?.let { ComponentName.unflattenFromString(it) }?.takeIf { resolve(it.flattenToString()) != null }
        ?: listOf("com.spoti.plus", "com.spotify.music", "com.byd.mediacenter", "app.revanced.android.apps.youtube.music").firstNotNullOfOrNull { resolve(it) }

/** Abre la ruta en Waze; si no está, en la app que entienda la segunda dirección (Google Maps) o geo:. */
internal fun HomeActivity.navigate(waze: Uri, fallback: Uri) {
    val tries = listOf(Intent(Intent.ACTION_VIEW, waze), Intent(Intent.ACTION_VIEW, fallback))
    for (i in tries) {
        if (i.resolveActivity(packageManager) != null) { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
    }
    toast("No hay ninguna app de navegación instalada")
}

/** Casa / Trabajo guardados en Waze (o el que tenga Google Maps). */
internal fun HomeActivity.goFavorite(which: String) =
    navigate(Uri.parse("waze://?favorite=$which&navigate=yes"), Uri.parse("google.navigation:q=${if (which == "home") "casa" else "trabajo"}"))

/** Navegar a unas coordenadas (Waze; si no, Google Maps o cualquier app que entienda geo:). */
internal fun HomeActivity.navTo(lat: Double, lng: Double) {
    val ll = String.format(java.util.Locale.US, "%.6f,%.6f", lat, lng)
    val tries = listOf(Intent(Intent.ACTION_VIEW, Uri.parse("waze://?ll=$ll&navigate=yes")), Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$ll")),
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:$ll?q=$ll")))
    for (i in tries) if (i.resolveActivity(packageManager) != null) { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
    toast("No hay ninguna app de navegación instalada")
}

/** Mapa oscuro de OpenStreetMap (teselas de CARTO) con el coche; setCar(lat, lng, rumbo, velocidad) lo mueve. */
private const val MAP_HTML = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="leaflet/leaflet.css">
<script src="leaflet/leaflet.js"></script>
<style>html,body,#m{margin:0;height:100%;background:#0d141c}.leaflet-control-attribution{font-size:10px;background:rgba(0,0,0,.4)!important;color:#8b96a1}
.leaflet-control-attribution a{color:#8b96a1}.car{width:26px;height:26px;border-radius:50%;background:#74e1ce;border:3px solid #fff;box-shadow:0 0 0 6px rgba(116,225,206,.25)}
#off{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:#8b96a1;font:16px sans-serif}</style></head>
<body><div id="m"></div><div id="off">Cargando mapa…</div><script>
var map=null,car=null,follow=true;
function init(){ if(map) return; if(!window.L){document.getElementById('off').textContent='No se pudo cargar el mapa.';return;}
 document.getElementById('off').style.display='none';
 map=L.map('m',{zoomControl:false,attributionControl:true}).setView([40.4,-3.7],6);
 var t=L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',{maxZoom:19,
  attribution:'&copy; OpenStreetMap &copy; CARTO'}).addTo(map);
 t.on('tileerror',function(){ var o=document.getElementById('off'); o.style.display='flex'; o.style.pointerEvents='none';
  o.textContent='Sin cobertura: el mapa se verá al tener internet.'; });
 map.on('dragstart',function(){follow=false;}); }
function setCar(lat,lng,hd,spd){ if(!map) return; var p=[lat,lng];
 if(!car){ car=L.marker(p,{icon:L.divIcon({className:'',html:'<div class="car"></div>',iconSize:[26,26],iconAnchor:[13,13]})}).addTo(map); map.setView(p,15); }
 else car.setLatLng(p);
 if(follow) map.panTo(p,{animate:true}); }
window.addEventListener('load',init);
</script></body></html>"""
