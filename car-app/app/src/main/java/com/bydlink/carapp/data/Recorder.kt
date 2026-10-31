package com.bydlink.carapp.data

import android.util.Log
import com.bydlink.carapp.App
import com.bydlink.carapp.model.CarState
import com.google.firebase.database.DatabaseReference
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Grabador de VIAJES y CARGAS. El servicio llama [onState] en cada publicación de telemetría
 * (cada ~2 s). Detecta viajes (inicio al moverse, fin tras unos minutos parado en P) y sesiones
 * de carga, y los escribe en Firebase con EXACTAMENTE el formato que espera el móvil:
 *
 *   cars/{id}/trips/{tripId}            = {start, end, distanceKm, durationSec, startAddr, endAddr}
 *   cars/{id}/tripPoints/{tripId}/{i}   = {lat, lng, t, speed}      (array; el móvil hace .filter)
 *   cars/{id}/chargingSessions/{id}     = {start, socStart, socEnd, kwh, durationMin, type, place, costEur}
 *
 * (ver phone/backend.js DemoBackend y phone/app.js renderTrips/renderSessions/getTripPoints.)
 */
class Recorder(carId: String) {

    private companion object {
        const val TAG = "BYDLink"
        const val END_IDLE_MS = 3 * 60_000L     // parado en P este tiempo → fin de viaje
        const val POINT_MIN_MS = 12_000L         // un punto GPS cada ~12 s…
        const val POINT_MIN_M = 100.0            // …o cada ~100 m
        const val MAX_POINTS = 2000
    }

    private val root: DatabaseReference = App.db.getReference("cars").child(carId)

    // --- estado del viaje en curso ---
    private var tripId: String? = null
    private var tripStartTs = 0L
    private var tripDistanceKm = 0.0
    private var tripPointIdx = 0
    private var lastLat = 0.0
    private var lastLng = 0.0
    private var lastPointTs = 0L
    private var stoppedSince = 0L

    // --- estado de la carga en curso ---
    private var chgId: String? = null
    private var chgStartTs = 0L
    private var chgSocStart: Int? = null
    private var chgKwh = 0.0
    private var chgType: String? = null
    private var chgLastTs = 0L

    fun onState(s: CarState) {
        try { trips(s) } catch (e: Throwable) { Log.e(TAG, "recorder viaje", e) }
        try { charging(s) } catch (e: Throwable) { Log.e(TAG, "recorder carga", e) }
    }

    // ======================= VIAJES =======================
    private fun trips(s: CarState) {
        val moving = s.speed in 1..300 || s.gear == "D" || s.gear == "R"
        val now = if (s.ts > 0) s.ts else System.currentTimeMillis()
        val loc = s.location
        val hasLoc = loc.lat != 0.0 || loc.lng != 0.0

        if (tripId == null) {
            if (moving) startTrip(s, now)
            return
        }

        // Viaje en curso: acumula distancia y suelta puntos.
        if (hasLoc) {
            val d = if (lastLat != 0.0 || lastLng != 0.0) haversineKm(lastLat, lastLng, loc.lat, loc.lng) else 0.0
            val farEnough = d * 1000.0 >= POINT_MIN_M
            val longEnough = now - lastPointTs >= POINT_MIN_MS
            if ((farEnough || longEnough) && tripPointIdx < MAX_POINTS) {
                if (d.isFinite() && d < 5.0) tripDistanceKm += d   // ignora saltos GPS absurdos
                writePoint(loc.lat, loc.lng, now, s.speed)
                lastLat = loc.lat; lastLng = loc.lng; lastPointTs = now
            }
        }

        if (moving) {
            stoppedSince = 0L
        } else if (s.gear == "P") {
            if (stoppedSince == 0L) stoppedSince = now
            else if (now - stoppedSince >= END_IDLE_MS) endTrip(now)
        }
    }

    private fun startTrip(s: CarState, now: Long) {
        val id = root.child("trips").push().key ?: return
        tripId = id
        tripStartTs = now
        tripDistanceKm = 0.0
        tripPointIdx = 0
        stoppedSince = 0L
        val loc = s.location
        lastLat = loc.lat; lastLng = loc.lng; lastPointTs = 0L
        if (loc.lat != 0.0 || loc.lng != 0.0) { writePoint(loc.lat, loc.lng, now, s.speed); lastPointTs = now }
        Log.i(TAG, "viaje iniciado $id")
    }

