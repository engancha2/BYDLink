package com.bydlink.carapp.ui

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * El tiempo para la franja de Inicio: Open-Meteo (gratis, sin clave) en la posición del coche y el nombre del pueblo
 * por Nominatim (OpenStreetMap; pide un User-Agent propio y como mucho una consulta por segundo: se guarda en caché).
 * Se pide como mucho cada 20 min, o al moverse más de ~5 km. Todo fuera del hilo principal.
 */
internal object Weather {
    data class Now(
        val temp: Double, val code: Int, val windKmh: Double, val windDir: Int?, val rainPct: Int?,
        val place: String?, val ts: Long
    ) {
        val text get() = describe(code)
        val icon get() = iconOf(code)
        val wind get() = (windDir?.let { compass(it) + " · " } ?: "") + "${windKmh.roundToInt()} km/h"
    }

    private const val TAG = "BYDLink"
    private const val UA = "BYDLink/2 (https://app.bydhack.com)"
    private const val EVERY_MS = 20 * 60_000L

    @Volatile var last: Now? = null; private set
    @Volatile var error: String? = null; private set
    @Volatile private var busy = false
    private var lastLat = 0.0; private var lastLng = 0.0
    @Volatile private var failedAt = 0L

    /** Pide el tiempo si toca (en su hilo) y llama a [done] en ese hilo al terminar. */
    fun refresh(ctx: Context, lat: Double, lng: Double, done: () -> Unit) {
        if (busy || (lat == 0.0 && lng == 0.0)) return
        val l = last
        val moved = Math.abs(lat - lastLat) > 0.05 || Math.abs(lng - lastLng) > 0.05
        if (l != null && !moved && System.currentTimeMillis() - l.ts < EVERY_MS) return
        if (System.currentTimeMillis() - failedAt < 5 * 60_000L) return   // sin conexión: reintentar cada 5 min
        busy = true
        Thread {
            try {
                val w = parseForecast(get(forecastUrl(lat, lng)), Calendar.getInstance())
                val place = placeName(ctx, lat, lng)
                last = w.copy(place = place); error = null; failedAt = 0L
                lastLat = lat; lastLng = lng
            } catch (e: Throwable) {
                error = "Sin conexión"; failedAt = System.currentTimeMillis(); Log.w(TAG, "tiempo: $e")
            } finally { busy = false; done() }
        }.start()
    }

    fun forecastUrl(lat: Double, lng: Double) = String.format(Locale.US,
        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,wind_speed_10m,wind_direction_10m" +
            "&hourly=precipitation_probability&forecast_days=1&timezone=auto", lat, lng)

    /** Lo que devuelve Open-Meteo → [Now] (la probabilidad de lluvia es la de la hora en curso). */
    fun parseForecast(json: String, now: Calendar): Now {
        val o = JSONObject(json)
        val cur = o.getJSONObject("current")
        val hourly = o.optJSONObject("hourly")
        var rain: Int? = null
        if (hourly != null) {
            val times = hourly.optJSONArray("time"); val probs = hourly.optJSONArray("precipitation_probability")
            val key = String.format(Locale.US, "%04d-%02d-%02dT%02d:00", now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
                now.get(Calendar.DAY_OF_MONTH), now.get(Calendar.HOUR_OF_DAY))
            if (times != null && probs != null) for (i in 0 until times.length())
                if (times.optString(i) == key && !probs.isNull(i)) { rain = probs.optInt(i); break }
        }
        return Now(
            temp = cur.getDouble("temperature_2m"), code = cur.optInt("weather_code", -1),
            windKmh = cur.optDouble("wind_speed_10m", 0.0),
            windDir = if (cur.has("wind_direction_10m") && !cur.isNull("wind_direction_10m")) cur.optInt("wind_direction_10m") else null,
            rainPct = rain, place = null, ts = System.currentTimeMillis()
        )
    }

    /** Nombre del pueblo o ciudad (Nominatim), guardado por zona de ~5 km para no repetir consultas. */
    private fun placeName(ctx: Context, lat: Double, lng: Double): String? {
        val prefs = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)
        val key = String.format(Locale.US, "p%.2f,%.2f", lat, lng)
        prefs.getString(key, null)?.let { return it.ifEmpty { null } }
        return try {
            val url = String.format(Locale.US, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.4f&lon=%.4f&zoom=10&accept-language=es", lat, lng)
            val name = parsePlace(get(url))
            prefs.edit().putString(key, name ?: "").apply()
            name
        } catch (e: Throwable) { null }
    }

    fun parsePlace(json: String): String? {
        val a = JSONObject(json).optJSONObject("address") ?: return null
        return listOf("city", "town", "village", "municipality", "county").firstNotNullOfOrNull { k -> a.optString(k).takeIf { it.isNotBlank() } }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", UA)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Códigos WMO de Open-Meteo → texto corto en español. */
    fun describe(code: Int) = when (code) {
        0 -> "Despejado"; 1 -> "Casi despejado"; 2 -> "Algo nublado"; 3 -> "Nublado"
        45, 48 -> "Niebla"
        51, 53, 55, 56, 57 -> "Llovizna"
        61, 63, 66, 80, 81 -> "Lluvia"; 65, 67, 82 -> "Lluvia fuerte"
        71, 73, 75, 77, 85, 86 -> "Nieve"
        95, 96, 99 -> "Tormenta"
        else -> "—"
    }

    fun iconOf(code: Int) = when (code) {
        0, 1 -> "sun"; 2, 3 -> "cloud"; 45, 48 -> "fog"
        in 51..67, 80, 81, 82 -> "rain"; in 71..77, 85, 86 -> "snow"; 95, 96, 99 -> "storm"
        else -> "cloud"
    }

    /** Grados → punto cardinal de donde viene el viento (N, NE, E…). */
    fun compass(deg: Int): String = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")[(((deg % 360) + 360) % 360 + 22) / 45 % 8]
}
