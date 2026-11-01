# Automatizaciones y avisos al móvil (app del coche 2.27, web `phone/autos.js`)

"Cuando pase esto · solo si se cumple esto · haz esto". Las reglas las escribe el móvil en Firebase y **las cumple
el coche** (`car-app/.../auto/`), aunque el móvil esté apagado. Los avisos al móvil los manda **el propio coche**
por Web Push estándar, sin servidor de BYDLink.

## 1. Nodos de Firebase (`cars/{carId}/…`)

| Nodo | Quién escribe | Contenido |
|---|---|---|
| `automations/{ruleId}` | móvil | una regla (§2). El móvil la guarda directamente, sin orden: el coche la recoge al tener conexión y la guarda también en el coche para funcionar sin cobertura. |
| `autoState/{ruleId}` | coche | `{last: ts, ok: bool, why: "Has apagado el coche"}` — la última vez que saltó. |
| `autoLog/{pushId}` | coche | `{ts, rule, name, why, ok, manual, results: [{action, label, ok, msg}]}` — las 100 últimas (el coche borra las viejas). `.indexOn: ts`. |
| `push/key` | coche | clave pública VAPID del coche (P-256 en crudo, Base64 url). La privada nunca sale del coche (preferencias `push`). |
| `push/subs/{subId}` | móvil | una suscripción Web Push: `{endpoint, p256dh, auth, key, name, uid, ts, sentry: bool, autos: bool}`. `subId` = 24 primeros hex del SHA-256 del `endpoint`. `key` = la clave del coche con la que se suscribió. El coche borra la suscripción si el servicio responde 404/410 y pone `error: "clave"` si responde 401/403 (el móvil pide volver a activarlos). |
| `bydCloud/linked` | parte "nube BYD" | `true` si la cuenta BYD está vinculada: solo entonces la web ofrece las acciones `byd*`. |

Reglas (`firebase/database.rules.json`): mismas que el resto del coche + `.validate` de la forma de las reglas y de
las suscripciones (`endpoint` https, < 1000 caracteres).

## 2. Una regla

```json
{
  "name": "Al apagar el coche, activa el centinela",
  "on": true,
  "when": { "type": "acc", "on": false },
  "only": [ { "type": "time", "from": "22:00", "to": "07:00", "days": [1,2,3,4,5] } ],
  "do":   [ { "action": "setSentryConfig", "payload": { "on": true } },
            { "action": "notify", "payload": { "text": "Centinela puesto" } } ],
  "cooldownMin": 5,
  "created": 1793500000000, "updated": 1793500000000
}
```

Días: 1 = lunes … 7 = domingo; vacío = todos. Horas en la zona horaria del coche.

### `when` (disparador: salta al CAMBIAR, nunca por estar ya así)

| type | parámetros | salta cuando |
|---|---|---|
| `acc` | `on` | se da (`true`) o se quita (`false`) el contacto (aviso ACC de BYD / `accmodemanager`; si no, `powerState`) |
| `lock` | `locked` | se cierra / abre el coche |
| `charge` | `charging` | empieza (`true`) / termina (`false`) la carga |
| `soc` | `op` `below`/`above`, `value` % | la batería llega a `value` o menos / o más. Margen de 2 % para rearmarse |
| `temp` | `where` `in`/`out`, `op`, `value` °C | temperatura interior/exterior cruza el umbral (margen 1 °C) |
| `aux12v` | `value` V | la batería de 12 V baja a `value` o menos (margen 0,3 V) |
| `time` | `at` "HH:MM", `days` | a esa hora (si el coche estaba dormido, hasta 10 min después); una vez al día |
| `place` | `lat`, `lng`, `radius` m (50–50 000), `event` `arrive`/`leave`, `label` | el coche entra / sale del círculo (al salir, el círculo es un 20 % mayor, mín. 50 m) |
| `sentry` | — | el centinela de BYDLink detecta movimiento |

La primera vez que se mira un dato (al arrancar la app o crear la regla) no salta nada. Los umbrales y las
geocercas se recuerdan en el coche (sobreviven a reinicios).

### `only` (todas deben cumplirse; si falta el dato, NO se cumple)

`time` {from, to, days} (cruza medianoche si from > to) · `soc` {op, value} · `temp` {where, op, value} ·
`place` {lat, lng, radius, inside} · `state` {key: `parked`|`charging`|`locked`|`accOn`, value}.

### `do` (como mucho 5, en orden; cada una espera su respuesta, máx. 30 s)

