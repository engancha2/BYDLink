# Cámaras del coche — contrato coche ↔ móvil

Todo va por Firebase Realtime Database bajo `/cars/{carId}` (mismas reglas que el resto: escribe el
coche y el móvil vinculado). Los comandos usan el mecanismo de siempre (`protocol/commands.md`):

    móvil:  cars/{id}/commands/{cmdId}  = { action, payload, ts }
    coche:  cars/{id}/responses/{cmdId} = { ok, data, ts }
            si ok=false → data = { error: "mensaje en español para enseñar tal cual", code: "código estable" }

El coche responde **de verdad**: `ok:true` solo cuando la cámara ha dado imágenes (foto/directo).
Los comandos de cámara tardan como mucho ~10 s; en el móvil conviene un timeout de **15 s** para
`takePhoto`, `startLiveJpeg` y `startLiveCam` (12 s va justo).

**Seguridad:** la cámara solo se usa con el coche **parado en P y a 0 km/h**. Si se mete otra marcha
o se mueve, el coche la suelta al momento (≤ 0,3 s) y para el directo. Mientras tanto los comandos
responden `code: "not_parked"`.

**Seguridad del servicio (actualizaciones sin nadie delante):** la cámara del proceso principal
(centinela, foto, directo) **solo se abre si el test de cámara en proceso aparte ha salido
`OK_FRAMES` en este coche con esta misma versión de la app** (`cameras.test.ok`). Hasta entonces el
centinela por cámara está en pausa y `takePhoto`/`startLiveJpeg`/`startLiveCam` responden
`code: "testing"` («La cámara se está comprobando, prueba en un minuto.») y lanzan el test si no está
en marcha. Si el test no sale bien: `code: "test_failed"` (se reintenta solo cada 20 min, hasta 5 veces
por versión, o a mano con `camTest`).
Si aun así el proceso principal muere **2 veces seguidas en menos de 2 min (o 3 en 15 min) con la
cámara abierta**, la cámara del proceso principal queda **desactivada para esa versión**
(`cameras.disabled = "main_crash_loop"`, aviso en `diag/cam/mainCrash`). En ese estado la foto y el
directo JPEG se siguen sirviendo desde el proceso aparte (`remote: true` en la respuesta y en el frame);
el centinela y WebRTC quedan apagados. `camReset` la vuelve a activar.

---

## 1. Nodos de datos

| Ruta | Escribe | Contenido |
|---|---|---|
| `cameras` | coche | Estado y capacidades (abajo). Se actualiza al cambiar algo (máx. cada 1,5 s) y cada minuto con la cámara abierta. |
| `camera/photo` | coche | Última foto: `{ ts, camera, w, h, jpeg, bytes }` |
| `camera/live` | coche | Último frame del directo JPEG (se **sobrescribe**): `{ ts, seq, camera, w, h, jpeg }` |
| `camera/liveState` | coche | `{ state: "on"\|"off", camera, fps, reason, seq, maxSec, heartbeatSec, ts }` |
| `camera/viewer` | **móvil** | Latido del directo JPEG: cualquier valor que cambie (usar `ServerValue.TIMESTAMP`) cada ≤ 10 s |
| `sentryEvents/{key}` | coche | Evento ligero: `{ ts, camera, videoId, hasThumb, score, clip? }` |
| `sentryThumbs/{key}` | coche | Miniatura del evento, **misma key**: `{ ts, camera, w, h, jpeg }` (JPEG ≈ 320×240, < 15 KB). Solo se guardan las 50 últimas. |
| `signaling/{session}/…` | ambos | WebRTC (sección 4). El coche añade `signaling/{session}/car` con su estado. |
| `iceConfig` | admin | Opcional: servidores TURN `{ iceServers: [ { urls: [..], username, credential } ] }`. Lo leen coche y móvil. |
| `diag/cam` | coche | Resultado del test de cámara (sección 5) |
| `diag/camlog` | coche | `{ ts, text }`: últimas 300 líneas del log de cámara |
| `diag/bmmjar` | coche | `{ path, size, sha256, b64, ts }`: el `bmmcamera.jar` del coche (una vez), para analizarlo |

