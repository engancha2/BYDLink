package com.bydlink.carapp.auto

import com.bydlink.carapp.model.CarState
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Lo que el motor necesita saber del coche en un momento (null = no se sabe). */
data class Facts(
    val now: Long,
    val accOn: Boolean? = null,
    val locked: Boolean? = null,
    val charging: Boolean? = null,
    val soc: Int? = null,
    val inTemp: Int? = null,
    val outTemp: Int? = null,
    val aux12v: Double? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val speed: Int? = null,
    val gear: String? = null
) {
    /** En marcha (o a punto): con el contacto quitado nunca. */
    val driving: Boolean get() = accOn != false && ((speed ?: 0) > 0 || gear == "D" || gear == "R")
    val parked: Boolean get() = accOn == false || (speed == 0 && gear == "P")

    companion object {
        /** [accOn] es el del sistema (aviso de contacto / accmodemanager); si no hay, el powerState del SDK. */
        fun from(s: CarState?, accOn: Boolean?, sdkOk: Boolean, now: Long): Facts {
            val acc = accOn ?: if (!sdkOk) false else when (s?.powerState) { "OFF" -> false; "ACC", "ON", "OK" -> true; else -> null }
            if (s == null) return Facts(now, accOn = acc)
            val loc = s.location.takeIf { it.lat != 0.0 || it.lng != 0.0 }
            return Facts(now, acc, s.locked, s.charging, s.soc?.takeIf { it in 0..100 }, s.inTemp, s.outTemp,
                s.aux12v?.takeIf { it in 5.0..20.0 }, loc?.lat, loc?.lng, s.speed, s.gear)
        }
    }
}

/** Algo que pasa de golpe (no es un estado): de momento, el centinela ve movimiento. */
data class Event(val type: String, val data: Map<String, Any?> = emptyMap())

/**
 * Memoria del motor (se guarda en el coche, sobrevive a reinicios): cuándo saltó cada regla, si un umbral o
 * una geocerca está cumplido ahora (para saltar solo al cruzarlo) y qué día saltaron las de hora.
 */
class Mem {
    val last = HashMap<String, Long>()
    val level = HashMap<String, Boolean>()
    val day = HashMap<String, String>()
    val recent = ArrayDeque<Long>()
    var dirty = false

    fun retain(rules: Collection<Rule>) {
        val ids = rules.map { it.id }.toSet(); val keys = rules.map { it.memKey }.toSet()
        if (last.keys.retainAll(ids) or day.keys.retainAll(ids) or level.keys.retainAll(keys)) dirty = true
    }

    fun toMap(): Map<String, Any?> = mapOf("last" to HashMap(last), "level" to HashMap(level), "day" to HashMap(day), "recent" to recent.toList())

    companion object {
        fun fromMap(m: Map<String, Any?>?): Mem {
            val r = Mem()
            (m?.get("last") as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v is Number) r.last[k] = v.toLong() }
            (m?.get("level") as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v is Boolean) r.level[k] = v }
            (m?.get("day") as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v is String) r.day[k] = v }
            (m?.get("recent") as? List<*>)?.forEach { if (it is Number) r.recent.addLast(it.toLong()) }
            return r
        }
    }
}

/**
 * El motor de automatizaciones, sin Android (se prueba en el PC). En cada vuelta compara el estado de antes con
 * el de ahora y devuelve las reglas que tienen que saltar. Reglas de oro:
 *  - Se salta al CAMBIAR algo (dar el contacto, cruzar un umbral, entrar en un sitio), nunca por estar así:
 *    al arrancar la app o crear la regla no salta nada que ya estuviera pasando.
 *  - Umbrales con margen (2 % de batería, 1 °C, 0,3 V, la geocerca un poco más grande al salir) para no
 *    saltar una y otra vez si el dato baila.
 *  - Cada regla espera su enfriamiento; y entre todas, como mucho [MAX_PER_HOUR] por hora.
 */
class Engine(private val zone: TimeZone = TimeZone.getDefault()) {

    data class Firing(val rule: Rule, val why: String)

    companion object {
        const val MAX_PER_HOUR = 20
        const val TIME_WINDOW_MIN = 10
    }

