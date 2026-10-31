# Ingeniería inversa de Electro (br.com.rory.electro) — cómo funciona por dentro

APK analizado: `electro app\2025-11-04-690a6306ec9f5.apk` (v1.5.8, para pantalla BYD).
Objetivo: entender **cómo lo hace** para reconstruirlo con código propio. Esto es lo que
más te interesa: **cómo accede a las cámaras** (funciona en casi todos los DiLink).

## 1. Estructura y protección

- El Java visible (smali) es solo un **cascarón**: la lógica real está **empaquetada y
  cifrada en librerías nativas** y se carga con `NativeLoader.classesInit0()`.
  - `libelectropkg.so` (808 KB) — la lógica principal (coche, cámaras, red, centinela).
  - `libnative-lib.so` — procesado de imagen (YUV, OpenCV MOG2).
  - `libelectrolib.so` — utilidades/salud.
  - `libjingle_peerconnection_so.so` — WebRTC.
  - `libtensorflowlite_jni.so` — TensorFlow Lite (clasificación en centinela).
  - `libspake2.so` — emparejamiento ADB inalámbrico (auto-concesión de permisos).
- Licencia de servidor (`LicenseExpired`, `UpdateRequired`). **No lo copiamos; lo
  reconstruimos.**

## 2. Comunicación con el coche (SDK BYD)

Usa `android.hardware.bydauto.*` vía JNI. Firmas de callbacks **confirmadas** del smali
(clases que heredan de los listeners):

- Carga: `AbsBYDAutoChargingListener.onChargerStateChanged(int)`, `onChargingPowerChanged(double)`
- Carrocería: `AbsBYDAutoBodyworkListener.onDoorStateChanged(int,int)`, `onPowerLevelChanged(int)`, `onAutoSystemStateChanged(int)`
- Asientos/instrumentos: `AbsBYDAutoInstrumentListener.onSafetyBeltStatusChanged(int,int)`
- Marcha: `AbsBYDAutoGearboxListener.onGearboxAutoModeTypeChanged(int)`
- Dispositivos por `getInstance(Context)`: ac, bodywork, charging, gearbox, instrument, light, setting, speed, statistic.
- Métodos usados: `getCurrentSpeed`, `getGearboxAutoModeType`, `getBatteryPowerVoltage`,
  `getChargingRestTime`, `getOutCarTemperature`, `getTemperatureUnit`, `getSafetyBeltStatus`,
  `setSeatHeatingState`, `setSeatVentilatingState`, `lockDevice`, `registerListener`.

## 3. ⭐ CÁMARAS — el método de Electro (clave, model-agnostic)

**Electro NO usa la API `BYDAutoPanoramaDevice`** (esa está bloqueada por permisos de
servidor para apps de terceros). En su lugar usa el framework de cámara **BMM** de BYD:

### Clases (del `bmmcamera.jar` del sistema)
- `android.hardware.NormalCamera`  (una cámara) + `NormalCamera$IPreviewCallback`, `$IEventCallback`
- `android.hardware.AVMCamera`     (360/around-view) + `AVMCamera$IPreviewCallback`, `$IEventCallback`
- `android.hardware.BmmCameraInfo` (info/tags de cámaras)
- `android.hardware.JNIBMMCamera`  (capa JNI nativa)

### Cómo las carga (esto es lo que lo hace universal)
En **código nativo** (`libelectropkg.so`, su `BmmCameraLoader`):
1. Construye en runtime la ruta del jar del sistema: **`/system/framework/bmmcamera.jar`**
   (la cadena aparece troceada en el binario: `…ework/bmmca…` + `/bmmca`).
2. Lo carga por reflexión con **`dalvik/system/BaseDexClassLoader` + `DexFile` + `loadClass`**.
3. Así obtiene `NormalCamera`/`AVMCamera` **aunque no estén en el classpath** de la app y
   **sin** declarar `uses-library` en el manifest.

> Por qué funciona en casi todos los DiLink: `bmmcamera.jar` y
> `android.hardware.NormalCamera/AVMCamera` existen en prácticamente todas las cabeceras
> BYD (es SU framework de cámara). No depende del SoC, a diferencia de `FAST_CAM_CAPTURE`
> (que es solo DiLink 5.0 / SA8155P).