`jpeg` es siempre **base64 sin cabecera**: `img.src = 'data:image/jpeg;base64,' + v.jpeg`.
`ts` es la hora del servidor (`ServerValue.TIMESTAMP`).

### `cameras`
```js
{
  available: true,               // false = este coche no tiene cámaras BYD (bmmcamera.jar)
  jar: "/system/framework/bmmcamera.jar",
  kind: "avm4" | "single" | "unknown",   // 360 con 4 cámaras | solo trasera
  tag: "pano_h", id: 0, api: "avm" | "normal",
  state: "idle" | "opening" | "starting" | "streaming" | "retry" | "error" | "unavailable"
       | "not_parked" | "settling" | "testing" | "pending_test" | "test_failed" | "disabled",
       // settling = acaba de aparcar: el centinela espera 20 s antes de abrir (la 360 del coche puede seguir encendida)
  code: "open_refused", lastError: "mensaje en español",      // último error (si hay)
  parked: true,
  consumers: ["sentry", "jpeg"],    // quién usa la cámara ahora: sentry, live, jpeg, photo, clip
  fps: 25.0, frames: 12345,
  layout: { w: 5120, h: 960, fmt: 21, kind: "semi", grid: "4x1", tiles: 4, tileW: 1280, tileH: 960, nv12: false, bytes: 7372800 },
  views: ["front", "back", "left", "right", "mosaic"],   // ← botones que debe enseñar el móvil
  mode: "strip" | "channels",
  map: { front: { view: 0, tile: 0 }, back: { view: 0, tile: 1 }, ... },
  profile: { ... },                // perfil de cámara (lo fija el test; ver setCamProfile)
  sentry: { on: true, events: 3 },
  live: { jpeg: false, jpegCamera: "front", webrtc: false, session: null },
  test: { verdict: "OK_FRAMES", versionCode: 5, ts: 1761930000000, runs: 1, running: false, ok: true },
  block: null,                      // testing | pending_test | test_failed | disabled (si no es null, no se abre aquí)
  disabled: null,                   // "main_crash_loop" si se desactivó en esta versión
  deaths: [], remote: null,         // muertes recientes con la cámara abierta; "photo"/"live" si va por el proceso aparte
  versionCode: 5,
  count: 5, list: [ { id: "front", tag: "pano_h", name: "Frontal" }, ... ],   // compatibilidad con el formato viejo
  ts: 1761930000000
}
```
Nombres de cámara: `front` (Frontal), `back` (Trasera), `left` (Izquierda), `right` (Derecha) y
`mosaic` («4 en 1», las cuatro en 2×2). El coche también acepta `rear`, `trasera`, `frontal`, etc.

---

## 2. Foto bajo demanda

    sendCommand('takePhoto', { camera: 'front', w: 640, quality: 70 })   // w y quality opcionales
    → ok, data = { camera, w, h, bytes, path: "camera/photo" }

Después leer **una vez** `camera/photo` (`once('value')`) y pintar `jpeg`. 640×480 a calidad 70 ≈ 40–70 KB.
Si el coche no estaba usando la cámara, la abre, espera a que se estabilice la imagen (~1 s) y la cierra.

---

## 3. Directo «foto a foto» (JPEG por Firebase) — **funciona con cualquier red**

    sendCommand('startLiveJpeg', { camera: 'front', fps: 1 })       // fps 0.2–2 (por defecto 1)
    → ok, data = { camera, fps, path: "camera/live", viewerPath: "camera/viewer",
                   heartbeatSec: 20, maxSec: 180, views: [...], layout: {...} }

1. Escuchar `camera/live` con `on('value')` → `img.src = 'data:image/jpeg;base64,' + v.jpeg`
   (`seq` sube en cada frame; ≈ 480×360, 15–35 KB).
2. **Latido**: escribir `camera/viewer = firebase.database.ServerValue.TIMESTAMP` nada más empezar y
   cada 5–8 s. Si el coche no ve cambiar ese valor en **20 s**, para solo (`reason: "sin_latido"`).
