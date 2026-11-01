package com.bydlink.carapp.data

import android.content.Context
import com.bydlink.carapp.App
import com.bydlink.carapp.model.CarState
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Grabador de VIAJES y CARGAS. El servicio llama [onState] en cada publicación de telemetría (cada ~2 s) y
 * [onSilence] cuando el coche duerme y no contesta. Formato en Firebase (detalle en protocol/historial.md):
 *
 *   cars/{id}/trips/{tripId}            = {start, end, distanceKm, durationSec, startAddr, endAddr, + datos v2}
 *   cars/{id}/tripPoints/{tripId}/{i}   = {lat, lng, t, speed}      (como siempre: lo usan los móviles viejos)
 *   cars/{id}/tripRoutes/{tripId}       = polilínea codificada y simplificada (ver [Route])
 *   cars/{id}/chargingSessions/{id}     = {start, end, socStart, socEnd, kwh, durationMin, type, place, costEur, + v2}
 *
 * Lo que va en curso se guarda en preferencias: al quitar el contacto BYD mata la app y el vigilante la vuelve a
 * abrir; así el viaje o la carga siguen donde estaban (antes se perdía el final del viaje, que es justo cuando
 * se apaga el coche). Los precios y el coste NO se calculan aquí: se guardan los hechos (kWh por hora, lugar,
 * AC/DC) y el móvil aplica la tarifa.
 */
class Recorder internal constructor(private val out: Out, private val mem: Mem, private val log: (String) -> Unit) {

    constructor(ctx: Context, carId: String) : this(FirebaseOut(carId), PrefsMem(ctx), { android.util.Log.i(TAG, it) })

    /** Dónde se escribe (la nube) y dónde se guarda lo que va en curso (el coche). Separado para probarlo en el PC. */
    internal interface Out { fun set(path: String, v: Any?); fun remove(path: String); fun newKey(path: String): String }
    internal interface Mem { fun get(k: String): String?; fun put(k: String, v: String?); fun getF(k: String): Float; fun putF(k: String, v: Float) }

    private companion object {
        const val TAG = "BYDLink"
        const val END_IDLE_MS = 3 * 60_000L      // parado y aparcado este tiempo → fin de viaje
        const val END_STILL_MS = 10 * 60_000L    // parado (sin saber si en P) este tiempo → fin de viaje
        const val STALE_MS = 15 * 60_000L        // al volver, un viaje sin noticias desde hace esto se cierra
        const val CHG_GAP_MS = 30 * 60_000L      // pausa máxima dentro de una misma carga
        const val POINT_MIN_MS = 12_000L         // un punto GPS cada ~12 s…
        const val POINT_MIN_M = 100.0            // …o cada ~100 m
        const val MAX_POINTS = 2000
        const val ROUTE_KEEP = 1500              // puntos de ruta en memoria (se diezma si pasa)
        const val LIVE_MS = 60_000L              // guardar lo que va en curso en la nube
        const val PERSIST_MS = 20_000L           // y en el propio coche
        const val VERSION = 2
    }

    private val hourFmt = SimpleDateFormat("yyyyMMddHH", Locale.US)
    private var loaded = false

    /** Viaje en curso. Todo lo necesario para cerrarlo aunque la app se reinicie a mitad. */
    private class Trip(val id: String, val start: Long) {
        var km = 0.0; var pointIdx = 0
        var lastLat = 0.0; var lastLng = 0.0; var lastPointTs = 0L
        var stoppedSince = 0L; var lastTs = 0L; var lastMoveTs = 0L; var driveMs = 0L
        var soc0: Int? = null; var soc1: Int? = null
        var left0: Double? = null; var left1: Double? = null
        var tot0: Double? = null; var tot1: Double? = null
        var odo0: Int? = null; var odo1: Int? = null
        var maxKmh = 0; var tempSum = 0.0; var tempN = 0
        var lat0: Double? = null; var lng0: Double? = null
        val route = ArrayList<DoubleArray>()
        var liveAt = 0L; var savedAt = 0L