Pasan por **la misma ruta que las órdenes del móvil** (MainService.runAction: cámara, configuración, CarCommands
con todas sus comprobaciones) y antes por una lista cerrada (`auto/Engine.kt` → `Policy`):

| action | payload | notas |
|---|---|---|
| `notify` | `{text}` | aviso al móvil (§3) con el nombre de la regla como título; sin texto, el motivo |
| `setClimate` | `{on, temp}` | con el coche apagado no funciona en el Surf: usar `bydClimate` |
| `setDefrost`, `setSeatComfort`, `setSeatHeat`, `setSeatVent`, `batteryPreheat`, `setMirrorHeat` | como desde el móvil | |
| `setChargeLimit` | `{percent}` | |
| `setSentryConfig` | `{on}` | el centinela de BYDLink |
| `recordVideo` | `{seconds}` ≤ 300 | |
| `stopVideo`, `takePhoto`, `setVideoConfig` `{mode}` | | foto solo aparcado |
| `lock` / `setLock {locked:true}`, `closeWindows`, `flashLights`, `locateCar`, `setInteriorLight` | | abrir en local, nunca |
| `bydLock`, `bydUnlock`, `bydClimate {on, temp, minutes}`, `bydFind`, `bydCharge {op: "start"\|"stop"}` | | de la parte "nube BYD"; la web solo las ofrece con `bydCloud/linked` |

Prohibido siempre: `powerOff`, modo de conducción, limitador, `sdkCall`, maletero/frunk/tapa, abrir en local.
Solo con el coche aparcado: `bydUnlock`, `bydFind`, `flashLights`, `locateCar`, `takePhoto`, `setInteriorLight`,
`closeWindows`. Cada regla espera `cooldownMin` (1–1440, por defecto 5) antes de volver a saltar y, entre todas,
como mucho 20 por hora.

## 3. Avisos al móvil (Web Push, RFC 8030/8291/8292)

1. El coche crea una vez su par de claves VAPID (P-256) y publica la pública en `push/key`.
2. El móvil (botón «Activar avisos en este móvil») pide permiso, se suscribe con
   `pushManager.subscribe({userVisibleOnly: true, applicationServerKey: push/key})` y guarda la suscripción en
   `push/subs/{subId}`; luego pide `pushTest`.
3. El coche cifra cada aviso con `aes128gcm` (RFC 8291) y lo manda por HTTPS al `endpoint` con
   `Authorization: vapid t=<JWT ES256, aud=origen del endpoint, exp=12 h, sub=https://app.bydhack.com>, k=<clave>`,
   `TTL` (centinela 6 h, automatizaciones 24 h, prueba 10 min) y `Urgency` (centinela `high`).
   Solo a servicios de avisos de navegadores: `fcm.googleapis.com`, `*.push.services.mozilla.com`,
   `*.push.apple.com`, `*.notify.windows.com`.
4. Contenido (JSON, < 3 KB): `{t: título, b: texto, k: "sentry"|"autos"|"test", tag, u: "./#sentry", ts}`.
   El `sw.js` lo enseña (`push`) y al tocarlo abre/enfoca la web en `u` (`notificationclick`).

Avisos del centinela: con cada evento del centinela (`Events.SENTRY` desde `SentryEngine.emit`) se avisa a las
suscripciones con `sentry != false`. Los de las reglas, a las que tienen `autos != false`.

iPhone: solo con la web añadida a la pantalla de inicio (iOS 16.4+). Si se borran los datos de la app del coche,
cambia su clave y hay que volver a activar los avisos (la web lo detecta por el `error: "clave"`).

## 4. Órdenes nuevas (`cars/{id}/commands`)

| action | payload | respuesta |
|---|---|---|
| `runAutomation` | `{id}` | hace ya las acciones de la regla (sin mirar disparador ni condiciones, sí la seguridad): `{ok, results}` |
| `pushTest` | `{sub?}` | aviso de prueba a esa suscripción (o a todas): `{sent, failed, error?}` |

## 5. Pruebas

- PC: `gradle testDebugUnitTest --tests 'com.bydlink.carapp.auto.*'` (motor, seguridad, Base64, cifrado con el
  vector del RFC 8291, firma VAPID). `WebPushLiveTest` manda un aviso de verdad si existe `build/push-live.json`
  (lo crea `scratchpad/e2e/push-live.mjs` suscribiendo un Chrome).
- Web: `scratchpad/e2e/autos-test.mjs` con la demo (`?demo#settings`).
