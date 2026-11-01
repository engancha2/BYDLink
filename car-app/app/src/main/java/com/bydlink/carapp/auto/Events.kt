package com.bydlink.carapp.auto

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Avisos de "algo ha pasado" entre partes de la app sin que se conozcan (p. ej. el centinela → automatizaciones
 * y avisos al móvil). Quien escucha no debe tardar: si tiene trabajo, que lo pase a su hilo.
 */
object Events {
    const val SENTRY = "sentry"

    private val listeners = CopyOnWriteArrayList<(String, Map<String, Any?>) -> Unit>()

    fun listen(f: (String, Map<String, Any?>) -> Unit) { listeners += f }

    fun emit(type: String, data: Map<String, Any?> = emptyMap()) =
        listeners.forEach { try { it(type, data) } catch (e: Throwable) { android.util.Log.w("BYDLink", "evento $type", e) } }
}