        fun json(): JSONObject = JSONObject().apply {
            put("id", id); put("start", start); put("km", km); put("pointIdx", pointIdx)
            put("lastLat", lastLat); put("lastLng", lastLng); put("lastPointTs", lastPointTs)
            put("stoppedSince", stoppedSince); put("lastTs", lastTs); put("lastMoveTs", lastMoveTs); put("driveMs", driveMs)
            putOpt("soc0", soc0); putOpt("soc1", soc1); putOpt("left0", left0); putOpt("left1", left1)
            putOpt("tot0", tot0); putOpt("tot1", tot1); putOpt("odo0", odo0); putOpt("odo1", odo1)
            put("maxKmh", maxKmh); put("tempSum", tempSum); put("tempN", tempN); putOpt("lat0", lat0); putOpt("lng0", lng0)
            put("route", JSONArray().also { a -> route.forEach { p -> a.put(p[0]); a.put(p[1]) } })
        }

        companion object {
            fun of(o: JSONObject) = Trip(o.getString("id"), o.getLong("start")).apply {
                km = o.optDouble("km", 0.0); pointIdx = o.optInt("pointIdx")
                lastLat = o.optDouble("lastLat", 0.0); lastLng = o.optDouble("lastLng", 0.0); lastPointTs = o.optLong("lastPointTs")
                stoppedSince = o.optLong("stoppedSince"); lastTs = o.optLong("lastTs"); lastMoveTs = o.optLong("lastMoveTs"); driveMs = o.optLong("driveMs")
                soc0 = o.optIntOrNull("soc0"); soc1 = o.optIntOrNull("soc1"); left0 = o.optDoubleOrNull("left0"); left1 = o.optDoubleOrNull("left1")
                tot0 = o.optDoubleOrNull("tot0"); tot1 = o.optDoubleOrNull("tot1"); odo0 = o.optIntOrNull("odo0"); odo1 = o.optIntOrNull("odo1")
                maxKmh = o.optInt("maxKmh"); tempSum = o.optDouble("tempSum", 0.0); tempN = o.optInt("tempN")
                lat0 = o.optDoubleOrNull("lat0"); lng0 = o.optDoubleOrNull("lng0")
                o.optJSONArray("route")?.let { a -> var i = 0; while (i + 1 < a.length()) { route.add(doubleArrayOf(a.getDouble(i), a.getDouble(i + 1))); i += 2 } }
            }
        }
    }

    /** Carga en curso: de enchufar a desenchufar (las pausas del BMS no la cortan). */
    private class Chg(val id: String, val start: Long) {
        var soc0: Int? = null; var socLast: Int? = null
        var kwhBase = 0.0; var counter: Double? = null; var kwhPow = 0.0
        var type: String? = null; var lastTs = 0L; var notChargingSince = 0L
        var maxKw = 0.0; var chargingMs = 0L
        var lat: Double? = null; var lng: Double? = null
        val kwhH = LinkedHashMap<String, Double>(); val secH = LinkedHashMap<String, Long>()
        var liveAt = 0L; var savedAt = 0L

        fun json(): JSONObject = JSONObject().apply {
            put("id", id); put("start", start); putOpt("soc0", soc0); putOpt("socLast", socLast)
            put("kwhBase", kwhBase); putOpt("counter", counter); put("kwhPow", kwhPow); putOpt("type", type)
            put("lastTs", lastTs); put("notChargingSince", notChargingSince); put("maxKw", maxKw); put("chargingMs", chargingMs)
            putOpt("lat", lat); putOpt("lng", lng)
            put("kwhH", JSONObject(kwhH.toMap())); put("secH", JSONObject(secH.toMap()))
        }

