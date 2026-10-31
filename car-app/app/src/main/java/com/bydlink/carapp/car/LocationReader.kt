package com.bydlink.carapp.car

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import com.bydlink.carapp.model.Location

/** GPS del Android del head unit (no del SDK BYD). Requiere ACCESS_FINE_LOCATION. */
class LocationReader(private val ctx: Context) {

    @Volatile var last = Location()
    private val lm by lazy { ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private val listener = LocationListener { loc ->
        last = Location(loc.latitude, loc.longitude, loc.bearing.toInt(), System.currentTimeMillis())
    }

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 5000L, 5f, listener)
            }
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                last = Location(it.latitude, it.longitude, it.bearing.toInt(), System.currentTimeMillis())
            }
        } catch (e: Throwable) { e.printStackTrace() }
    }

    fun stop() { try { lm.removeUpdates(listener) } catch (e: Throwable) {} }
}
