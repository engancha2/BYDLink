package com.bydlink.carapp.data

import com.bydlink.carapp.model.CarState
import com.bydlink.carapp.model.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** El grabador de viajes y cargas con una "nube" y unas preferencias de mentira: viajes, cargas y reinicios de la app. */
class RecorderTest {
    private class Cloud : Recorder.Out {
        val data = LinkedHashMap<String, Any?>()
        var n = 0
        override fun set(path: String, v: Any?) { if (v == null) remove(path) else data[path] = v }
        override fun remove(path: String) { data.keys.removeAll { it == path || it.startsWith("$path/") } }
        override fun newKey(path: String) = "k${n++}"
        fun node(path: String) = data[path] as? Map<*, *>
        fun trips() = data.keys.filter { it.matches(Regex("trips/[^/]+")) }
    }
    private class Prefs : Recorder.Mem {
        val m = HashMap<String, String>(); val f = HashMap<String, Float>()
        override fun get(k: String) = m[k]
        override fun put(k: String, v: String?) { if (v == null) m.remove(k) else m[k] = v }
        override fun getF(k: String) = f[k] ?: 0f
        override fun putF(k: String, v: Float) { f[k] = v }
    }

    private val cloud = Cloud()
    private val prefs = Prefs()
    private fun rec() = Recorder(cloud, prefs) { }

    // El coche simulado: martes 3/11/2026 a las 8:00, 80 %, 34 kWh en la batería.
    private var ts = Calendar.getInstance().apply { clear(); set(2026, 10, 3, 8, 0) }.timeInMillis
    private var lat = 41.0; private var left = 34.0; private var soc = 80.0; private var odo = 1000.0

    private fun state(kmh: Int, gear: String) = CarState(ts = ts, speed = kmh, gear = gear, soc = soc.toInt(),
        energyLeftKwh = Math.round(left * 100) / 100.0, odometer = odo.toInt(), outTemp = 14, location = Location(lat, 2.0))

    /** [min] minutos hacia el norte a [kmh], una lectura cada 2 s, gastando 14 kWh/100 km. */
    private fun drive(r: Recorder, min: Int, kmh: Int = 36) = repeat(min * 30) {
        ts += 2000; val dk = kmh / 3600.0 * 2
        lat += dk / 111.2; odo += dk; left -= dk * 0.14; soc -= dk * 0.14 / 43.0 * 100
        r.onState(state(kmh, "D"))
    }
    private fun park(r: Recorder, min: Int) = repeat(min * 30) { ts += 2000; r.onState(state(0, "P")) }

    @Test fun tripIsSavedWithEnergyAndEndsWhenItStopped() {
        val r = rec()
        drive(r, 10)
        assertEquals(true, cloud.node("trips/k0")?.get("live"))   // va guardando en curso
        val stopAt = ts + 2000
        park(r, 4)
        val t = cloud.node("trips/k0")!!
        assertNull(t["live"])
        assertEquals(stopAt, t["end"])                             // cuando se paró, no 3 min después
        val km = t["distanceKm"] as Double
        assertTrue("km=$km", km in 5.7..6.1)                       // 10 min a 36 km/h
        assertEquals("bateria", t["kwhSrc"])
        val kwh = t["kwh"] as Double
        assertTrue("kwh=$kwh", kwh in 0.75..0.9)
        assertEquals(14.0, t["kwh100"] as Double, 0.6)
        assertEquals(36, t["maxKmh"]); assertEquals(14, t["outTemp"]); assertEquals(79, t["socStart"])
        assertTrue(cloud.data["tripRoutes/k0"] is String)
        assertTrue(Route.decode(cloud.data["tripRoutes/k0"] as String).size in 2..300)
        assertNull(prefs.m["trip"])
    }

    @Test fun tripSurvivesAppRestart() {
        drive(rec(), 5)
        ts += 20_000          // BYD mata la app; el vigilante la vuelve a abrir unos segundos después: el viaje sigue
        val r2 = rec()
        drive(r2, 5)
        park(r2, 4)
        assertEquals(listOf("trips/k0"), cloud.trips())
        val km = cloud.node("trips/k0")!!["distanceKm"] as Double
        assertTrue("km=$km", km in 5.5..6.2)
    }