        companion object {
            fun of(o: JSONObject) = Chg(o.getString("id"), o.getLong("start")).apply {
                soc0 = o.optIntOrNull("soc0"); socLast = o.optIntOrNull("socLast")
                kwhBase = o.optDouble("kwhBase", 0.0); counter = o.optDoubleOrNull("counter"); kwhPow = o.optDouble("kwhPow", 0.0)
                type = o.optString("type").takeIf { it.isNotEmpty() }
                lastTs = o.optLong("lastTs"); notChargingSince = o.optLong("notChargingSince")
                maxKw = o.optDouble("maxKw", 0.0); chargingMs = o.optLong("chargingMs")
                lat = o.optDoubleOrNull("lat"); lng = o.optDoubleOrNull("lng")
                o.optJSONObject("kwhH")?.let { m -> m.keys().forEach { k -> kwhH[k] = m.optDouble(k, 0.0) } }
                o.optJSONObject("secH")?.let { m -> m.keys().forEach { k -> secH[k] = m.optLong(k) } }
            }
        }
    }

    private var trip: Trip? = null
    private var chg: Chg? = null

    fun onState(s: CarState) {
        val now = if (s.ts > 0) s.ts else System.currentTimeMillis()
        load(now)
        try { trips(s, now) } catch (e: Throwable) { log("recorder viaje: $e") }
        try { charging(s, now) } catch (e: Throwable) { log("recorder carga: $e") }
    }

    /** El coche no contesta (dormido): lo que llevaba demasiado sin noticias se da por terminado. */
    fun onSilence(now: Long = System.currentTimeMillis()) {
        load(now)
        trip?.let { t -> if (now - t.lastTs >= END_IDLE_MS) { try { endTrip(t.stoppedSince.takeIf { it > 0 } ?: t.lastTs) } catch (e: Throwable) { log("recorder viaje: $e") } } }
        chg?.let { c -> if (now - c.lastTs >= CHG_GAP_MS) { try { endCharging(c.lastTs) } catch (e: Throwable) { log("recorder carga: $e") } } }
    }

    /** Recupera lo que iba en curso antes de reiniciarse la app (y cierra lo que ya es viejo). */
    private fun load(now: Long) {
        if (loaded) return
        loaded = true
        try {
            mem.get("trip")?.let { trip = Trip.of(JSONObject(it)) }
            trip?.let { t -> if (now - t.lastTs > STALE_MS) endTrip(t.stoppedSince.takeIf { it > 0 } ?: t.lastTs) else log("viaje recuperado ${t.id}") }
        } catch (e: Throwable) { log("recorder: viaje guardado ilegible: $e"); trip = null; mem.put("trip", null) }
        try {
            mem.get("chg")?.let { chg = Chg.of(JSONObject(it)) }
            chg?.let { c -> if (now - c.lastTs > CHG_GAP_MS) endCharging(c.lastTs) else log("carga recuperada ${c.id}") }
        } catch (e: Throwable) { log("recorder: carga guardada ilegible: $e"); chg = null; mem.put("chg", null) }
    }

