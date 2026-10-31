# Contribuir a BYDLink

¡Gracias por querer ayudar! Esto es un proyecto de comunidad para dueños de coches BYD.

## Cómo está montado

- **`phone/`** — App móvil web (HTML/CSS/JS sin framework, sin paso de build). Fácil de tocar:
  edita y recarga. La capa `backend.js` abstrae de dónde vienen los datos (demo / cloud / firebase).
- **`backend/`** — Servidor Node de pruebas con datos simulados (Express). `npm start` (puerto 8099).
- **`car-app/`** — App Android (Kotlin, Gradle) para la pantalla BYD. Aquí está lo específico de BYD:
  - `car/CarShell.kt` — permisos por ADB local (`dadb`, localhost:5555).
  - `car/BmmCameraLoader.kt` — carga las cámaras BMM por reflexión.
  - `car/BydGateway.kt` — lectura/control por el SDK `android.hardware.bydauto.*`.
  - `byd-stubs/` — firmas del SDK para compilar (se afinan con el coche real).
- **`firebase/`** — reglas de seguridad y configuración.

## En qué hace falta ayuda (lo más valioso)

1. **Probar en distintos modelos y versiones de DiLink.** El método de cámaras es genérico
   pero las firmas por reflexión y las rutas de `bmmcamera.jar` varían. La app del coche trae
   un **diagnóstico** (`MainActivity`): comparte su salida para tu modelo (abre un issue).
2. **Afinar las firmas del SDK BYD** (`byd-stubs/` y `BydGateway.kt`) con datos reales
   (herramienta útil: `byd-probe` del ecosistema byd-apps).
3. **Vídeo**: puente preview→MediaCodec→WebRTC, y detección de movimiento para el centinela.
4. **Multiusuario**: cuentas (Firebase Auth) y emparejamiento por código.

## Flujo de trabajo

1. Haz un fork y una rama por cambio (`feat/...`, `fix/...`).
2. Mantén el estilo del código de alrededor. La app móvil es vanilla JS a propósito (sin build).
3. Explica en el PR **en qué coche/DiLink lo has probado** (o si no has podido probar en coche).
4. No subas secretos: `phone/firebase-config.js`, `.firebaserc` ni claves de servicio están en
   `.gitignore`. Cada quien usa su propia config de Firebase (ver `firebase/config.template.js`).

## Seguridad y responsabilidad

Esto accede a la electrónica de un coche real. Prueba con cuidado y **en tu propio vehículo**.
Nunca envíes comandos sin entender qué hacen. Si descubres algo arriesgado, avisa en un issue.

## Licencia

Al contribuir, aceptas que tu aportación se publique bajo **GPLv3** (la del proyecto).
