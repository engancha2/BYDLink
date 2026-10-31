# Backend BYDLink (provisional)

Backend **inventado** que alimenta la app del teléfono con datos simulados del coche y
acepta comandos. Es un puente temporal hasta migrar a Firebase (o a la nube BYD real con
`BydClient`). La lógica de datos es idéntica a la de la app (mismo formato).

## Arrancar

    cd bydlink/backend
    npm install        # solo la primera vez
    npm start

Arranca en **http://localhost:8099**. Al arrancar imprime también la URL para el **móvil**
(misma WiFi), p. ej. `http://192.168.5.160:8099`. Abre esa URL en el teléfono y ya ves la
app conectada al backend (modo "Nube (backend local)", sin el badge de demo).

> El puerto 3000 está ocupado en este PC, por eso usa el **8099**. Cambiable con
> `PORT=xxxx npm start`.

## Qué hace

- `GET /api/state` — estado completo (meta, status, config, seats, cameras, trips,
  sentryEvents, chargingSessions). Incluye ventanas y **neumáticos**.
- `GET /api/trip/:id/points` — puntos GPS de un viaje.
- `POST /api/command` `{ action, payload }` — ejecuta una acción y devuelve `{ ok, data }`.
- Sirve la app del teléfono (`../phone`) en la raíz `/`.
- Simula el coche: mueve el GPS, baja/sube SOC, lanza eventos de centinela.
- **Automatización** "subir ventanas al cerrar" implementada en el servidor
  (`setLock` con `locked:true` cierra las ventanas si hay alguna abierta).

## Modos de la app del teléfono

La app elige backend sola:
- Abierta como archivo (`file://…/phone/index.html`) → **demo** (todo en el navegador).
- Servida por este backend (`http://…:8099`) → **cloud** (consume esta API).
- Con `phone/firebase-config.js` real → **firebase**.

## Siguiente

- Migrar a **Firebase** (la app ya tiene el modo), o
- Sustituir el simulador por **BydClient** (C#) para datos reales de la nube BYD
  (necesita credenciales), o alimentar desde la **app del coche** (Fase 2).