    // ======================= VIAJES =======================
    private fun trips(s: CarState, now: Long) {
        val loc = s.location
        val hasLoc = loc.lat != 0.0 || loc.lng != 0.0
        rememberCapacity(s)
        var t = trip
        if (t == null) {
            if (s.speed in 3..300) t = startTrip(s, now) else return
        }

        val dt = (now - t.lastTs).takeIf { t.lastTs > 0 && it in 1..15_000 } ?: 0L
        t.lastTs = now
        if (s.speed > 0) { t.driveMs += dt; t.lastMoveTs = now }
        if (s.speed in 1..300) t.maxKmh = maxOf(t.maxKmh, s.speed)
        s.soc?.let { t.soc1 = it; if (t.soc0 == null) t.soc0 = it }
        s.energyLeftKwh?.let { t.left1 = it; if (t.left0 == null) t.left0 = it }
        s.totalEnergyKwh?.let { t.tot1 = it; if (t.tot0 == null) t.tot0 = it }
        s.odometer?.let { t.odo1 = it; if (t.odo0 == null) t.odo0 = it }
        s.outTemp?.takeIf { it in -40..60 && s.speed > 0 }?.let { t.tempSum += it; t.tempN++ }

        if (hasLoc) {
            if (t.lat0 == null) { t.lat0 = loc.lat; t.lng0 = loc.lng }
            val d = if (t.lastLat != 0.0 || t.lastLng != 0.0) Route.haversineKm(t.lastLat, t.lastLng, loc.lat, loc.lng) else 0.0
            val farEnough = d * 1000.0 >= POINT_MIN_M
            val longEnough = now - t.lastPointTs >= POINT_MIN_MS
            if ((farEnough || longEnough || t.lastPointTs == 0L) && t.pointIdx < MAX_POINTS) {
                // Ignora saltos GPS absurdos y el baile del GPS con el coche parado.
                if (d.isFinite() && d < 5.0 && (s.speed > 0 || d * 1000.0 > 30.0)) t.km += d
                writePoint(t, loc.lat, loc.lng, now, s.speed)
            }
        }

        val parked = s.gear == "P" || s.powerState == "OFF" || s.parkBrake == true
        if (s.speed > 0) {
            t.stoppedSince = 0L
        } else {
            if (t.stoppedSince == 0L) { t.stoppedSince = now; persist(now, force = true) }   // al parar puede llegar el apagado
            val idle = now - t.stoppedSince
            if ((parked && idle >= END_IDLE_MS) || idle >= END_STILL_MS) { endTrip(t.stoppedSince); return }
        }
        if (now - t.liveAt >= LIVE_MS) { t.liveAt = now; writeTrip(t, now, live = true) }
        persist(now)
    }

    private fun startTrip(s: CarState, now: Long): Trip {
        val id = out.newKey("trips")
        val t = Trip(id, now)
        t.lastTs = now; t.lastMoveTs = now
        trip = t
        log("viaje iniciado $id")
        return t
    }

    /** Cierra el viaje. [end] = cuando se paró de verdad (no 3 minutos después). */
    private fun endTrip(end: Long) {
        val t = trip ?: return
        trip = null
        mem.put("trip", null)
        val endTs = if (end > t.start) end else t.lastTs
        val km = distance(t)
        val durationSec = ((endTs - t.start) / 1000L).coerceAtLeast(0)
        // Viajes de broma (sin recorrido real): se descartan para no ensuciar la lista.
        if (km >= 0.2 && durationSec >= 60) {
            writeTrip(t, endTs, live = false)
            if (t.route.size >= 2) out.set("tripRoutes/${t.id}", Route.encode(Route.simplify(t.route)))
            log("viaje guardado ${t.id}: ${round1(km)} km, ${durationSec}s")
        } else {
            out.remove("trips/${t.id}")
            out.remove("tripPoints/${t.id}")
            out.remove("tripRoutes/${t.id}")
        }
    }

    /** Distancia: la del GPS, salvo que el cuentakilómetros diga claramente más (túneles, GPS perdido). */
    private fun distance(t: Trip): Double {
        val dOdo = if (t.odo0 != null && t.odo1 != null) (t.odo1!! - t.odo0!!).toDouble() else null
        return when {
            dOdo != null && dOdo in 1.0..2000.0 && t.km < 0.2 -> dOdo
            dOdo != null && dOdo in 2.0..2000.0 && t.km < dOdo * 0.75 -> dOdo
            else -> t.km
        }
    }

