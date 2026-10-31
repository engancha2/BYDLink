package com.bydlink.carapp

/**
 * Configuración de la app del coche. Edita estos valores.
 *  - CAR_ID: debe coincidir con el window.CAR_ID de la app del teléfono.
 *  - USE_BYD_SDK: false mientras pruebas en un móvil/emulador (usa el simulado);
 *    true cuando la instalas en la pantalla del coche.
 */
object BuildConfigCompat {
    const val CAR_ID: String = "mi-byd"
    const val USE_BYD_SDK: Boolean = false
}