    fun evaluate(rules: List<Rule>, prev: Facts?, cur: Facts, events: List<Event>, mem: Mem): List<Firing> {
        val now = cur.now
        while (mem.recent.isNotEmpty() && now - mem.recent.first() > 3_600_000) { mem.recent.removeFirst(); mem.dirty = true }
        val out = ArrayList<Firing>()
        for (r in rules) {
            val why = triggered(r, prev, cur, events, mem) ?: continue   // los umbrales se actualizan aunque esté apagada
            if (!r.on) continue
            if (!r.only.all { holds(it, cur) }) continue
            val last = mem.last[r.id]
            if (last != null && now - last < r.cooldownMin * 60_000L) continue
            if (mem.recent.size >= MAX_PER_HOUR) continue
            mem.last[r.id] = now; mem.recent.addLast(now); mem.dirty = true
            if (r.trigger.type == "time") mem.day[r.id] = dateOf(now)
            out += Firing(r, why)
        }
        return out
    }

    /** Si el disparador de la regla ha saltado en esta vuelta, por qué (texto para el registro); si no, null. */
    private fun triggered(r: Rule, prev: Facts?, cur: Facts, events: List<Event>, mem: Mem): String? {
        val p = r.trigger.p
        return when (r.trigger.type) {
            "acc" -> {
                val want = p.bool("on") ?: false
                if (edge(prev?.accOn, cur.accOn, want)) (if (want) "Has dado el contacto" else "Has apagado el coche") else null
            }
            "lock" -> {
                val want = p.bool("locked") ?: true
                if (edge(prev?.locked, cur.locked, want)) (if (want) "Has cerrado el coche" else "Has abierto el coche") else null
            }
            "charge" -> {
                val want = p.bool("charging") ?: true
                if (edge(prev?.charging, cur.charging, want)) (if (want) "Ha empezado la carga" else "Ha terminado la carga") else null
            }
            "soc" -> threshold(r, cur.soc?.toDouble(), p, 2.0, mem)?.let {
                if (p.str("op") == "above") "La batería ha llegado al ${cur.soc} %" else "La batería ha bajado al ${cur.soc} %"
            }
            "temp" -> {
                val inside = p.str("where") == "in"
                val v = if (inside) cur.inTemp else cur.outTemp
                threshold(r, v?.toDouble(), p, 1.0, mem)?.let { (if (inside) "Dentro" else "Fuera") + " hace $v °C" }
            }
            "aux12v" -> threshold(r, cur.aux12v, p + ("op" to "below"), 0.3, mem)?.let {
                "La batería de 12 V está a ${"%.1f".format(cur.aux12v)} V"
            }
            "place" -> {
                val d = distance(cur, p) ?: return null
                val radius = (p.num("radius") ?: 200.0).coerceIn(50.0, 50_000.0)
                val was = mem.level[r.memKey]
                val inside = if (was == true) d <= radius + maxOf(50.0, radius * 0.2) else d <= radius
                if (was != inside) { mem.level[r.memKey] = inside; mem.dirty = true }
                val label = p.str("label")?.ifBlank { null } ?: "el lugar elegido"
                val arrive = p.str("event") != "leave"
                when {
                    was == null || was == inside -> null
                    arrive && inside -> "El coche ha llegado a $label"
                    !arrive && !inside -> "El coche ha salido de $label"
                    else -> null
                }
            }
            "time" -> {
                val at = minutesOf(p.str("at")) ?: return null
                val c = cal(cur.now)
                val days = p.days()
                if (days.isNotEmpty() && isoDay(c) !in days) return null
                val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
                if (m < at || m >= at + TIME_WINDOW_MIN || mem.day[r.id] == dateOf(cur.now)) null
                else "Son las " + p.str("at")
            }
            "sentry" -> if (events.any { it.type == "sentry" }) "El centinela ha visto movimiento" else null
            else -> null
        }
    }

    private fun edge(before: Boolean?, now: Boolean?, want: Boolean) = before != null && now != null && before != now && now == want

    /**
     * Umbral con margen: "below" se cumple al llegar a [value] o menos y deja de cumplirse al subir [hyst] por
     * encima; "above" al revés. Salta solo al pasar de no cumplido a cumplido (no la primera vez que se mira).
     */
    private fun threshold(r: Rule, v: Double?, p: Map<String, Any?>, hyst: Double, mem: Mem): Unit? {
        v ?: return null
        val x = p.num("value") ?: return null
        val below = p.str("op") != "above"
        val was = mem.level[r.memKey]
        val now = if (was == true) { if (below) v < x + hyst else v > x - hyst } else { if (below) v <= x else v >= x }
        if (was != now) { mem.level[r.memKey] = now; mem.dirty = true }
        return if (was == false && now) Unit else null
    }