    /** Energía gastada: contador del coche, energía de la batería o % × capacidad (lo primero que tenga sentido). */
    private fun tripEnergy(t: Trip, km: Double): Pair<Double, String>? {
        fun ok(v: Double?) = v != null && v > 0.05 && v < km * 0.6 + 2
        val dTot = if (t.tot0 != null && t.tot1 != null) t.tot1!! - t.tot0!! else null
        val dLeft = if (t.left0 != null && t.left1 != null) t.left0!! - t.left1!! else null
        if (ok(dTot) && dTot!! >= 2) return dTot to "contador"
        if (ok(dLeft)) return dLeft!! to "bateria"
        if (ok(dTot)) return dTot!! to "contador"
        val dSoc = if (t.soc0 != null && t.soc1 != null) t.soc0!! - t.soc1!! else null
        val cap = capacity()
        if (dSoc != null && dSoc > 0 && cap != null) return dSoc / 100.0 * cap to "soc"
        return null
    }

    private fun writeTrip(t: Trip, now: Long, live: Boolean) {
        val km = distance(t)
        val en = tripEnergy(t, km)
        val driveH = t.driveMs / 3_600_000.0
        out.set("trips/${t.id}",
            mapOf(
                "start" to t.start,
                "end" to if (live) null else now,
                "distanceKm" to round1(km),
                "durationSec" to ((now - t.start) / 1000L).coerceAtLeast(0),
                "startAddr" to null,
                "endAddr" to null,
                "live" to if (live) true else null,
                "v" to VERSION,
                "driveSec" to t.driveMs / 1000L,
                "avgKmh" to if (driveH > 0.01 && km > 0) round1(km / driveH) else null,
                "maxKmh" to t.maxKmh.takeIf { it > 0 },
                "socStart" to t.soc0, "socEnd" to t.soc1,
                "kwh" to en?.first?.let { round2(it) }, "kwhSrc" to en?.second,
                "kwh100" to if (en != null && km >= 1) round1(en.first / km * 100) else null,
                "outTemp" to if (t.tempN > 0) Math.round(t.tempSum / t.tempN).toInt() else null,
                "odoStart" to t.odo0, "odoEnd" to t.odo1,
                "startLat" to t.lat0?.let { round5(it) }, "startLng" to t.lng0?.let { round5(it) },
                "endLat" to t.lastLat.takeIf { it != 0.0 }?.let { round5(it) }, "endLng" to t.lastLng.takeIf { it != 0.0 }?.let { round5(it) }
            )
        )
    }

    private fun writePoint(t: Trip, lat: Double, lng: Double, ts: Long, speed: Int) {
        out.set("tripPoints/${t.id}/${t.pointIdx}", mapOf("lat" to lat, "lng" to lng, "t" to ts, "speed" to speed))
        t.pointIdx++
        t.lastLat = lat; t.lastLng = lng; t.lastPointTs = ts
        t.route.add(doubleArrayOf(lat, lng))
        if (t.route.size > ROUTE_KEEP) {   // viaje muy largo: se queda uno de cada dos (la forma se mantiene)
            val keep = t.route.filterIndexed { i, _ -> i % 2 == 0 || i == t.route.size - 1 }
            t.route.clear(); t.route.addAll(keep)
        }
    }

    // ======================= CARGAS =======================
    // Energía: el contador de la propia sesión del coche (chargedKwh); si no lo hay, kW × tiempo; y si tampoco, el %
    // ganado × la capacidad. Se reparte por horas (kwhH) para poder aplicar la tarifa por horas en el móvil.
    private fun plugged(s: CarState) = s.charging || (s.chargingGun != null && s.chargingGun != "none")