### API de cámara que usa
`getCameraNumbers()`, `getCameraId()`, `getValidCameraTag()`, `getTag()`, `open(...)`,
`setPreviewSize(w,h)`, `getPreviewWidth/Height()`, `setCameraFps(fps)`,
`setPreviewSurface(Surface)` / `addPreviewSurface(...)`,
`setPreviewCallback(IPreviewCallback)` / `enablePreviewCallback()` / `disablePreviewCallback()`,
`setMediaCodec(MediaCodec,...)` / `setMediaCodecFps(...)`, `setEventCallback(IEventCallback)`,
`startPreview()`, `stopPreview()`.

## 4. Pipeline de vídeo (en vivo, dashcam y centinela)

```
NormalCamera/AVMCamera.startPreview()
      │  IPreviewCallback  → frames YUV (NV21)
      ▼
YuvUtils (nativo, libnative-lib.so):
   convertNV21ToNV12/I420, cropNV21..., normalizeYChannel/CLAHE,
   combineCamerasTo2x2GridNV21ToNV12  ← junta las 4 cámaras en una rejilla 2x2
      │
      ├──► [CENTINELA] MOG2Utils (OpenCV, nativo): applyMOG2, getForegroundPercentage,
      │       getMotionVerticalCenter, applyShadowFilter  → detecta movimiento
      │       (+ TensorFlow Lite para filtrar qué se mueve)
      │
      ▼
MediaCodec (H.264 por hardware): createInputSurface, getInputBuffer/OutputBuffer,
   MediaCodec$BufferInfo, releaseOutputBuffer   (+ EGL/OpenGL para marca de agua/overlay:
   overlaySOC, overlaySpeed, overlaySeatbelt)
      │
      ├──► WebRTC (libjingle)  → cámara EN VIVO al móvil (peer-to-peer)
      └──► archivo .mp4 en /sdcard/Electro/Sentry/AAAA-MM/DD/  (dashcam/centinela)
```

## 5. Permisos (cómo se los salta)

Las cámaras BMM y varios `bydauto` piden permisos de firma/sistema. Electro se los
**auto-concede** emparejándose por **ADB inalámbrico** (`libspake2.so` = SPAKE2, el
protocolo de emparejamiento de depuración de Android) y usando `WRITE_SECURE_SETTINGS` /
`pm grant`. Por eso el manifest pide `WRITE_SECURE_SETTINGS`, `READ_LOGS`,
`REQUEST_INSTALL_PACKAGES`, y tiene `ADBAuthActivity`/`EnableUSBDebugActivity`.

## 6. Cómo lo replicamos (plan para BYDLink)

Para la app del coche, un `BmmCameraLoader` propio en Kotlin:
1. `DexClassLoader("/system/framework/bmmcamera.jar", …)` → cargar `NormalCamera`/`AVMCamera`
   por reflexión (fallback a `AVMCamera` para la vista 360 / 4 cámaras).
2. `getCameraNumbers()` + `getValidCameraTag()` → abrir cámara(s).
3. `setPreviewSize` + `setPreviewCallback` → recibir frames YUV.
4. Frames → `MediaCodec` (H.264) → **WebRTC** (en vivo al móvil) y/o `.mp4` (dashcam).
5. Centinela: detección de movimiento propia (OpenCV MOG2 o equivalente) sobre los frames.
6. Permisos: concederlos por **ADB** (lo preparas tú), como hace Electro.

> Compatibilidad: este método (BMM) es el de Electro y es el que conviene para el
> **Dolphin Surf**, porque no depende del SoC. Lo confirmamos en tu coche por ADB:
> comprobar que existe `bmmcamera.jar` y cuántas cámaras da `getCameraNumbers()`.

## 7. Receta CONFIRMADA por otras apps (Vektra, TripRecorderNG, SealNav)

El material que fuiste añadiendo confirma y mejora el método de Electro. **SealNav** es
casi la misma app que queremos (cámara BMM + WebRTC + auto-ADB + SDK BYD), y es la mejor
referencia. Receta definitiva, **model-agnostic**:

