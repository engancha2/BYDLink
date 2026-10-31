package com.bydlink.carapp.service

import android.service.notification.NotificationListenerService

/**
 * Solo existe para que Android deje a BYDLink ver qué música suena (MediaSessionManager.getActiveSessions pide un
 * "oyente de notificaciones" con permiso). No lee ni guarda notificaciones. El permiso lo da KeepAlive por ADB
 * (`cmd notification allow_listener`) o el usuario en Ajustes.
 */
class MediaListener : NotificationListenerService()