    @Test fun staleTripIsClosedOnRestartAndOnSilence() {
        drive(rec(), 5)
        val lastDrive = ts
        ts += 20 * 60_000L    // la app vuelve 20 min después con el coche aparcado: el viaje viejo se cierra
        rec().onState(state(0, "P"))
        val t = cloud.node("trips/k0")!!
        assertNull(t["live"])
        assertTrue((t["end"] as Long) in (lastDrive - 20_000)..lastDrive)

        // Otro viaje y el coche se duerme (el SDK no contesta): onSilence lo cierra
        ts += 3_600_000L
        val r = rec()
        drive(r, 3)
        r.onSilence(ts + 4 * 60_000L)
        assertEquals(2, cloud.trips().size)
        assertNull(cloud.node("trips/k1")!!["live"])
        assertNull(prefs.m["trip"])
    }

    @Test fun shortStopsDoNotSplitAndTinyTripsAreDropped() {
        val r = rec()
        drive(r, 3); park(r, 2)                  // semáforo largo en P: 2 min
        drive(r, 3); park(r, 4)
        assertEquals(1, cloud.trips().size)
        drive(r, 1, kmh = 5); park(r, 4)         // moverse 1 minuto en un garaje (< 0,2 km): no se guarda
        assertEquals(1, cloud.trips().size)
        assertTrue(cloud.data.keys.none { it.startsWith("tripPoints/k1") })
    }

    @Test fun chargeByHourAndRestart() {
        ts = Calendar.getInstance().apply { clear(); set(2026, 10, 3, 1, 30) }.timeInMillis   // martes 1:30
        var r = rec()
        var kwh = 0.0
        // 2 h cargando a 2,4 kW (una lectura cada 20 s), con un reinicio de la app a mitad
        for (i in 0 until 360) {
            if (i > 0) ts += 20_000L
            if (i == 180) r = rec()
            kwh = 2.4 * i * 20 / 3600.0
            r.onState(CarState(ts = ts, charging = true, chargingGun = "AC", chargedKwh = kwh, chargeKw = 2.4, soc = 50 + i / 20,
                location = Location(41.0, 2.0)))
        }
        ts += 20_000L
        r.onState(CarState(ts = ts, charging = false, chargingGun = "none", soc = 68, location = Location(41.0, 2.0)))
        assertEquals(listOf("chargingSessions/k0"), cloud.data.keys.filter { it.startsWith("chargingSessions/") })
        val c = cloud.node("chargingSessions/k0")!!
        assertNull(c["live"]); assertEquals("AC", c["type"]); assertEquals(50, c["socStart"]); assertEquals(68, c["socEnd"])
        assertEquals(kwh, c["kwh"] as Double, 0.11)
        assertEquals(2.4, c["avgKw"] as Double, 0.15)
        val h = c["kwhH"] as Map<*, *>
        assertEquals(setOf("2026110301", "2026110302", "2026110303"), h.keys)
        assertEquals(kwh, h.values.sumOf { (it as Number).toDouble() }, 0.05)
        assertEquals(kwh * 0.1, Tariff.chargeCost(Tariff.Cfg(p3 = 0.1), c, null), 0.02)   // de madrugada: todo valle
        assertEquals(41.0, c["lat"])
        assertNull(prefs.m["chg"])
    }

    @Test fun chargeWithoutCounterUsesSocAndCapacity() {
        val r = rec()
        r.onState(CarState(ts = ts, soc = 60, energyLeftKwh = 26.0))   // capacidad: 26 kWh al 60 % → 43,3 kWh
        for (i in 0 until 100) { ts += 20_000L; r.onState(CarState(ts = ts, charging = true, chargingGun = "AC", soc = 60 + i / 10)) }
        ts += 20_000L
        r.onState(CarState(ts = ts, charging = false, chargingGun = "none", soc = 70))
        val c = cloud.node("chargingSessions/k0")!!
        assertEquals(4.3, c["kwh"] as Double, 0.15)   // 10 % de 43,3 kWh
        assertNotNull(c["kwhH"])
    }
}
