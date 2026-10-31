# App del coche — puesta en marcha (fase 2)

Esta app corre en la **pantalla Android del BYD**. Estado actual: esqueleto completo
con la arquitectura, el puente a Firebase y la capa del SDK BYD (con *stubs* para
compilar). Faltan, y los hacemos juntos mañana:

1. Sustituir los stubs de `byd-stubs/` por el SDK real de BYD (vía ADB al coche).
2. Añadir `google-services.json` de Firebase.
3. Cámara en vivo (WebRTC) y centinela (OpenCV/TFLite) — fase 3.

## 0. Abrir el proyecto

Ábrelo con **Android Studio** (ya instalado). Deja que descargue Gradle y genere el
wrapper. SDK de Android ya en `C:\Users\Xavi\AppData\Local\Android\Sdk`.

## 1. Firebase (google-services.json)

1. En la consola Firebase → Configuración → Tus apps → **Añadir app Android**.
2. Nombre de paquete: `com.bydlink.carapp`.
3. Descarga `google-services.json` y colócalo en `car-app/app/`.
4. En `app/build.gradle.kts` descomenta el plugin `com.google.gms.google-services`.
5. En `App` → `BuildConfigCompat.CAR_ID` pon el mismo `CAR_ID` que la web.

## 2. Probar SIN el coche (en un móvil o emulador)

Con `BuildConfigCompat.USE_BYD_SDK = false` (por defecto) usa el coche **simulado**:
publica estado y mueve el GPS. Instálala y comprueba que el teléfono ve los datos por
Firebase. Así validamos todo el circuito antes de tocar el coche.

    adb install -r app/build/outputs/apk/debug/app-debug.apk

## 3. Sacar el SDK real de BYD (con ADB al coche) — MAÑANA

La app original usa `android.hardware.bydauto.*`, que es un framework del sistema BYD.
Para compilar contra el de verdad:

1. En la pantalla del coche, activar **Depuración USB** (o inalámbrica).
2. Conectar y autorizar:

       adb devices
       adb shell pm list packages | findstr byd      # ver paquetes BYD
       adb shell ls /system/framework | findstr -i byd

3. Localizar y extraer el/los jar del framework BYD:

       adb pull /system/framework/framework.jar
       # y cualquier byd*.jar / *bydauto*.jar que aparezca

4. Convertir y quedarnos con las clases `android.hardware.bydauto.*` como librería
   `compileOnly` (reemplaza `byd-stubs`). Con eso las firmas son las reales y
   ajustamos `BydGateway.kt`.
5. Poner `BuildConfigCompat.USE_BYD_SDK = true` y reinstalar en el coche.

> Nota: algunas acciones (memoria de asiento, ajustes seguros) necesitan permisos de
> firma del sistema BYD. La app original se los auto-concedía emparejándose por ADB
> inalámbrico (`libspake2`). Si hace falta, lo replicamos entonces; muchas lecturas
> (batería, puertas, velocidad, GPS) funcionan sin eso.

## 4. Autoarranque

El `BootReceiver` ya arranca el servicio al encender. En la pantalla BYD puede hacer
falta además permitir el autoarranque de la app en los ajustes del sistema.