    /** ¿Se cumple la condición "solo si"? Si falta el dato, no (mejor no hacer nada que hacerlo a ciegas). */
    fun holds(c: Cond, f: Facts): Boolean {
        val p = c.p
        return when (c.type) {
            "time" -> {
                val from = minutesOf(p.str("from")) ?: return false
                val to = minutesOf(p.str("to")) ?: return false
                val cl = cal(f.now)
                val days = p.days()
                if (days.isNotEmpty() && isoDay(cl) !in days) return false
                val m = cl.get(Calendar.HOUR_OF_DAY) * 60 + cl.get(Calendar.MINUTE)
                if (from <= to) m in from until to else m >= from || m < to
            }
            "soc" -> compare(f.soc?.toDouble(), p)
            "temp" -> compare((if (p.str("where") == "in") f.inTemp else f.outTemp)?.toDouble(), p)
            "place" -> {
                val d = distance(f, p) ?: return false
                val inside = d <= (p.num("radius") ?: 200.0).coerceIn(50.0, 50_000.0)
                inside == (p.bool("inside") ?: true)
            }
            "state" -> {
                val want = p.bool("value") ?: true
                val v = when (p.str("key")) {
                    "parked" -> f.parked
                    "charging" -> f.charging
                    "locked" -> f.locked
                    "accOn" -> f.accOn
                    else -> null
                }
                v != null && v == want
            }
            else -> false
        }
    }

    private fun compare(v: Double?, p: Map<String, Any?>): Boolean {
        v ?: return false
        val x = p.num("value") ?: return false
        return if (p.str("op") == "above") v >= x else v <= x
    }

    /** Metros desde el coche al lugar de la regla (null si falta la posición de alguno). */
    private fun distance(f: Facts, p: Map<String, Any?>): Double? {
        val lat = f.lat ?: return null; val lng = f.lng ?: return null
        val la = p.num("lat") ?: return null; val lo = p.num("lng") ?: return null
        return meters(lat, lng, la, lo)
    }

    private fun cal(ts: Long) = Calendar.getInstance(zone).apply { timeInMillis = ts }
    private fun isoDay(c: Calendar) = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1   // lunes = 1 … domingo = 7
    private fun dateOf(ts: Long): String = cal(ts).let { "%04d-%02d-%02d".format(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) }
}

/** Distancia en metros entre dos puntos (haversine). */
fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/**
 * Qué puede hacer una automatización. Lista CERRADA: nada que mueva el coche, lo apague, cambie su modo de
 * conducción o abra puertas/maletero/ventanas por sí solo; y lo que podría molestar o sorprender (luces,
 * claxon, abrir por la nube, fotos) solo con el coche aparcado.
 */
object Policy {
    val ALLOWED = setOf(
        "notify",
        "setClimate", "setDefrost", "setSeatComfort", "setSeatHeat", "setSeatVent", "batteryPreheat", "setMirrorHeat",
        "setChargeLimit",
        "setSentryConfig", "recordVideo", "stopVideo", "takePhoto", "setVideoConfig",
        "lock", "setLock", "closeWindows", "flashLights", "locateCar", "setInteriorLight"
    )
    private val PARKED_ONLY = setOf("flashLights", "locateCar", "takePhoto", "setInteriorLight", "closeWindows")

    /** null si se puede hacer ahora; si no, el motivo (va al registro). */
    fun check(a: Act, f: Facts): String? {
        if (a.action !in ALLOWED) return "Esta orden no se puede usar en una automatización."
        if (a.action == "setLock" && a.payload["locked"] == false) return "Una automatización no puede abrir el coche."
        if (a.action in PARKED_ONLY && !f.parked) return "No se hace con el coche en marcha."
        if (a.action == "recordVideo" && ((a.payload.num("seconds") ?: 60.0) > 300 || (a.payload.num("seconds") ?: 60.0) <= 0))
            return "Una automatización graba como mucho 5 minutos."
        return null
    }

    /** Texto corto de una orden para el registro (sin jerga). */
    fun label(a: Act): String = when (a.action) {
        "notify" -> "Avisar al móvil"
        "setClimate" -> if (a.payload["on"] == false) "Apagar el aire" else "Encender el aire" + (a.payload.num("temp")?.let { " a ${it.roundToInt()}°" } ?: "")
        "setSentryConfig" -> if (a.payload["on"] == false) "Desactivar el centinela" else "Activar el centinela"
        "recordVideo" -> "Grabar vídeo"
        "takePhoto" -> "Hacer una foto"
        "setVideoConfig" -> "Cambiar la grabación continua"
        "setChargeLimit" -> "Límite de carga al ${a.payload.num("percent")?.roundToInt() ?: "?"} %"
        "lock", "setLock" -> "Cerrar el coche"
        else -> a.action
    }
}
