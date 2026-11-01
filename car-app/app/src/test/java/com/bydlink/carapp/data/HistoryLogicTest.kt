package com.bydlink.carapp.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class HistoryLogicTest {
    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi) }.timeInMillis

    @Test fun polylineAsGoogleExample() {
        val pts = listOf(doubleArrayOf(38.5, -120.2), doubleArrayOf(40.7, -120.95), doubleArrayOf(43.252, -126.453))
        val enc = Route.encode(pts)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", enc)
        val back = Route.decode(enc)
        assertEquals(3, back.size)
        assertEquals(43.252, back[2][0], 1e-9); assertEquals(-126.453, back[2][1], 1e-9)
    }

    @Test fun simplifyStraightLine() {
        val pts = (0..200).map { doubleArrayOf(41.0 + it * 0.0001, 2.0 + it * 0.0001) }
        assertEquals(2, Route.simplify(pts).size)
        val zig = (0..2000).map { doubleArrayOf(41.0 + it * 0.001, 2.0 + if (it % 2 == 0) 0.0 else 0.01) }
        assert(Route.simplify(zig, 300).size <= 300)
    }

    @Test fun periods20TD() {
        assertEquals(1, Tariff.period("2026110210"))   // lunes 10 h: punta
        assertEquals(2, Tariff.period("2026110209"))   // lunes 9 h: llano
        assertEquals(3, Tariff.period("2026110207"))   // lunes 7 h: valle
        assertEquals(1, Tariff.period("2026110321"))   // martes 21 h: punta
        assertEquals(2, Tariff.period("2026110322"))   // martes 22 h: llano
        assertEquals(3, Tariff.period("2026110112"))   // domingo y festivo
        assertEquals(3, Tariff.period("2026120812"))   // 8 de diciembre (martes): festivo
        assertEquals(3, Tariff.period("2026110712"))   // sábado
    }

    @Test fun chargeCosts() {
        val cfg = Tariff.parse(mapOf(
            "home" to mapOf("mode" to "periods", "p1" to 0.3, "p2" to 0.2, "p3" to 0.1),
            "awayAc" to 0.4, "dc" to 0.5,
            "places" to listOf(
                mapOf("name" to "Casa", "lat" to 41.0, "lng" to 2.0, "r" to 150, "price" to "home"),
                mapOf("name" to "Trabajo", "lat" to 41.1, "lng" to 2.1, "r" to 200, "price" to 0)
            )
        ))
        val night = mapOf("kwh" to 10.0, "type" to "AC", "lat" to 41.0005, "lng" to 2.0, "kwhH" to mapOf("2026110302" to 10.0))
        assertEquals(1.0, Tariff.chargeCost(cfg, night, null), 1e-9)
        assertEquals("home", Tariff.kindOf(cfg, night, null))
        val work = mapOf("kwh" to 8.0, "type" to "AC", "lat" to 41.1, "lng" to 2.1)
        assertEquals("place", Tariff.kindOf(cfg, work, null)); assertEquals(0.0, Tariff.chargeCost(cfg, work, null), 1e-9)
        val away = mapOf("kwh" to 10.0, "type" to "AC", "lat" to 40.0, "lng" to 3.0)
        assertEquals(4.0, Tariff.chargeCost(cfg, away, null), 1e-9)
        val dc = mapOf("kwh" to 10.0, "type" to "DC", "lat" to 41.0, "lng" to 2.0)
        assertEquals(5.0, Tariff.chargeCost(cfg, dc, null), 1e-9)
        assertEquals(2.5, Tariff.chargeCost(cfg, dc, mapOf("costEur" to 2.5)), 1e-9)
        assertEquals(0.0, Tariff.chargeCost(cfg, dc, mapOf("kind" to "free")), 1e-9)
        // Carga vieja con coste puesto a mano (demo) y sin lugar: se respeta.
        assertEquals(3.1, Tariff.chargeCost(cfg, mapOf("kwh" to 19.4, "type" to "AC", "costEur" to 3.1), null), 1e-9)
    }

    @Test fun spreadWithoutHourly() {
        val c = mapOf("kwh" to 4.0, "start" to ts(2026, 11, 3, 1, 30), "end" to ts(2026, 11, 3, 3, 30))
        val h = Tariff.hours(c)
        assertEquals(1.0, h["2026110301"]!!, 1e-9); assertEquals(2.0, h["2026110302"]!!, 1e-9); assertEquals(1.0, h["2026110303"]!!, 1e-9)
        val flat = Tariff.parse(mapOf("home" to mapOf("mode" to "flat", "flat" to 0.2)))
        assertEquals(0.8, Tariff.chargeCost(flat, c, null), 1e-9)
    }
}
