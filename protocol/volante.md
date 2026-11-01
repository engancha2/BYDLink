# Botones del volante (app del coche 2.27)

El usuario asigna a un botón del volante una acción de BYDLink por tipo de pulsación: **corta**, **larga** (≥ 0,6 s) o
**doble** (dos en 0,35 s). Lo que no tiene acción sigue haciendo lo de siempre. Código: `car-app/.../keys/`
(`WheelKeys`, `WheelKeyService`, `SdkKeyListeners`), página del lanzador `ui/KeysPage.kt`, web `phone/keys.js`.

## 1. Cómo llegan los botones en DiLink 3.0 (Dolphin Surf, Android 10)

| Fuente | Qué es | ¿Se puede quedar el botón? |
|---|---|---|
| **KeyEvent de Android** por un servicio de **accesibilidad** con filtro de teclas (`flagRequestFilterKeyEvents`) | Llega antes que a las apps. Es lo que usan Kim (`byd.KimKeyAccessibilityService`, "Key Mapping", "Block original BYD action") y Electro (`service.SecondaryService`, accesibilidad). | **Sí**: si se devuelve `true` en `onKeyEvent` |
| **SDK BYD** `onDataEventChanged` | `INSTRUMENT_INPUT_KEYCODE_STATUS` = `0x4a801010` (Instrument; la dashcam de BYD, `com.byd.avc` AutoVideo, lo escucha y reacciona al valor 22), `AUDIO_VOICE_INPUT_KEY_STATUS` = `0x4a80101e`, `AUDIO_CUSTOM_KEY_MEDIA_PLAY_PAUSE_NOTIFY` = `0x99000309` (Audio). Registro: `BYDAutoInstrumentDevice.registerListener(AbsBYDAutoInstrumentListener, int[])`, igual en `BYDAutoAudioDevice`. | No (solo se oye) |
| `getevent -lq` por el ADB del coche | Lo que manda el hardware (`/dev/input/eventN`). Solo en "Aprender", para mapear. | No |

Otros hallazgos:
- El **botón personalizable** de BYD (app de sistema `com.byd.customkey`) guarda su función en `CarSettings.UserTableData
  "custom_key_value"` (izquierdo) y `CarSettings.Global "right_custom_key_value"` (derecho). Funciones: 1 girar pantalla,
  2 WiFi, 3 foto dashcam, 4 bloquear vídeo dashcam, 5 aparcamiento automático, 6 play/pausa, 7 cambiar fuente de audio,
  8 navegación a casa. No tiene "abrir una app": por eso aquí se usa la accesibilidad.
- `com.byd.car.feature.steerwheel.ICarSteerWheelService` (AppServer) es solo del volante calefactado / columna, no de botones.
- BYD tiene su propio servicio de accesibilidad de voz (`com.byd.autovoice`): Kim lo desactiva a ratos para coger el botón de
  voz. **Nosotros no lo tocamos**: al poner el nuestro se añade a la lista sin quitar los demás.
- **Al quitar el contacto BYD fuerza el cierre de todas las apps** y Android, al forzar el cierre, saca el servicio de la lista
  de accesibilidad (y en Android 11+ lo marca como "colgado"). `WheelKeys` lo vuelve a poner al arrancar (a los 3 s y 12 s,
  y cada minuto) y, si está puesto pero no se enlaza, lo quita y lo vuelve a poner. Escribe `Settings.Secure` con
  `WRITE_SECURE_SETTINGS` (lo da KeepAlive por ADB); si no lo tiene, por el ADB del coche.

## 2. Reglas

- Solo se queda los botones **asignados**. Nunca: casita (3), atrás (4), encendido (26), dormir/despertar, multitarea.
- Si un botón asignado se pulsa de una forma sin acción, se devuelve: volumen → `AudioManager.adjustSuggestedStreamVolume`,
  multimedia → `AudioManager.dispatchMediaKeyEvent`, el resto → `input keyevent N` por ADB (lo inyectado no vuelve a pasar
  por el filtro; por si acaso, se deja pasar lo del teclado virtual durante 4 s).
- Con doble asignada, la corta espera 0,35 s (para ver si llega la segunda).
- SDK: cada valor ≠ 0 es un toque (no se sabe cuándo se suelta): solo corta y doble.
- "Aprender" no cambia nada: los botones siguen igual; solo se apunta lo que llega.

## 3. Acciones

`page:inicio|camara|clima|energia|mapa|musica|coche` (abre el lanzador en esa pestaña; extra `tab` de `HomeActivity`),
`clip` (pasa el último tramo cerrado de la dashcam a "Grabaciones" y graba 1 minuto), `photo` (`takePhoto`),
`sentry` (centinela sí/no: orden local `toggleSentry` de MainService), `climate` (aire sí/no: `setClimate`),
`media:next|prev|playpause`, `app:<paquete>`. Cada acción enseña un aviso corto en la pantalla del coche.

## 4. Nube: `cars/{id}/keys`

```
config:  { enabled, bindings: { "<botón>": { short?, long?, double? } } }
status:  { enabled, service (accesibilidad enlazada), sdk ("ok" | "sin SDK" | "no deja (…)" | "no"), learning, learnUntil?,
           adb?, devices? (nombres de /proc/bus/input/devices), ts }
learned: { "<botón>": { id, src (android|sdk|sdk-voz), name, code, count, first, last, scan?, device?, held? (ms), fid? } }
log:     [ { ts, src (android|sdk|sdk-voz|getevent|accion), id, name, info } ]   (lo último, 30 como mucho)
apps:    [ { pkg, label } ]   (apps que se pueden abrir)
```
Ids de botón: `k<keyCode>` (Android), `sdk<valor>` (SDK), `voz<valor>` (botón de voz del SDK). Lo de `getevent` sale en
`log` como `ev:<KEY_…>` (no se puede asignar). Se publica solo si cambia algo (o cada 30 min).

## 5. Órdenes (`cars/{id}/commands`)

| action | payload | qué hace |
|---|---|---|
| `setKeyConfig` | `{ enabled?: bool, clear?: true, key?, press?: "short"\|"long"\|"double", action?: string\|null }` | Activa/desactiva, borra todo o asigna (null = lo normal). Asignar el primer botón activa la función. |
| `keyLearn` | `{ on: bool, minutes?: 1-30 (5) }` | Modo aprender (también `getevent` por ADB a tandas de 12 s). |
| `getKeyStatus` | — | Devuelve `{ config, status }`. |
| `keyRunAction` | `{ action }` | Ejecuta una acción ya (para probar). |

Orden local (no del móvil): `toggleSentry` → `{ on }`, por `StateHub.run`.

## 6. Por probar en el coche

1. Ajustes del lanzador → Botones del volante → Aprender botón, y pulsar todos los botones del volante (corto y largo):
   ver qué llega como tecla (id `k…`), por SDK (`sdk…`/`voz…`) o solo por hardware (`ev:…`), y cuánto dura (`held`).
2. Asignar algo a "Siguiente" (pulsación larga) y comprobar que la corta sigue pasando de canción.
3. Quitar y poner el contacto: el servicio debe volver solo (estado "activo" en ~15 s).
4. Si BYD actúa igual con un botón asignado (p. ej. la voz o el teléfono), es que lo trata antes de Android: anotarlo.
