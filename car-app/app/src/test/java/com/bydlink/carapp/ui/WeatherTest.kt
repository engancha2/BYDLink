package com.bydlink.carapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class WeatherTest {
    // Respuesta de Open-Meteo recortada (current + hourly con la probabilidad de lluvia).
    private val json = """{"latitude":41.19,"longitude":1.57,"current":{"time":"2026-11-01T10:15","temperature_2m":18.4,
        "weather_code":2,"wind_speed_10m":12.3,"wind_direction_10m":225},
        "hourly":{"time":["2026-11-01T09:00","2026-11-01T10:00","2026-11-01T11:00"],"precipitation_probability":[0,5,30]}}"""

    @Test fun leeElTiempoYLaLluviaDeLaHoraEnCurso() {
        val now = Calendar.getInstance().apply { clear(); set(2026, 10, 1, 10, 24) }
        val w = Weather.parseForecast(json, now)
        assertEquals(18.4, w.temp, 0.01)
        assertEquals(2, w.code)
        assertEquals(5, w.rainPct)
        assertEquals("Algo nublado", w.text)
        assertEquals("cloud", w.icon)
        assertEquals("SO · 12 km/h", w.wind)
    }

    @Test fun sinDatosDeLaHoraNoHayLluvia() {
        val now = Calendar.getInstance().apply { clear(); set(2026, 10, 2, 8, 0) }
        assertNull(Weather.parseForecast(json, now).rainPct)
    }

    @Test fun puntosCardinales() {
        assertEquals("N", Weather.compass(0)); assertEquals("N", Weather.compass(359)); assertEquals("NE", Weather.compass(40))
        assertEquals("E", Weather.compass(90)); assertEquals("S", Weather.compass(180)); assertEquals("O", Weather.compass(270))
        assertEquals("NO", Weather.compass(-45))
    }

    @Test fun nombreDelPueblo() {
        assertEquals("Calafell", Weather.parsePlace("""{"address":{"village":"Calafell","county":"Baix Penedès"}}"""))
        assertEquals("Barcelona", Weather.parsePlace("""{"address":{"city":"Barcelona","town":"x"}}"""))
        assertNull(Weather.parsePlace("""{"error":"Unable to geocode"}"""))
    }

    @Test fun codigosDelTiempo() {
        assertEquals("Despejado", Weather.describe(0)); assertEquals("sun", Weather.iconOf(0))
        assertEquals("Lluvia", Weather.describe(61)); assertEquals("rain", Weather.iconOf(61))
        assertEquals("Nieve", Weather.describe(73)); assertEquals("snow", Weather.iconOf(73))
        assertEquals("Tormenta", Weather.describe(95)); assertEquals("storm", Weather.iconOf(95))
    }
}
