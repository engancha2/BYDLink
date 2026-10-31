package com.bydlink.carapp.car

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.bydlink.carapp.model.Location

/**
 * GPS del Android del head unit (no del SDK BYD). Requiere ACCESS_FINE_LOCATION.
 * La última posición se guarda: al quitar el contacto DiLink cierra la app y, cuando el vigilante la vuelve a
 * abrir, el GPS ya está apagado. Sin guardarla, el móvil se quedaba sin saber dónde está el coche aparcado.
 */
class LocationReader(private val ctx: Context) {

    @Volatile var last = Location()
    private val lm by lazy { ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private val prefs by lazy { ctx.getSharedPreferences("location", Context.MODE_PRIVATE) }
    private var savedAt = 0L

    // OJO: en Android 10 (el del coche) estos cuatro métodos son abstractos; con una lambda solo se
    // implementa el primero y la app se CERRABA al apagarse/encenderse el GPS (p. ej. al apagar el coche).
    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: android.location.Location) = use(loc)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Android < 10")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    fun start() {
        restore()
        try {
            // Siempre, aunque ahora esté apagado: DiLink apaga el GPS al quitar el contacto y, si la app arranca así,
            // se quedaba sin posición en el siguiente viaje. Registrado, empieza a llegar en cuanto se enciende.
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try { if (lm.allProviders.contains(p)) lm.requestLocationUpdates(p, 5000L, 5f, listener) } catch (e: Throwable) { e.printStackTrace() }
            }
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Throwable) { null } }
                .maxByOrNull { it.time }
                ?.takeIf { it.time > last.ts }
                ?.let { use(it) }
        } catch (e: Throwable) { e.printStackTrace() }
    }

    fun stop() { try { lm.removeUpdates(listener) } catch (e: Throwable) {} }

    private fun use(loc: android.location.Location) {
        // Hora del fijo (no la de ahora): el móvil puede decir "posición de hace 2 h" si el coche lleva rato apagado.
        val t = loc.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        last = Location(loc.latitude, loc.longitude, loc.bearing.toInt(), t)
        val now = System.currentTimeMillis()
        if (now - savedAt > 20_000) {
            savedAt = now
            prefs.edit().putString("last", "${loc.latitude},${loc.longitude},${loc.bearing.toInt()},$t").apply()
        }
    }

    private fun restore() {
        val p = prefs.getString("last", null)?.split(',') ?: return
        try { last = Location(p[0].toDouble(), p[1].toDouble(), p[2].toInt(), p[3].toLong()) } catch (e: Throwable) {}
    }
}
