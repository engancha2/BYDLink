package com.bydlink.carapp.service

import com.bydlink.carapp.model.CarState

/**
 * Último estado del coche en memoria, para las pantallas de la propia app (el lanzador) sin pasar por la nube.
 * Lo rellena MainService en cada lectura; null hasta la primera (o si el servicio no está en marcha).
 */
object StateHub {
    @Volatile var state: CarState? = null
    /** Cuándo se leyó (ms); con el SDK dormido el estado se queda, pero esto dice lo viejo que es. */
    @Volatile var readAt: Long = 0L
    /** false mientras el SDK del coche no responde (coche apagado y dormido). */
    @Volatile var sdkOk: Boolean = true

    /** Contacto puesto según el sistema (aviso ACC de BYD o accmodemanager); null si aún no se sabe. */
    @Volatile var accOn: Boolean? = null

    /** true en cuanto hay sesión de Firebase (para la pantalla de estado del lanzador). */
    @Volatile var cloudOk: Boolean = false

    /**
     * Mandos del coche desde la propia app (pestaña Coche del lanzador), los mismos que llegan del móvil.
     * Lo pone MainService; devuelve la respuesta de CarCommands o lanza la excepción del mando. Llamar fuera del hilo
     * principal.
     */
    /** Cámara y vídeos (para la página Cámara del lanzador). */
    @Volatile var camera: com.bydlink.carapp.camera.CameraCommands? = null
    @Volatile var commander: ((String, Map<String, Any?>) -> Map<String, Any?>?)? = null
    /** Cualquier orden como si llegara del móvil (también las de MainService: centinela, ajustes…); responde a [cb]. */
    @Volatile var run: ((String, Map<String, Any?>, (Boolean, Map<String, Any?>) -> Unit) -> Unit)? = null

    fun update(s: CarState) { state = s; readAt = System.currentTimeMillis(); sdkOk = true }
}
