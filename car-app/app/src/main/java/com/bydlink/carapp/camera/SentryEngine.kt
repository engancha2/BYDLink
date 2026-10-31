package com.bydlink.carapp.camera

import kotlin.math.abs

/**
 * Centinela: detección de movimiento por diferencia de luminancia (canal Y) entre frames.
 * Es ligero y en Kotlin puro — equivalente simple al MOG2 de Electro, suficiente para avisar
 * de que "algo se mueve" delante del coche aparcado. Afinar con pruebas en el coche.
 *
 * Entrada: frames en NV21/NV12 (el primer w*h bytes es el plano Y = luminancia).
 */
class SentryEngine(
    var sensitivity: Int = 5,          // 1 (poco sensible) .. 10 (muy sensible)
    private val cooldownMs: Long = 8000 // no re-disparar antes de este tiempo
) {
    private var prevY: ByteArray? = null
    private var lastTrigger = 0L
    var enabled = true

    /** Devuelve true si detecta movimiento significativo (respetando el cooldown). */
    fun process(frame: ByteArray, width: Int, height: Int): Boolean {
        if (!enabled) return false
        val ySize = width * height
        if (frame.size < ySize) return false
        val prev = prevY
        // Copia del plano Y (submuestreado para rapidez).
        val step = 4
        if (prev == null || prev.size != ySize) {
            prevY = frame.copyOf(ySize); return false
        }

        // Umbral por pixel y % de área según sensibilidad.
        val pixelThreshold = 40 - (sensitivity * 3)          // 10..37
        val areaThresholdPct = (11 - sensitivity) * 0.4       // ~0.4%..4%
        var changed = 0; var total = 0
        var i = 0
        while (i < ySize) {
            val diff = abs((frame[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF))
            if (diff > pixelThreshold) changed++
            total++
            i += step
        }
        // Actualiza referencia (adaptativa).
        prevY = frame.copyOf(ySize)

        val pct = if (total > 0) changed * 100.0 / total else 0.0
        if (pct >= areaThresholdPct) {
            val now = System.currentTimeMillis()
            if (now - lastTrigger >= cooldownMs) { lastTrigger = now; return true }
        }
        return false
    }

    fun reset() { prevY = null }
}
