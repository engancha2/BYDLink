package com.bydlink.carapp.data

import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

/**
 * Precios de la energía y coste de cada carga, con los ajustes que guarda el móvil en cars/{id}/historyCfg.
 * Es la misma cuenta que phone/history.js (si se cambia una, hay que cambiar la otra). Ver protocol/historial.md.
 */
object Tariff {
    /** Festivos nacionales de fecha fija: en la tarifa 2.0TD todo el día es valle (como los fines de semana). */
    private val HOLIDAYS = setOf("0101", "0106", "0501", "0815", "1012", "1101", "1206", "1208", "1225")

    data class Place(val name: String, val lat: Double, val lng: Double, val r: Double, val price: Double?)   // price null = tarifa de casa

    data class Cfg(
        val periods: Boolean = true, val flat: Double = 0.16,
        val p1: Double = 0.25, val p2: Double = 0.17, val p3: Double = 0.11,
        val awayAc: Double = 0.35, val dc: Double = 0.49,
        val places: List<Place> = emptyList(), val unknownAcHome: Boolean = true
    )

    private fun d(v: Any?, def: Double) = (v as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 } ?: def

    fun parse(m: Map<*, *>?): Cfg {
        if (m == null) return Cfg()
        val home = m["home"] as? Map<*, *>
        val places = (m["places"] as? List<*>)?.mapNotNull { p ->
            val o = p as? Map<*, *> ?: return@mapNotNull null
            val lat = (o["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
            val lng = (o["lng"] as? Number)?.toDouble() ?: return@mapNotNull null
            Place(o["name"] as? String ?: "Lugar", lat, lng, d(o["r"], 150.0), if (o["price"] == "home") null else d(o["price"], 0.0))
        } ?: emptyList()
        val def = Cfg()
        return Cfg(
            periods = home?.get("mode") != "flat",
            flat = d(home?.get("flat"), def.flat), p1 = d(home?.get("p1"), def.p1), p2 = d(home?.get("p2"), def.p2), p3 = d(home?.get("p3"), def.p3),
            awayAc = d(m["awayAc"], def.awayAc), dc = d(m["dc"], def.dc),
            places = places, unknownAcHome = m["unknownAc"] != "away"
        )
    }

    /** Periodo 2.0TD de una hora local "aaaammddhh": 1 punta, 2 llano, 3 valle. */
    fun period(key: String): Int {
        if (key.length < 10) return 3
        val y = key.substring(0, 4).toInt(); val mo = key.substring(4, 6).toInt(); val dd = key.substring(6, 8).toInt(); val h = key.substring(8, 10).toInt()
        val c = Calendar.getInstance().apply { clear(); set(y, mo - 1, dd) }
        val dow = c.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY || key.substring(4, 8) in HOLIDAYS) return 3
        return when (h) { in 0..7 -> 3; in 10..13, in 18..21 -> 1; else -> 2 }
    }

    fun homePrice(cfg: Cfg, key: String): Double =
        if (!cfg.periods) cfg.flat else when (period(key)) { 1 -> cfg.p1; 2 -> cfg.p2; else -> cfg.p3 }

    private fun distM(lat1: Double, lng1: Double, lat2: Double, lng2: Double) = Route.haversineKm(lat1, lng1, lat2, lng2) * 1000

    fun placeOf(cfg: Cfg, lat: Double?, lng: Double?): Place? {
        if (lat == null || lng == null) return null
        return cfg.places.filter { distM(lat, lng, it.lat, it.lng) <= it.r }.minByOrNull { distM(lat, lng, it.lat, it.lng) }
    }

    /** "home", "place", "away", "dc" o "free". */
    fun kindOf(cfg: Cfg, c: Map<*, *>, edit: Map<*, *>?): String {
        (edit?.get("kind") as? String)?.let { return it }
        if (c["type"] == "DC") return "dc"
        val lat = (c["lat"] as? Number)?.toDouble(); val lng = (c["lng"] as? Number)?.toDouble()
        placeOf(cfg, lat, lng)?.let { return if (it.price == null) "home" else "place" }
        val hasHome = cfg.places.any { it.price == null }
        return if (lat == null || !hasHome) (if (cfg.unknownAcHome) "home" else "away") else "away"
    }

    /** kWh por hora local: los que mandó el coche o, si no, repartidos por igual entre el inicio y el final. */
    fun hours(c: Map<*, *>): Map<String, Double> {
        val kwh = d(c["kwh"], 0.0)
        val h = (c["kwhH"] as? Map<*, *>)?.mapNotNull { (k, v) -> (k as? String)?.let { it to d(v, 0.0) } }?.toMap()
        if (!h.isNullOrEmpty()) {
            val sum = h.values.sum()
            return if (sum > 0 && kwh > 0) h.mapValues { it.value * kwh / sum } else h
        }
        val start = (c["start"] as? Number)?.toLong() ?: return emptyMap()
        val end = (c["end"] as? Number)?.toLong() ?: (start + ((c["durationMin"] as? Number)?.toLong() ?: 60L) * 60_000L)
        return spread(start, max(end, start + 60_000L), kwh)
    }

    private fun spread(start: Long, end: Long, kwh: Double): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        val c = Calendar.getInstance().apply { timeInMillis = start; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val total = (end - start).toDouble()
        var guard = 0
        while (c.timeInMillis < end && guard++ < 24 * 14) {
            val a = max(c.timeInMillis, start); val b = min(c.timeInMillis + 3_600_000L, end)
            val key = String.format(java.util.Locale.US, "%04d%02d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY))
            if (b > a) out[key] = kwh * (b - a) / total
            c.add(Calendar.HOUR_OF_DAY, 1)
        }
        return out
    }

    fun chargeCost(cfg: Cfg, c: Map<*, *>, edit: Map<*, *>?): Double {
        (edit?.get("costEur") as? Number)?.let { return it.toDouble() }
        if (edit?.get("kind") == null) (c["costEur"] as? Number)?.let { return it.toDouble() }
        val kwh = d(c["kwh"], 0.0)
        return when (kindOf(cfg, c, edit)) {
            "free" -> 0.0
            "dc" -> kwh * cfg.dc
            "away" -> kwh * cfg.awayAc
            "place" -> kwh * (placeOf(cfg, (c["lat"] as? Number)?.toDouble(), (c["lng"] as? Number)?.toDouble())?.price ?: cfg.awayAc)
            else -> hours(c).entries.sumOf { it.value * homePrice(cfg, it.key) }
        }
    }
}