3. El directo dura **3 min** como máximo (`reason: "max_3min"`). Volver a mandar `startLiveJpeg`
   (con la misma u otra cámara) lo renueva otros 3 min sin cortar.
4. Cambiar de cámara **sin reabrir**: `switchLiveJpeg { camera }` (o `switchLiveCam { camera }`, que cambia los dos directos).
5. Parar: `stopLiveJpeg {}` (también al ocultar la página: `visibilitychange`).
6. `camera/liveState` dice si sigue (`state: "on"`) o por qué paró (`state: "off"`, `reason`:
   `sin_latido`, `max_3min`, `not_parked`, `testing`, `móvil`, `sin_frames`, `servicio parado`).

Consumo: 1 fps ≈ 70–120 MB/h de descarga de RTDB (plan gratuito: 10 GB/mes para todo el proyecto).
Por eso por defecto 1 fps y 3 min.

---

## 4. Directo WebRTC (vídeo fluido; puede no conectar sin TURN)

Igual que ahora en `phone/webrtc.js`:
1. El móvil crea `session = 'live-' + Date.now()`, su `RTCPeerConnection` (recvonly) y escribe
   `signaling/{session}/offer = { type, sdp }`.
2. Después `sendCommand('startLiveCam', { session, camera })`
   → ok, data = `{ camera, session, views, layout }` (cuando ya hay imágenes) o error.
3. El coche responde `signaling/{session}/answer` y va añadiendo `signaling/{session}/ice/car/*`;
   el móvil añade los suyos en `ice/phone/*` (el coche los guarda en cola hasta tener la oferta).
4. Estado del coche en `signaling/{session}/car = { ice, pc, pair, cands: {host, srflx, relay}, camera, frames, stopped, ts }`.
   `pair` = tipos del par elegido, p. ej. `"srflx/srflx"` (directo) o `"relay/…"` (por TURN).
5. `switchLiveCam { camera }` cambia de cámara sin reabrir; `stopLiveCam {}` para.
6. El coche para solo si no conecta en 30 s (`stopped: "ice_timeout"`), si falla (`ice_failed`),
   si se cae 15 s (`ice_lost`), a los 10 min (`max_10min`) o si deja de estar parado (`not_parked`).

Vídeo: 640×480 (o 640×360 si la cámara es 16:9), ≤ 15 fps, 150–600 kbps, H.264/VP8.
**Recomendado en el móvil:** si en 10–12 s `pc` no llega a `"connected"` (o pasa a `failed`) →
`stopLiveCam` y pasar a **JPEG** (sección 3) avisando «sin conexión directa, modo foto a foto».
TURN: si existe `cars/{id}/iceConfig`, el coche lo usa; el móvil debe añadir los mismos servidores.

---

## 5. Test de cámara (diagnóstico remoto)

El coche lo ejecuta **solo, una vez por versión de la app**, en cuanto lleva 30 s parado (si no sale
`OK_FRAMES`, se repite cada 20 min, como mucho 5 veces por versión; `CRASHED`, `NO_JAR`, `JNI_FAIL` y
`NO_VALID_CAMERA` no se repiten solos). Sin un `OK_FRAMES` de esta versión la cámara no se usa en el
servicio principal. También a mano:

    sendCommand('camTest', {})        → ok, data = { started: true, etaSec: 75, path: "diag/cam" }

Dura ≤ 75 s, corre en un proceso aparte (si la librería de BYD peta, no tumba la app) y, mientras,
`cameras.state = "testing"` y los demás comandos responden `code: "testing"`. Resultado en `diag/cam`:

```js
{
  verdict: "OK_FRAMES" | "NO_JAR" | "JNI_FAIL" | "NO_VALID_CAMERA" | "OPEN_NULL" | "BUSY" | "NO_FRAMES"
         | "CRASHED" | "TIMEOUT" | "ABORTED_NOT_PARKED" | "START_FAIL" | "ERROR",
  verdictMsg: "texto en español",
  proposed: { ...perfil... },        // si OK_FRAMES: el coche lo aplica solo (salvo perfil manual)
  env, jar, props, libs, classes, info, others, camera2, target,
  runs: [ { name: "A"|"B"|"C"|"D"|"F"|"P"|"S", api, id, open, openMs, calls, pw, ph, frames, fps, firstFrameMs,
            meta: { w, h, fmt, dataSize, bufLen, view, ts }, events: [...], geom, fp, meanLuma, thumbs: [claves] } ],
  channels: [ { view: 1..5, enable, frames, fps, meta, geom, fp, thumbs: [clave] } ],
  thumbs: { "A_t0": b64, "A_t1": b64, ..., "A_full": b64, "A_t0_nv12": b64, "ch1": b64, ... },
  logcat: "…", adb: { getenforce, getprop, packages, top, libs, ps },
  mainCrash: { reason, versionCode, deaths },     // solo si la cámara del proceso principal está desactivada
  durationMs, wallMs, version, versionCode, reason, lastStep, uploadedAt
}
```
Para enseñarlo: veredicto + `verdictMsg` + una rejilla con `thumbs` (cada clave es una imagen:
`A_t0..t3` = cada trozo de la tira, `A_full` = el frame entero, `A_t0_nv12` = el trozo 0 con el
croma al revés, `chN` = canal N).

---

## 6. Calibrar desde el móvil (Ajustes avanzados)

    sendCommand('setCamProfile', { cams: { front: 0, back: 1, left: 2, right: 3 } })   // trozo de la tira por cámara
    sendCommand('setCamProfile', { cams: { front: { view: 1, tile: 0 }, ... } })        // modo canales
    sendCommand('setCamProfile', { nv12: true })          // si la gente sale azul
    sendCommand('setCamProfile', { grid: '2x2' })         // auto | 4x1 | 2x2 | 2x1 | 1x1
    sendCommand('setCamProfile', { reset: true })         // volver al perfil por defecto
    → ok, data = { profile }
Otros campos: `api` (auto|avm|normal), `cameraId`, `previewW`/`previewH`, `restart`, `surface`.
Un perfil puesto a mano no lo pisa el test automático.

    sendCommand('camReset', { profile: false, retest: false })   // quita la desactivación por crash; retest: olvidar el test (se repite)
    sendCommand('getCamStatus', {})                               // lo mismo que el nodo `cameras`
    sendCommand('dumpCamLog', {})                                 // sube el log a diag/camlog

---

## 7. Centinela

Igual que hasta ahora se activa con `setSentryConfig { on, sensitivity }` (1..10). Con el coche parado
(empieza 20 s después de aparcar), el coche vigila **las 4 cámaras** a 4 análisis/s. En cada detección:

1. `sentryThumbs/{key} = { ts, camera, w, h, jpeg }` (miniatura < 15 KB; se guardan las 50 últimas).
2. `sentryEvents/{key} = { ts, camera, videoId, hasThumb: true, score, clip? }` — **misma key**.

El móvil sigue listando `sentryEvents` (`orderByChild('ts').limitToLast(60)`), y solo cuando haga falta
(al ver el elemento o al tocarlo) lee `sentryThumbs/{id}` con `once('value')`. Si `hasThumb` es false
o la miniatura ya se borró (más de 50), enseñar el icono de siempre.

El centinela solo funciona cuando el test de esta versión ha salido `OK_FRAMES` y la cámara no está
desactivada (`cameras.block == null`).

Clip: si el coche puede, graba 15 s en MP4 (640×480, 10 fps) de la cámara que disparó, en
`/sdcard/BYDLink/Sentry/AAAA-MM/DD/HHmmss_camara.mp4` o, si Android no deja, en
`/sdcard/Android/data/com.bydlink.carapp/files/Sentry/AAAA-MM/DD/`. `clip` = ruta en el coche,
`videoId` = `"AAAA-MM/DD/HHmmss_camara"`. (Descargarlo desde el móvil: pendiente, comando futuro `getClip`.)

Límites: como mucho un evento cada 30 s y 20 por hora.

---

## 8. Códigos de error (`data.code`)

