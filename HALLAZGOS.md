# BYDLink — Hallazgos y plan (tras revisar el material de `APP BYDlink\SDK`)

Has reunido 7 fuentes. Resumen de qué permiten y cómo encajan en tu app.

## Las fuentes

| Fuente | Qué es | Sirve para |
|---|---|---|
| `sdk_v1.0.5` | SDK **oficial** de BYD + docs (Javadoc, PDF de ejemplo) | referencia de firmas reales |
| `byd-apps-main` | Apps de cabecera (probe, pet-mode…) + **`BydPermissionContext`** + SDK real (`byd_sdk.dex`) + API ref verificada en un BYD Dolphin | app EN el coche sin firma de sistema |
| `byd-car-sdk-master` | SDK Kotlin **modular y limpio** (lectura CAN/HAL: batería, SoC, carga, marcha; + GPS/IMU) con `HiddenApiBypass`; compila y con tests | base de la app del coche (lectura) |
| `byd-php-client-main` | Cliente de la **nube de BYD** (PHP) — como la app oficial | GPS/estado/control remoto desde fuera |
| `BydClient-main` | El mismo cliente portado a **C#/.NET** | igual, más fácil en Windows |
| `FAST_CAM_CAPTURE-master` | Motor nativo que captura **las 4 cámaras** (Qualcomm AIS, zero-copy) en DiLink 5.0 | cámara en vivo, dashcam, centinela |

## Lo que cambia: hay DOS caminos que se complementan

### Camino A — Nube BYD (desde cualquier sitio, sin tocar el coche)
Usando tu **cuenta de la app BYD** (usuario/contraseña), el cliente (`byd-php-client` o
`BydClient` en C#) habla con los servidores de BYD igual que la app oficial. Da:

- 📍 **Ubicación GPS** del coche (`telemetry().gps()`) → "dónde está". El histórico
  "a dónde fue" lo guardamos nosotros registrando posiciones con el tiempo.
- 🔋 **Estado**: batería/SoC, carga, autonomía, temperatura, estado de puertas/ventanas/cierre.
- 🎛️ **Control remoto**: cerrar/abrir, clima on/off, maletero, luces, asientos, calentar batería.

Ventajas: **no hay que instalar nada en el coche, ni ADB**, y funciona desde cualquier lugar.
Pega con casi toda tu lista (ubicación, estado, control). **No** da cámaras.
Requiere: tus credenciales BYD + un pequeño backend (el cliente usa cifrado propio de BYD,
no se puede hacer sólo desde el navegador).

### Camino B — App en el coche (pantalla BYD)
App instalada en la cabecera que lee el coche por el SDK y hace de puente. Da lo de arriba
**más** lo que la nube no ofrece:

- 📷 **Cámara en vivo, dashcam y centinela** con `FAST_CAM_CAPTURE` (las 4 cámaras, zero-copy).
- Telemetría más rica y en tiempo real (`byd-car-sdk`).

Requiere: **ADB al coche** para instalar y conceder permisos (`BydPermissionContext` +
`pm grant`), y que la cámara funcione depende del hardware:
**`FAST_CAM_CAPTURE` es para DiLink 5.0 (Qualcomm SA8155P)**. Hay que confirmar qué DiLink
tiene tu coche (el APK de Electro apuntaba a Android 7 = DiLink más antiguo; el tuyo puede
ser más nuevo).

## Matriz de funciones (lo que querías)

| Función | Cómo | Camino | Dificultad |
|---|---|---|---|
| Dónde está el coche (GPS) | nube `telemetry().gps()` | A | fácil |
| A dónde fue (histórico) | registramos GPS con el tiempo | A | fácil |
| Estado (batería, carga, temp, puertas) | nube `telemetry().realtime()` | A | fácil |
| Cerrar/abrir, clima, maletero, luces, asientos | nube `ControlService` | A | fácil-media |
| **Cámara en vivo** | `FAST_CAM_CAPTURE` en el coche | B | alta (hardware) |
| **Centinela** (detección + aviso) | cámaras + detección propia | B | alta |
| **Dashcam** | cámaras + grabación | B | alta |

## Recomendación

**Híbrido, empezando por el Camino A (nube).** Da la mayor parte de lo que quieres rápido y
sin riesgo, reutilizando tu app del teléfono tal cual. Luego, el Camino B para cámaras/centinela.

### Plan
1. **Fase 1 (nube):** un backend pequeño que envuelve el cliente BYD (recomiendo el de **C#**,
   fácil en Windows) y expone JSON. La app del teléfono (ya hecha) añade un modo `cloud` que
   lo consume: ubicación + histórico, estado y control remoto. → **Necesito tus credenciales
   de la app BYD** para probarlo (mañana).
2. **Fase 2 (coche):** app Android basada en `byd-car-sdk` para telemetría + `FAST_CAM_CAPTURE`
   para cámaras/centinela. → Necesito **ADB al coche** y saber el **DiLink/modelo**.

## Decisiones que necesito de ti (mañana)
- ¿OK usar tu **cuenta BYD** para el Camino A? (es como la app oficial; las credenciales se
  quedan en tu backend, no se comparten).
- ¿Qué **modelo/DiLink** es tu coche? (define si `FAST_CAM_CAPTURE` vale para las cámaras).
- ¿Backend del Camino A en **C#/.NET** (mi recomendación en Windows) o PHP?

> Nota: todo esto es para TU coche y tu cuenta. Es interoperabilidad legítima; seguimos sin
> clonar la app Electro ni saltar su licencia.
