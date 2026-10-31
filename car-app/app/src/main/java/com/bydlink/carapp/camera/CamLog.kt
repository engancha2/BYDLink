package com.bydlink.carapp.camera

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log de la cámara: va a logcat (tag "BYDLink", prefijo "[cam]") y a un anillo con las
 * últimas 300 líneas, que se sube a `cars/{id}/diag/camlog` para depurar a distancia.
 */
object CamLog {
    private const val TAG = "BYDLink"
    private const val MAX = 300
    private val ring = ArrayDeque<String>(MAX + 1)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lastByKey = HashMap<String, Long>()
    @Volatile var changes = 0L; private set

    fun i(msg: String) { Log.i(TAG, "[cam] $msg"); add("I", msg) }
    fun w(msg: String, t: Throwable? = null) {
        Log.w(TAG, "[cam] $msg", t); add("W", msg + (t?.let { " | " + BmmRuntime.rootCause(it) } ?: ""))
    }
    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, "[cam] $msg", t); add("E", msg + (t?.let { " | " + BmmRuntime.rootCause(it) } ?: ""))
    }

    /** Igual que [w] pero como mucho una vez cada [everyMs] por [key] (para cosas que pasan en cada frame). */
    fun limited(key: String, everyMs: Long, msg: String) {
        val now = System.currentTimeMillis()
        synchronized(lastByKey) {
            val last = lastByKey[key] ?: 0L
            if (now - last < everyMs) return
            lastByKey[key] = now
        }
        w(msg)
    }

    @Synchronized private fun add(level: String, msg: String) {
        ring.addLast("${fmt.format(Date())} $level $msg")
        while (ring.size > MAX) ring.removeFirst()
        changes++
    }

    @Synchronized fun text(): String = ring.joinToString("\n")
}
