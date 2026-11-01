package com.bydlink.carapp.keys

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Servicio de accesibilidad SOLO para los botones (filtro de teclas): no lee la pantalla ni lo que se escribe.
 * Lo activa [WheelKeys] cuando el usuario usa los botones del volante; si algo falla, el botón pasa tal cual.
 */
class WheelKeyService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        try { serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS } } catch (e: Throwable) {}
        WheelKeys.start(applicationContext)
        WheelKeys.attach(this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = try { WheelKeys.onKey(this, event) } catch (e: Throwable) { false }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean { WheelKeys.detach(this); return super.onUnbind(intent) }
    override fun onDestroy() { WheelKeys.detach(this); super.onDestroy() }
}
