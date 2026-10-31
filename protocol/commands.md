# Protocolo BYDLink (sobre Firebase Realtime Database)

No hay servidor propio. El coche y el móvil comparten un nodo en la Realtime Database
bajo `/cars/{carId}`. El coche **escribe** su estado; el móvil lo **lee en tiempo real**.
Para acciones (cerrar, clima, asientos, cámara) el móvil escribe un comando y el coche
responde. Campos tomados del análisis de la app Electro (nombres propios aquí).

## Estructura en la base de datos

    /cars/{carId}
      meta:     { name, model, online, lastSeen, appVersion }
      status:   {
                  ts, soc, charging, chargingPower, chargeRestMin,
                  gear ("P"|"R"|"N"|"D"), speed, locked,
                  doors:      { fl, fr, rl, rr, trunk, hood },   // true = abierta
                  seatbelts:  { driver, passenger },
                  outTemp, tempUnit,
                  odometer,
                  location:   { lat, lng, heading, ts }
                }
      config:   {
                  sentry:        { on, workMode, sensitivity, proximityDetection, shadowFilter },
                  dashcam:       { on, hasOEM, oemLimitEnabled, oemStorageLimit },
                  notifications: { enabled, flash, gif, sentryMovement, doorOpenClose },
                  overlays:      { soc, seatbelt, speed },
                  safeLocations: { enabled }
                }
      seats:    { driverStage, passengerStage }      // -2..2 (negativo = ventilación, positivo = calefacción)
      cameras:  { count, list: [ { id, tag, name } ] }
      trips/{tripId}:        { start, end, distanceKm, durationSec, startAddr, endAddr }
      tripPoints/{tripId}:   [ { lat, lng, t, speed } ]
      sentryEvents/{eventId}:{ ts, camera, thumbUrl, videoId }
      recordings/sentry/{id}:{ ts, camera, durationSec, thumbUrl, size }
      recordings/dashcam/{id}:{ ts, durationSec, thumbUrl, size }
      commands/{cmdId}:      { action, payload, ts }   // escribe el MÓVIL
      responses/{cmdId}:     { ok, data, ts }          // escribe el COCHE
      signaling/{sessionId}: { offer, answer, ice: {...} }   // WebRTC

## Lecturas en tiempo real (el móvil se suscribe)

`status`, `config`, `seats`, `cameras`, `trips`, `sentryEvents`, `recordings`.
No hace falta pedir nada: el coche mantiene estos nodos al día y el móvil recibe cambios.

## Comandos (acciones del móvil → coche)

El móvil crea `/cars/{carId}/commands/{cmdId}` con `{ action, payload, ts }`.
El coche ejecuta y escribe `/cars/{carId}/responses/{cmdId}` con `{ ok, data, ts }`.

| Acción            | Payload                   | Efecto en el coche (vía SDK BYD)                       |
|-------------------|---------------------------|--------------------------------------------------------|
| `setLock`         | `{ locked }`              | `BYDAutoBodyworkDevice.lockDevice()`                   |
| `setClimate`      | `{ on, temp }`            | Clima                                                  |
| `setSeatComfort`  | `{ driver, passenger }`   | `setSeatHeatingState` / `setSeatVentilatingState`      |
| `loadSeatMemory`  | `{ slot }`                | Carga memoria de asiento                               |
| `setSentryConfig` | `{ on, sensitivity, ... }`| Config centinela                                       |
| `setDashcamConfig`| `{ on, ... }`             | Config dashcam                                         |
| `startLiveCam`    | `{ camera }`              | Abre cámara y arranca WebRTC                           |
| `switchLiveCam`   | `{ camera }`              | Cambia de cámara                                       |
| `stopLiveCam`     | —                         | Cierra cámara                                          |
| `downloadSentry`  | `{ id }`                  | Prepara descarga de grabación                          |

## Vídeo en vivo (WebRTC, directo coche→móvil)

Señalización en `/cars/{carId}/signaling/{sessionId}`:
el móvil pone `offer`, el coche pone `answer`, y ambos van añadiendo candidatos en `ice`.
El media (vídeo) viaja **directo** entre coche y móvil (STUN público; TURN opcional si
la red no permite P2P). El coche combina las 4 cámaras según `SwitchLiveCam`.
