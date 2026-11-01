package com.bydlink.carapp.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ruta de un viaje en pocos bytes: se simplifica (Douglas-Peucker, tolerancia en metros) y se codifica como
 * "polilínea codificada" (el formato abierto de Google Maps, precisión 1e-5). El móvil la decodifica en history.js.
 */
object Route {
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Simplifica hasta que quedan como mucho [max] puntos (sube la tolerancia de 10 m en 10 m). */
    fun simplify(pts: List<DoubleArray>, max: Int = 300): List<DoubleArray> {
        if (pts.size <= 2) return pts
        var tol = 10.0
        var out = dp(pts, tol)
        while (out.size > max && tol < 5000) { tol *= 1.6; out = dp(pts, tol) }
        return out
    }

    private fun dp(pts: List<DoubleArray>, tolM: Double): List<DoubleArray> {
        val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.size - 1] = true
        val stack = ArrayDeque<IntArray>(); stack.addLast(intArrayOf(0, pts.size - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast().let { it[0] to it[1] }
            var far = -1; var dMax = 0.0
            for (i in a + 1 until b) { val d = distToSegM(pts[i], pts[a], pts[b]); if (d > dMax) { dMax = d; far = i } }
            if (far >= 0 && dMax > tolM) { keep[far] = true; stack.addLast(intArrayOf(a, far)); stack.addLast(intArrayOf(far, b)) }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    /** Distancia de p al segmento a-b en metros (proyección plana local: suficiente para tramos cortos). */
    private fun distToSegM(p: DoubleArray, a: DoubleArray, b: DoubleArray): Double {
        val k = cos(Math.toRadians(a[0])) * 111_320.0
        val ax = a[1] * k; val ay = a[0] * 110_540.0
        val bx = b[1] * k - ax; val by = b[0] * 110_540.0 - ay
        val px = p[1] * k - ax; val py = p[0] * 110_540.0 - ay
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx; val dy = py - t * by
        return sqrt(dx * dx + dy * dy)
    }

    fun encode(pts: List<DoubleArray>): String {
        val sb = StringBuilder()
        var pLat = 0L; var pLng = 0L
        for (p in pts) {
            val lat = Math.round(p[0] * 1e5); val lng = Math.round(p[1] * 1e5)
            put(sb, lat - pLat); put(sb, lng - pLng)
            pLat = lat; pLng = lng
        }
        return sb.toString()
    }

    private fun put(sb: StringBuilder, v: Long) {
        var x = if (v < 0) (v shl 1).inv() else v shl 1
        while (x >= 0x20) { sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar()); x = x shr 5 }
        sb.append((x + 63).toInt().toChar())
    }

    fun decode(s: String): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var i = 0; var lat = 0L; var lng = 0L
        fun next(): Long {
            var r = 0L; var shift = 0; var b: Int
            do { b = s[i++].code - 63; r = r or ((b and 0x1f).toLong() shl shift); shift += 5 } while (b >= 0x20)
            return if (r and 1L != 0L) (r shr 1).inv() else r shr 1
        }
        while (i < s.length) { lat += next(); lng += next(); out.add(doubleArrayOf(lat / 1e5, lng / 1e5)) }
        return out
    }
}
