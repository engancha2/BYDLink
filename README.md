# BYDLink

**Convierte tu BYD en una cámara de seguridad y contrólalo desde el móvil.**

Proyecto **open source** (GPLv3) y de comunidad: una app que se instala en la pantalla de
tu coche BYD y, desde tu teléfono, te deja ver la telemetría, controlar el coche y usar sus
cámaras como **vigilancia (centinela) y dashcam** cuando está aparcado. Código 100% propio
e independiente; no es un fork ni una copia de ninguna app comercial, ni está afiliado a BYD.

Web del proyecto: **bydhack.com** · App: **app.bydhack.com**

## Qué hace

- 📍 **Telemetría en tiempo real**: batería/SoC, autonomía, carga, potencia, voltaje,
  temperatura, odómetro, **presión de neumáticos**, marcha, velocidad, puertas y ventanas.
- 🎛️ **Control remoto**: cerrar/abrir, **ventanas**, maletero, clima, asientos (calefacción/ventilación).
- 📷 **Cámaras en vivo**: las 4 cámaras del coche desde el móvil.
- 🛡️ **Centinela**: detección de movimiento alrededor del coche aparcado → aviso + grabación.
- 🎥 **Dashcam**: grabación en marcha.
- 🔌 **Historial de cargas**: cada ciclo con kWh, coste y gráfica.
- 🤖 **Automatizaciones**: p. ej. *subir las ventanas al cerrar el coche* (útil en modelos
  donde viene capado).

## Cómo funciona

```
┌─────────────┐        ┌──────────────┐        ┌──────────────────────────┐
│  App MÓVIL  │ ◄────► │   FIREBASE   │ ◄────► │     App del COCHE        │
│ (web / PWA) │ datos  │ (realtime +  │ datos  │ (pantalla BYD, siempre   │
│ app.bydhack │ eventos│  auth)       │ eventos│  activa, se auto-arranca)│
└─────────────┘        └──────────────┘        └──────────────────────────┘
      ▲                                                     │
      └────────── Vídeo en vivo: WebRTC P2P ────────────────┘
```

- La **app del coche** corre siempre en segundo plano (servicio en primer plano +
  auto-arranque al encender), lee el coche por el SDK de BYD y publica todo en Firebase.
- El **móvil** (web/PWA) lee en tiempo real y envía comandos. El vídeo va directo coche→móvil.
- **Emparejamiento por usuario**: cada quien registra su cuenta y su coche; los datos quedan
  aislados por las reglas de seguridad. **No se guardan contraseñas de BYD de nadie.**

### Cámaras (el método, model-agnostic)
Usa el framework de cámara **BMM** de BYD (`android.hardware.NormalCamera`/`AVMCamera`) cargado
por reflexión desde `bmmcamera.jar`, que existe en casi todas las cabeceras DiLink — por eso
funciona en la mayoría de modelos. Los permisos se conceden con **ADB local** (`dadb` a
`localhost:5555`), sin necesidad de firma de sistema. Detalle en
[`INGENIERIA_ELECTRO.md`](INGENIERIA_ELECTRO.md) y [`car-app/CAMARAS.md`](car-app/CAMARAS.md).

## Compatibilidad

Coches BYD con pantalla **DiLink**. Objetivo de referencia: **BYD Dolphin Surf**. El método
de cámaras es genérico, pero cada modelo/versión de DiLink puede variar: la app del coche
trae un **diagnóstico** que te dice qué soporta tu vehículo.

## Estructura

| Carpeta | Qué es |
|---|---|
| `phone/`     | App móvil (web/PWA). Modos: `demo` (sin nada), `cloud` (backend local), `firebase`. |
| `backend/`   | Servidor Node de pruebas (datos simulados). Opcional; el destino es Firebase. |
| `car-app/`   | App Android para la pantalla BYD (telemetría, control, cámaras, centinela). |
| `firebase/`  | Reglas de seguridad, plantilla de config y guía de alta. |
| `simulator/` | Página que simula el coche sobre Firebase (para probar sin coche). |
| `protocol/`  | Modelo de datos y comandos. |

Documentos: [`HALLAZGOS.md`](HALLAZGOS.md) · [`INGENIERIA_ELECTRO.md`](INGENIERIA_ELECTRO.md) ·
[`CONTRIBUTING.md`](CONTRIBUTING.md)

## Probar la app del móvil (sin coche)

Ábrela en `demo` sin instalar nada: abre `phone/index.html` en el navegador.
Para el modo nube local: `cd backend && npm install && npm start` → `http://localhost:8099`.
Para Firebase: ver [`firebase/SETUP.md`](firebase/SETUP.md).

## Estado

- [x] App móvil (web/PWA) completa, publicada y conectada a Firebase
- [x] Firebase: reglas, auth, datos
- [x] Ingeniería inversa del método de cámaras (BMM) documentada
- [x] App del coche: diagnóstico + auto-permisos (`dadb`) + carga de cámaras (scaffold)
- [ ] App del coche: telemetría real + control + WebRTC + centinela (en desarrollo)
- [ ] Cuentas de usuario + emparejamiento multiusuario
- [ ] Empaquetado y publicación

## Contribuir

¡Bienvenidas las contribuciones! Especialmente **probar en distintos modelos/DiLink** y
afinar las firmas del SDK por reflexión. Ver [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Licencia

**GPLv3** — ver [`LICENSE`](LICENSE). Puedes usar, estudiar, modificar y compartir; las
versiones derivadas deben seguir siendo libres bajo la misma licencia.

## Aviso legal

Para usar en **tu propio coche**. Es interoperabilidad con tu propio vehículo. El proyecto no
está afiliado, patrocinado ni respaldado por BYD. Úsalo bajo tu responsabilidad; nunca confíes
en estos datos para conducir.
