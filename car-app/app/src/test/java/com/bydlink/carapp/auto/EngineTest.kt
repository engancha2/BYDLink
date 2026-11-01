package com.bydlink.carapp.auto

import com.bydlink.carapp.model.CarState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class EngineTest {

    private val tz = TimeZone.getTimeZone("Europe/Madrid")
    private val engine = Engine(tz)
    private val notify = listOf(mapOf("action" to "notify", "payload" to mapOf("text" to "hola")))

    private fun rule(whenMap: Map<String, Any?>, only: List<Map<String, Any?>> = emptyList(), cooldown: Int = 5, on: Boolean = true, id: String = "r1") =
        Rule.parse(id, mapOf("name" to "Prueba", "on" to on, "when" to whenMap, "only" to only, "do" to notify, "cooldownMin" to cooldown))!!

    /** 1 nov 2026 (domingo) a la hora dada, en Madrid. */
    private fun at(h: Int, m: Int, day: Int = 1): Long = Calendar.getInstance(tz).apply {
        clear(); set(2026, Calendar.NOVEMBER, day, h, m, 0)
    }.timeInMillis

    private fun run(r: Rule, vararg facts: Facts, mem: Mem = Mem(), events: List<Event> = emptyList()): List<Int> {
        val fired = ArrayList<Int>()
        var prev: Facts? = null
        facts.forEachIndexed { i, f ->
            if (engine.evaluate(listOf(r), prev, f, events, mem).isNotEmpty()) fired += i
            prev = f
        }
        return fired
    }

    @Test fun reglaMalEscritaSeDescarta() {
        assertNull(Rule.parse("x", mapOf("when" to mapOf("type" to "nada"), "do" to notify)))
        assertNull(Rule.parse("x", mapOf("when" to mapOf("type" to "acc"), "do" to emptyList<Any>())))
        assertNull(Rule.parse("x", null))
        // Firebase puede devolver las listas como mapa {"0":…}
        val r = Rule.parse("x", mapOf("when" to mapOf("type" to "acc", "on" to false), "do" to mapOf("0" to notify[0])))
        assertEquals(1, r!!.acts.size)
        assertEquals(5, r.cooldownMin)
    }

    @Test fun alApagarElCoche() {
        val r = rule(mapOf("type" to "acc", "on" to false))
        val t = at(10, 0)
        // al arrancar (sin estado anterior) no salta aunque ya esté apagado; salta al pasar de encendido a apagado
        assertEquals(listOf(2), run(r, Facts(t, accOn = false), Facts(t + 5000, accOn = true), Facts(t + 10000, accOn = false), Facts(t + 15000, accOn = false)))
    }

    @Test fun sinDatoNoSalta() {
        val r = rule(mapOf("type" to "lock", "locked" to true))
        val t = at(10, 0)
        assertEquals(emptyList<Int>(), run(r, Facts(t, locked = false), Facts(t + 1, locked = null), Facts(t + 2, locked = true)))
    }

    @Test fun bateriaConMargen() {
        val r = rule(mapOf("type" to "soc", "op" to "below", "value" to 20), cooldown = 1)
        val t = at(10, 0)
        val m = 120_000L
        // 25 → 20 salta; 19, 21 (dentro del margen) no; 23 rearma; 20 vuelve a saltar
        val socs = listOf(25, 20, 19, 21, 20, 23, 20)
        val facts = socs.mapIndexed { i, s -> Facts(t + i * m, soc = s) }.toTypedArray()
        assertEquals(listOf(1, 6), run(r, *facts))
    }

    @Test fun bateriaYaBajaAlCrearLaReglaNoSalta() {
        val r = rule(mapOf("type" to "soc", "op" to "below", "value" to 20))
        val t = at(10, 0)
        assertEquals(emptyList<Int>(), run(r, Facts(t, soc = 15), Facts(t + 5000, soc = 14)))
    }

    @Test fun enfriamiento() {
        val r = rule(mapOf("type" to "lock", "locked" to true), cooldown = 10)
        val t = at(10, 0)
        val f = listOf(false, true, false, true, false, true).mapIndexed { i, l -> Facts(t + i * 60_000L * 3, locked = l) }
        // cierra a los 3 min (salta), a los 9 (dentro de los 10 de espera: no) y a los 15 (sí)
        assertEquals(listOf(1, 5), run(r, *f.toTypedArray()))
    }

    @Test fun aLaHoraEntreSemana() {
        val r = rule(mapOf("type" to "time", "at" to "08:00", "days" to listOf(1, 2, 3, 4, 5)))
        val mem = Mem()
        // lunes 2 nov 2026
        assertEquals(listOf(1), run(r, Facts(at(7, 59, 2)), Facts(at(8, 0, 2)), Facts(at(8, 1, 2)), Facts(at(8, 30, 2)), mem = mem))
        // el domingo 1 no
        assertEquals(emptyList<Int>(), run(r, Facts(at(8, 0, 1)), Facts(at(8, 2, 1))))
        // si el coche estaba dormido a las 8:00 y despierta a las 8:07, aún salta (ventana de 10 min)
        assertEquals(listOf(0), run(r, Facts(at(8, 7, 3))))
        assertEquals(emptyList<Int>(), run(r, Facts(at(8, 12, 4))))
    }

    @Test fun llegarACasa() {
        val casa = mapOf("type" to "place", "lat" to 41.1925, "lng" to 1.6405, "radius" to 200, "event" to "arrive", "label" to "casa")
        val r = rule(casa, cooldown = 1)
        val t = at(18, 0)
        val lejos = 41.20; val cerca = 41.1930; val borde = 41.1925 + 0.0020   // ~222 m: fuera del radio, dentro del margen
        val f = listOf(lejos, cerca, borde, cerca, lejos, cerca).mapIndexed { i, lat -> Facts(t + i * 120_000L, lat = lat, lng = 1.6405) }
        assertEquals(listOf(1, 5), run(r, *f.toTypedArray()))
        val salir = rule(casa + ("event" to "leave"), cooldown = 1, id = "r2")
        assertEquals(listOf(4), run(salir, *f.toTypedArray()))
    }

    @Test fun condiciones() {
        val noche = mapOf("type" to "time", "from" to "22:00", "to" to "07:00")
        assertTrue(engine.holds(Cond("time", noche), Facts(at(23, 30))))
        assertTrue(engine.holds(Cond("time", noche), Facts(at(6, 59))))
        assertFalse(engine.holds(Cond("time", noche), Facts(at(7, 0))))
        assertFalse(engine.holds(Cond("time", noche), Facts(at(12, 0))))
        assertTrue(engine.holds(Cond("soc", mapOf("op" to "above", "value" to 30)), Facts(0, soc = 30)))
        assertFalse(engine.holds(Cond("soc", mapOf("op" to "above", "value" to 30)), Facts(0)))   // sin dato: no
        assertTrue(engine.holds(Cond("state", mapOf("key" to "parked", "value" to true)), Facts(0, accOn = false)))
        assertFalse(engine.holds(Cond("state", mapOf("key" to "parked", "value" to true)), Facts(0, accOn = true, speed = 30, gear = "D")))
        assertFalse(engine.holds(Cond("state", mapOf("key" to "charging", "value" to true)), Facts(0)))
    }

    @Test fun soloSiNoSeCumpleNoHaceNada() {
        val r = rule(mapOf("type" to "acc", "on" to false), only = listOf(mapOf("type" to "soc", "op" to "above", "value" to 50)))
        val t = at(10, 0)
        assertEquals(emptyList<Int>(), run(r, Facts(t, accOn = true, soc = 40), Facts(t + 5000, accOn = false, soc = 40)))
        assertEquals(listOf(1), run(r, Facts(t, accOn = true, soc = 60), Facts(t + 5000, accOn = false, soc = 60)))
    }

    @Test fun apagadaNoSaltaNiAlEncenderla() {
        val mem = Mem()
        val t = at(10, 0)
        val off = rule(mapOf("type" to "soc", "op" to "below", "value" to 20), on = false)
        assertEquals(emptyList<Int>(), run(off, Facts(t, soc = 30), Facts(t + 5000, soc = 18), mem = mem))
        // al encenderla con la batería ya baja, no salta de golpe
        val on = off.copy(on = true)
        assertEquals(emptyList<Int>(), run(on, Facts(t + 10000, soc = 17), mem = mem))
    }

    @Test fun centinela() {
        val r = rule(mapOf("type" to "sentry"))
        assertEquals(listOf(0), run(r, Facts(at(3, 0)), events = listOf(Event("sentry"))))
        assertEquals(emptyList<Int>(), run(r, Facts(at(3, 0))))
    }

    @Test fun topeGlobalPorHora() {
        val mem = Mem()
        val t = at(10, 0)
        var n = 0
        for (i in 0 until 30) {
            val r = rule(mapOf("type" to "sentry"), id = "r$i")
            if (engine.evaluate(listOf(r), null, Facts(t + i * 1000L), listOf(Event("sentry")), mem).isNotEmpty()) n++
        }
        assertEquals(Engine.MAX_PER_HOUR, n)
        assertEquals(1, engine.evaluate(listOf(rule(mapOf("type" to "sentry"), id = "otra")), null, Facts(t + 3_700_000L), listOf(Event("sentry")), mem).size)
    }

    @Test fun memoriaSeGuardaYSeLee() {
        val mem = Mem()
        val r = rule(mapOf("type" to "soc", "op" to "below", "value" to 20))
        run(r, Facts(at(10, 0), soc = 30), Facts(at(10, 1), soc = 15), mem = mem)
        val back = Mem.fromMap(mem.toMap())
        assertEquals(mem.level, back.level)
        assertEquals(mem.last, back.last)
        assertEquals(mem.recent.toList(), back.recent.toList())
    }

    @Test fun seguridad() {
        val parado = Facts(0, accOn = false)
        val enMarcha = Facts(0, accOn = true, speed = 50, gear = "D")
        assertNotNull(Policy.check(Act("powerOff", mapOf("confirm" to "APAGAR")), parado))
        assertNotNull(Policy.check(Act("setDriveMode", emptyMap()), parado))
        assertNotNull(Policy.check(Act("openTrunk", emptyMap()), parado))
        assertNotNull(Policy.check(Act("sdkCall", emptyMap()), parado))
        assertNotNull(Policy.check(Act("setLock", mapOf("locked" to false)), parado))
        assertNull(Policy.check(Act("setLock", mapOf("locked" to true)), enMarcha))
        assertNotNull(Policy.check(Act("bydUnlock", emptyMap()), parado))
        assertNotNull(Policy.check(Act("flashLights", emptyMap()), enMarcha))
        assertNull(Policy.check(Act("setClimate", mapOf("on" to true, "temp" to 21)), enMarcha))
        assertNotNull(Policy.check(Act("recordVideo", mapOf("seconds" to 1800)), parado))
        assertNull(Policy.check(Act("recordVideo", mapOf("seconds" to 60)), parado))
    }

    @Test fun hechosDesdeElEstado() {
        val s = CarState(soc = 80, charging = true, locked = true, powerState = "OFF", speed = 0, gear = "P")
        val f = Facts.from(s, null, sdkOk = true, now = 1)
        assertEquals(false, f.accOn); assertTrue(f.parked); assertEquals(80, f.soc); assertNull(f.lat)
        assertEquals(false, Facts.from(CarState(powerState = "ON"), null, sdkOk = false, now = 1).accOn)  // SDK dormido = apagado
        assertEquals(true, Facts.from(CarState(powerState = "OFF"), true, sdkOk = true, now = 1).accOn)   // manda el aviso del sistema
    }
}
