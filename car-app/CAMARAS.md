# Cámaras y auto-permisos en el coche (método Electro, model-agnostic)

Implementa lo documentado en `../INGENIERIA_ELECTRO.md`. Dos piezas:

## 1. `CarShell.kt` — auto-permisos por ADB local (`dadb`)
Se conecta al ADB del propio coche (`localhost:5555`) con la librería `dadb` y ejecuta
`shell` (igual que Vektra/SentryEV/SealNav). Sirve para concederse permisos sin firma de
sistema: `pm grant ... BYDAUTO_*_COMMON`, `WRITE_SECURE_SETTINGS`, autoarranque, etc.

Requisito (una vez, lo activa el usuario): **Depuración ADB por red** en la pantalla. La
primera conexión puede pedir aceptar un popup de ADB.

## 2. `BmmCameraLoader.kt` — cargar las cámaras BMM por reflexión
Busca `bmmcamera.jar` en varias particiones y carga `android.hardware.NormalCamera` /
`AVMCamera` con un `DexClassLoader`. Abre la cámara AVM (4 cámaras / 360) y dirige el
preview a un `Surface`. De ahí: `MediaCodec` (H.264) → WebRTC (en vivo) / `.mp4` (dashcam).

## Verificar qué soporta TU Dolphin Surf

La app del coche (`MainActivity`) hace un **diagnóstico automático** al abrirla e indica:
- si conecta por ADB local,
- qué `bmmcamera.jar` existe (y en qué partición),
- cuántas cámaras da `getCameraNumbers()`,
- si el SDK `android.hardware.bydauto.*` está presente.

Alternativa manual (sin instalar nada), por ADB desde el PC al coche:

    adb shell "ls -l /system/framework/bmmcamera.jar /system_ext/framework/bmmcamera.jar /product/framework/bmmcamera.jar /vendor/framework/bmmcamera.jar /odm/framework/bmmcamera.jar 2>/dev/null"
    adb shell pm list packages | findstr -i byd
    # y, para enumerar/testear todo el SDK y cámaras, instalar byd-probe (del repo byd-apps)

Con esa info ajustamos las firmas por reflexión (son aproximadas) y encendemos la cámara.

## Pendiente (fase 2, en el coche)
- Puente preview→MediaCodec→WebRTC (vídeo en vivo al móvil); la señalización puede ir por
  el backend/Firebase, como el pairing de SentryEV.
- Centinela: detección de movimiento sobre los frames (OpenCV MOG2 o equivalente) + aviso push.
- Grabación dashcam a `.mp4`.