    private fun endTrip(now: Long) {
        val id = tripId ?: return
        val durationSec = ((now - tripStartTs) / 1000L).coerceAtLeast(0)
        // Viajes de broma (sin recorrido real): se descartan para no ensuciar la lista.
        if (tripDistanceKm >= 0.2 && durationSec >= 60) {
            root.child("trips").child(id).setValue(
                mapOf(
                    "start" to tripStartTs,
                    "end" to now,
                    "distanceKm" to round1(tripDistanceKm),
                    "durationSec" to durationSec,
                    "startAddr" to null,
                    "endAddr" to null
                )
            )
            Log.i(TAG, "viaje guardado $id: ${round1(tripDistanceKm)} km, ${durationSec}s")
        } else {
            root.child("trips").child(id).removeValue()
            root.child("tripPoints").child(id).removeValue()
        }
        tripId = null
    }

    private fun writePoint(lat: Double, lng: Double, t: Long, speed: Int) {
        val id = tripId ?: return
        root.child("tripPoints").child(id).child(tripPointIdx.toString())
            .setValue(mapOf("lat" to lat, "lng" to lng, "t" to t, "speed" to speed))
        tripPointIdx++
    }

    // ======================= CARGAS =======================
    // Una sesión va de enchufar a desenchufar: las pausas del BMS (estado "en pausa", 0 kW un rato) no la cortan.
    // Energía: el contador de la propia sesión del coche (chargedKwh); si no lo hay, kW × tiempo; y si tampoco, el %
    // ganado × la capacidad que se deduce de la energía restante. Se guarda cada minuto para no perderla.
    private var chgSocLast: Int? = null
    private var chgCapKwh: Double? = null
    private var chgNotChargingSince = 0L
    private var chgSavedAt = 0L

    private fun plugged(s: CarState) = s.charging || (s.chargingGun != null && s.chargingGun != "none")

    private fun charging(s: CarState) {
        val now = if (s.ts > 0) s.ts else System.currentTimeMillis()
        if (chgId == null) {
            if (s.charging) {
                chgId = root.child("chargingSessions").push().key
                chgStartTs = now
                chgSocStart = s.soc
                chgKwh = 0.0
                chgType = s.chargingGun?.takeIf { it == "AC" || it == "DC" }
                chgLastTs = now; chgNotChargingSince = 0L; chgSavedAt = 0L
                Log.i(TAG, "carga iniciada ${chgId}")
            }
            return
        }
        val dtH = (now - chgLastTs).coerceAtLeast(0) / 3_600_000.0
        chgLastTs = now
        if (s.charging) {
            chgNotChargingSince = 0L
            when {
                s.chargedKwh != null -> chgKwh = maxOf(chgKwh, s.chargedKwh)
                (s.chargeKw ?: 0.0) > 0 -> chgKwh += (s.chargeKw ?: 0.0) * dtH
            }
            if (chgType == null) chgType = s.chargingGun?.takeIf { it == "AC" || it == "DC" }
                ?: (s.chargeKw ?: 0.0).takeIf { it > 15 }?.let { "DC" }
        } else if (chgNotChargingSince == 0L) chgNotChargingSince = now
        s.soc?.let { chgSocLast = it }
        if (s.energyLeftKwh != null && (s.soc ?: 0) >= 10) chgCapKwh = s.energyLeftKwh / (s.soc!! / 100.0)
        val ended = !plugged(s) || (chgNotChargingSince > 0 && now - chgNotChargingSince > 30 * 60_000L)
        if (ended) endCharging(now)
        else if (now - chgSavedAt > 60_000L) { saveCharging(now, live = true); chgSavedAt = now }
    }

    /** kWh de la sesión; si el coche no dio contador ni potencia, el % ganado × capacidad. */
    private fun sessionKwh(): Double {
        if (chgKwh > 0.05) return chgKwh
        val gained = (chgSocLast ?: return 0.0) - (chgSocStart ?: return 0.0)
        return if (gained > 0 && chgCapKwh != null) gained / 100.0 * chgCapKwh!! else 0.0
    }

    private fun saveCharging(now: Long, live: Boolean) {
        val id = chgId ?: return
        root.child("chargingSessions").child(id).setValue(
            mapOf(
                "start" to chgStartTs,
                "end" to if (live) null else now,
                "socStart" to chgSocStart,
                "socEnd" to chgSocLast,
                "kwh" to round1(sessionKwh()),
                "durationMin" to ((now - chgStartTs) / 60_000L).coerceAtLeast(0),
                "type" to (chgType ?: "AC"),
                "live" to if (live) true else null,
                "place" to null,
                "costEur" to null
            )
        )
    }

    private fun endCharging(now: Long) {
        val id = chgId ?: return
        val durationMin = ((now - chgStartTs) / 60_000L).coerceAtLeast(0)
        val kwh = sessionKwh()
        // Carga real: al menos 2 min o algo de energía.
        if (durationMin >= 2 || kwh >= 0.3) {
            saveCharging(now, live = false)
            Log.i(TAG, "carga guardada $id: ${round1(kwh)} kWh, ${durationMin} min")
        } else {
            root.child("chargingSessions").child(id).removeValue()
        }
        chgId = null
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
}