    private fun charging(s: CarState, now: Long) {
        var c = chg
        if (c != null && now - c.lastTs > CHG_GAP_MS) { endCharging(c.lastTs); c = null }
        if (c == null) {
            if (!s.charging) return
            val id = out.newKey("chargingSessions")
            c = Chg(id, now).apply { soc0 = s.soc; lastTs = now }
            chg = c
            log("carga iniciada $id")
        }
        val dt = (now - c.lastTs).coerceIn(0L, 60_000L)   // más de un minuto sin lecturas: la app estaba parada
        c.lastTs = now
        if (c.lat == null && (s.location.lat != 0.0 || s.location.lng != 0.0)) { c.lat = round5(s.location.lat); c.lng = round5(s.location.lng) }
        if (s.charging) {
            c.notChargingSince = 0L
            c.chargingMs += dt
            val hour = hourFmt.format(Date(now))
            c.secH[hour] = (c.secH[hour] ?: 0L) + dt / 1000L
            val counter = s.chargedKwh
            if (counter != null) {
                val prev = c.counter
                if (prev != null && counter < prev - 0.5) c.kwhBase += prev   // el coche reinició su contador
                val d = if (prev == null || counter < prev - 0.5) counter else counter - prev
                if (d > 0 && d < 60) c.kwhH[hour] = (c.kwhH[hour] ?: 0.0) + d
                c.counter = if (prev == null || counter < prev - 0.5) counter else maxOf(prev, counter)
            }
            // kW × tiempo siempre: sirve para comprobar el contador del coche (en el Surf se queda corto).
            val kw = s.chargeKw ?: 0.0
            if (kw > 0) { val e = kw * dt / 3_600_000.0; c.kwhPow += e; if (counter == null) c.kwhH[hour] = (c.kwhH[hour] ?: 0.0) + e }
            (s.chargeKw ?: s.chargingPower?.toDouble())?.takeIf { it in 0.1..400.0 }?.let { c.maxKw = maxOf(c.maxKw, it) }
            if (c.type == null) c.type = s.chargingGun?.takeIf { it == "AC" || it == "DC" }
                ?: (s.chargeKw ?: 0.0).takeIf { it > 15 }?.let { "DC" }
        } else if (c.notChargingSince == 0L) c.notChargingSince = now
        s.soc?.let { c.socLast = it; if (c.soc0 == null) c.soc0 = it }
        rememberCapacity(s)
        val ended = !plugged(s) || (c.notChargingSince > 0 && now - c.notChargingSince > CHG_GAP_MS)
        if (ended) { endCharging(now); return }
        if (now - c.liveAt > LIVE_MS) { c.liveAt = now; saveCharging(c, now, live = true) }
        persist(now)
    }

    /**
     * kWh de la sesión. Hay tres medidas: el contador del coche, kW × tiempo y el % ganado × capacidad. El contador del
     * Surf se queda corto (noche del 31/10: 8,3 kWh para 69→99 % de 41 kWh ≈ 12,3), así que el % ganado manda cuando es
     * fiable (≥ 3 %) y las otras dos solo se usan si se parecen a él.
     */
    private fun sessionKwh(c: Chg): Double {
        val counterK = c.kwhBase + (c.counter ?: 0.0)
        val powK = c.kwhPow
        val gained = (c.socLast ?: 0) - (c.soc0 ?: c.socLast ?: 0)
        val socK = capacity()?.takeIf { gained >= 3 }?.let { gained / 100.0 * it }
        fun near(v: Double, ref: Double, tol: Double) = v > 0.05 && Math.abs(v - ref) <= ref * tol
        if (socK != null) return when {
            near(counterK, socK, 0.15) -> counterK
            near(powK, socK, 0.2) -> powK
            else -> socK
        }
        return when {
            counterK > 0.05 -> counterK
            powK > 0.05 -> powK
            else -> capacity()?.takeIf { gained > 0 }?.let { gained / 100.0 * it } ?: 0.0
        }
    }

    /** kWh por hora local (aaaammddhh). Si el contador no llegó a repartir casi nada, se reparte por el tiempo cargando. */
    private fun hourly(c: Chg, kwh: Double): Map<String, Double>? {
        if (kwh <= 0.05) return null
        val sum = c.kwhH.values.sum()
        val secs = c.secH.values.sum()
        val m = if (sum >= kwh * 0.8 || secs <= 0) c.kwhH.toMap() else c.secH.mapValues { kwh * it.value / secs }
        val scale = if (m.values.sum() > 0) kwh / m.values.sum() else 1.0
        return m.mapValues { round2(it.value * scale) }.filterValues { it > 0 }.takeIf { it.isNotEmpty() }
    }