### a) Auto-concesión de permisos con `dadb` (en vez de spake2 nativo)
Vektra y SealNav usan la librería **`dadb`** (cliente ADB en Kotlin puro). En vez de firma
de sistema:
1. Generan/guardan un `AdbKeyPair` (RSA) en archivos de la app.
2. `Dadb.create("localhost", 5555, keyPair)` → se conectan al **ADB del propio coche**.
3. Ejecutan `shell` (`AdbShellStream`): `pm grant …`, `settings put …`, `cmd …` para
   concederse los permisos BYDAUTO/cámara y obtener nivel `shell`.
   (El usuario activa "depuración ADB por red" una vez; es lo que prepararás por ADB.)

### b) Cargar el framework de cámara buscándolo en TODAS las particiones
SealNav prueba varias rutas (según modelo/DiLink el jar está en un sitio u otro):
```
/system/framework/bmmcamera.jar
/system_ext/framework/bmmcamera.jar
/product/framework/bmmcamera.jar
/odm/framework/bmmcamera.jar
/vendor/framework/bmmcamera.jar
(y además  /system/framework/avmcamera.jar , bydcamera.jar)
```
→ `DexClassLoader(rutaEncontrada)` → `AVMCamera` (vista 360/4 cámaras) o `NormalCamera`.
Degradar con elegancia si falta el SDK (como hace SealNav: *"no SDK on this car"*).

### c) Vídeo
Frames YUV del `IPreviewCallback` → `MediaCodec` (H.264, "requiere alineación 2x2") →
**WebRTC** (`org.webrtc.MediaCodecVideoDecoderFactory`) al móvil, y/o `.mp4`.

### ⭐ SentryEV 1.2.3 — LA referencia de la app del coche (hace casi todo lo que queremos)
Es Electro rehecho con el stack moderno y abierto. Confirma toda la receta:
- **Centinela**: `startSentryMode`, `triggerHardwareSnapshot`, `turnBacklightOff` (apaga la
  pantalla mientras vigila), snapshots por hardware.
- **Cámara**: `AVMCamera.open`, `SurfaceTexture bound to AVMCamera successfully!`,
  `AVMCamera$IPreviewCallback`, `BmmCameraInfo`; comprueba `bmmcamera.jar` (model-agnostic).
- **Vídeo en vivo**: WebRTC con `MediaCodecVideoEncoder/Decoder` (H.264).
- **Auto-ADB**: `dadb` a `localhost:5555`, genera `.android/adbkey`, whitelist de autoarranque
  (`applyBydAutostartWhitelist`), avisa al usuario ("toque ABRIR ADB").
- **SDK BYD amplio y robusto**: `BYDAutoManager`/`BYDAutoDeviceManager`, `BYDAutoFeatureIds`,
  `BYDAutoEventValue`, devices ac/light/seat/tyre/adas/diag/ota/set; maneja varias variantes
  del manager con degradación elegante.
- **Nube de emparejamiento**: `https://sentryev.com/api/pairing/start` y `/status/` para
  parear móvil↔coche (equivalente a lo que hace nuestro Firebase).

### Otras piezas útiles
- **TripRecorderNG**: viajes/rutas GPS (`LocationManager`), exporta `trips.csv`, lee el
  coche por **Binder** (`BinderVehicle`/`HelperVehicle`), `SideCamera`/`BlindSpot`.
- **Vektra**: cluster (velocidad, presión neumáticos `BYDAUTO_TYRE_*`, PM2.5, altitud),
  `dadb` a `localhost:5555`.

> **Conclusión:** con SentryEV + SealNav como referencia de la app del coche, el cliente de
> nube BYD (C#) para lo remoto, y nuestra app del teléfono ya hecha, **tenemos todo para
> construir sin más ingeniería inversa.**

> Con esto, **la cámara/centinela SÍ es viable en el Dolphin Surf** por el método BMM
> (no por FAST_CAM, que es DiLink 5.0). Lo único a verificar en tu coche (por ADB):
> qué `bmmcamera.jar` existe y cuántas cámaras expone.