| code | Qué pasa | Qué hacer en el móvil |
|---|---|---|
| `not_parked` | Coche no parado en P / en marcha | Enseñar el mensaje; reintentar al aparcar |
| `testing` | Se está comprobando la cámara (o es el primer uso de esta versión) | «La cámara se está comprobando, prueba en un minuto» |
| `test_failed` | El test de esta versión no salió bien: por seguridad no se usa | Ofrecer «Probar cámara» (`camTest`) y enseñar `diag/cam` |
| `no_bmm` | El coche no tiene cámaras BYD | Ocultar la sección de cámaras (`cameras.available=false`) |
| `jni` | No carga la librería nativa | Mostrar error; pedir `camTest` |
| `open_refused` | El coche no deja abrir la cámara (otra app la usa / id inválido) | Reintentar más tarde |
| `busy` | El coche ha reclamado la cámara (su 360) o ya hay un test | Reintentar en 20–60 s |
| `no_frames` | Abre pero no llegan imágenes | `camTest` |
| `no_permission` | Evento 1006 (sin permiso de cámara) | Volver a conceder permisos (pantalla del coche) |
| `disabled` | Cámara del servicio principal desactivada en esta versión (murió con ella abierta). Solo afecta a WebRTC: foto y JPEG van por el proceso aparte | Usar JPEG; `camReset` para reactivar |
| `bad_camera` | Esa cámara no existe en este coche | Usar `cameras.views` |
| `bad_args` | Falta un dato (p. ej. `session`) | — |
| `webrtc` | WebRTC no se pudo iniciar en el coche | Pasar a JPEG |
| `error` | Otro | Enseñar `error` |

## 9. Vídeos guardados (app del coche 2.25)

Dónde: el coche elige entre sus soportes (`internal` = memoria del coche, o el id de una tarjeta SD / memoria USB).
Carpeta `BYDLink/{Centinela,Dashcam,Grabaciones}/AAAA-MM/DD/HHmmss_cámara.mp4`. En la memoria del coche es
`/sdcard/BYDLink` (si hay permiso de almacenamiento, lo da el ADB); en SD/USB Android 10 solo deja la carpeta de la app
(`Android/data/com.bydlink.carapp/files/BYDLink`, se borra al desinstalar). Id de un vídeo: `Carpeta/AAAA-MM/DD/HHmmss_cámara`.

Publica el coche:
- `cars/{id}/video`: `{store, current, missing, quotaGb, quotaBytes, mode, quality, stamp, segMin, volumes:[{id, kind
  (internal|sd|usb), label, free, total, used, lasting, path}], recording:[{kind, camera, since, until, file}],
  dashcam:{holding, recording, driving}, usable, sending, ts}`.
- `cars/{id}/videos`: `{ts, items:[{id, kind (centinela|dashcam|manual), camera, ts, bytes, volume}]}` (los 150 más nuevos).

Comandos:
| acción | payload | respuesta `data` |
|---|---|---|
| `recordVideo` | `{camera?, seconds? (60; 0 = hasta parar, máx. 1800)}` | `{recording, camera, seconds, id, store}` o `{already:true}` |
| `stopVideo` | — | `{stopped}` |
| `listVideos` | `{limit?}` | `{videos:[…]}` |
| `deleteVideo` / `keepVideo` | `{id}` | `{deleted}` / `{id}` (nuevo id en Grabaciones) |
| `getVideo` | `{id, req}` | `{path:"videoDl", req, bytes, parts}`; luego el coche escribe `videoDl/{req,id,bytes,n,done,state,error}` y `videoDl/parts/{i}` (base64 de 512 KB). El móvil baja los trozos, junta el MP4 y borra `videoDl`. Máx. 60 MB. |
| `setVideoConfig` | `{store?, quotaGb? (0 = auto), mode? (off|parked|drive|always), quality? (normal|alta), stamp?, segMin? (1..10)}` | estado como `video` |
| `getVideoStatus` | — | estado como `video` |

Grabación continua: `parked` graba aparcado en tramos de `segMin` minutos; `drive` (en pruebas) solo abre la cámara por
encima de 15 km/h y la suelta por debajo de 8 (nunca con la R); con la cámara abierta en marcha el centinela no analiza.
Al pasar del límite (o con menos de 500 MB libres) se borra lo más viejo: dashcam, luego centinela y lo último las grabaciones.