    private fun saveCharging(c: Chg, now: Long, live: Boolean) {
        val kwh = sessionKwh(c)
        val chH = c.chargingMs / 3_600_000.0
        out.set("chargingSessions/${c.id}",
            mapOf(
                "start" to c.start,
                "end" to if (live) null else now,
                "socStart" to c.soc0,
                "socEnd" to c.socLast,
                "kwh" to round1(kwh),
                "durationMin" to ((now - c.start) / 60_000L).coerceAtLeast(0),
                "type" to (c.type ?: "AC"),
                "live" to if (live) true else null,
                "place" to null,
                "costEur" to null,
                "v" to VERSION,
                "chargeMin" to c.chargingMs / 60_000L,
                "maxKw" to c.maxKw.takeIf { it > 0 }?.let { round1(it) },
                "avgKw" to if (chH > 0.02 && kwh > 0) round1(kwh / chH) else null,
                "lat" to c.lat, "lng" to c.lng,
                "kwhH" to hourly(c, kwh)
            )
        )
    }

    private fun endCharging(now: Long) {
        val c = chg ?: return
        chg = null
        mem.put("chg", null)
        val durationMin = ((now - c.start) / 60_000L).coerceAtLeast(0)
        val kwh = sessionKwh(c)
        // Carga real: al menos 2 min o algo de energía.
        if (durationMin >= 2 || kwh >= 0.3) {
            saveCharging(c, now, live = false)
            log("carga guardada ${c.id}: ${round1(kwh)} kWh, ${durationMin} min")
        } else {
            out.remove("chargingSessions/${c.id}")
        }
    }

    // ======================= comunes =======================
    /** Capacidad útil de la batería, deducida de la energía restante y el %. Se recuerda entre reinicios. */
    private fun rememberCapacity(s: CarState) {
        val soc = s.soc ?: return; val left = s.energyLeftKwh ?: return
        if (soc < 40) return   // con poca batería el % entero da una capacidad poco precisa
        val cap = left / (soc / 100.0)
        if (cap in 10.0..200.0 && kotlin.math.abs(cap - (capacity() ?: 0.0)) > 0.3) mem.putF("capKwh", cap.toFloat())
    }

    private fun capacity(): Double? = mem.getF("capKwh").takeIf { it > 0 }?.toDouble()

    private fun persist(now: Long, force: Boolean = false) {
        trip?.let { t -> if (force || now - t.savedAt >= PERSIST_MS) { t.savedAt = now; mem.put("trip", t.json().toString()) } }
        chg?.let { c -> if (force || now - c.savedAt >= PERSIST_MS) { c.savedAt = now; mem.put("chg", c.json().toString()) } }
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    private fun round5(v: Double): Double = Math.round(v * 1e5) / 1e5
}

private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null

/** Escritura real: Firebase, bajo cars/{id}. */
private class FirebaseOut(carId: String) : Recorder.Out {
    private val root = App.db.getReference("cars").child(carId)
    override fun set(path: String, v: Any?) { root.child(path).setValue(v) }
    override fun remove(path: String) { root.child(path).removeValue() }
    override fun newKey(path: String): String = root.child(path).push().key ?: "k${System.currentTimeMillis()}"
}

/** Lo que va en curso, en las preferencias del coche ("recorder"). */
private class PrefsMem(ctx: Context) : Recorder.Mem {
    private val p = ctx.getSharedPreferences("recorder", Context.MODE_PRIVATE)
    override fun get(k: String): String? = p.getString(k, null)
    override fun put(k: String, v: String?) { if (v == null) p.edit().remove(k).apply() else p.edit().putString(k, v).apply() }
    override fun getF(k: String): Float = p.getFloat(k, 0f)
    override fun putF(k: String, v: Float) { p.edit().putFloat(k, v).apply() }
}
