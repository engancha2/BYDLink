# Mantener viva la app del coche (anti-hibernación)

Para que el centinela sirva, la app NO puede morir cuando el coche se aparca y el head unit
entra en bajo consumo (es lo que hace Electro). Estrategia en capas:

## En la app (código, ya implementado)
- **Servicio en primer plano** con notificación persistente + `START_STICKY`.
- **WakeLock parcial**: la CPU sigue activa con la pantalla apagada (para procesar cámaras).
- **Auto-arranque** al encender (`BootReceiver`, `BOOT_COMPLETED`/`QUICKBOOT_POWERON`).
- **Watchdog**: si el sistema barre o destruye el servicio, se reprograma su reinicio por
  `AlarmManager` (`RestartReceiver`) y hay un `JobScheduler` periódico (`KeepAliveJobService`,
  cada ~15 min, persistido entre reinicios) que lo vuelve a levantar.
- `onTaskRemoved` también relanza el servicio.

## Por ADB local (`CarShell.keepAlive`, se aplica una vez)
- `dumpsys deviceidle whitelist +pkg` → exento de Doze.
- `cmd appops set pkg RUN_ANY_IN_BACKGROUND allow` → sin restricción en segundo plano.
- `cmd app_hibernation set-state ... false` y `settings put global app_hibernation_enabled 0`
  → sin hibernación de apps (Android 12+).
- `am set-inactive pkg false` → no marcar como inactiva.

## Opcional (más agresivo)
- Registrar la app como **launcher (HOME)** como hace Electro/Beetle: los launchers casi nunca
  se matan. Es opcional porque sustituye la pantalla de inicio.
- Mantener la pantalla apagada pero el sistema despierto (`turnBacklightOff` del SDK BYD, como
  SentryEV) para ahorrar batería sin dejar de vigilar.

> Qué exenciones hacen falta depende del modelo/versión de DiLink. El diagnóstico de la app
> ayuda a ver qué se aplica. En coches dedicados a esto, las exenciones de arriba bastan.
