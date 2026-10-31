# Firebase — pasos EXACTOS (lo que haces tú)

Yo ya dejé hecho: reglas, `firebase.json`, `.firebaserc`, el modo `firebase` de la app, y el
simulador que hace de coche sobre Firebase. Tú solo tienes que hacer esto (una vez, ~10 min).

## 1. Crear el proyecto
1. Entra en https://console.firebase.google.com (con tu cuenta Google) → **Crear un proyecto**.
2. Nombre: `bydlink` (o el que quieras). Analytics: puedes desactivarlo. Crear.
3. Apunta el **Project ID** que te asigna (algo como `bydlink-1a2b3`).

## 2. Realtime Database
1. Menú izquierdo → **Compilación → Realtime Database** → **Crear base de datos**.
2. Ubicación: **europe-west1** (Bélgica) u otra EU. Empieza en **modo bloqueado**. Habilitar.
3. (Las reglas las subo yo con el CLI en el paso 6, o pégalas tú desde `firebase/database.rules.json`.)

## 3. Authentication
1. Menú → **Compilación → Authentication** → **Comenzar**.
2. Pestaña **Sign-in method** → habilita **Anónimo** → Guardar.

## 4. Registrar la app web y copiar la config
1. Rueda dentada (arriba izq.) → **Configuración del proyecto**.
2. Baja a **Tus apps** → icono **</>** (Web) → apodo `phone` → **Registrar app**.
3. Te muestra un bloque `const firebaseConfig = { apiKey: "...", ... }`. **Cópialo.**

## 5. Pegar la config (esto es lo único que me pasas a mí)
Pégame aquí ese bloque `firebaseConfig` **y tu Project ID**. Yo creo
`phone/firebase-config.js` con tus datos y pongo el Project ID en `.firebaserc`.
(O lo haces tú: copia `firebase/config.template.js` a `phone/firebase-config.js` y rellena.)

> Nota: la `apiKey` web de Firebase NO es secreta (va en el navegador). La seguridad la dan
> las reglas + Auth. Aun así, no subas `phone/firebase-config.js` a sitios públicos.

## 6. Publicar reglas + app (lo hago yo por CLI, o tú)
Con Node (ya lo tienes):

    npm install -g firebase-tools
    firebase login                       # abre el navegador para loguearte (esto SÍ lo haces tú)
    cd bydlink
    firebase deploy --only database       # sube las reglas
    firebase deploy --only hosting        # publica la app en https://TU-PROYECTO.web.app

## 7. Probar
- Abre `https://TU-PROYECTO.web.app` en el móvil (¡ya funciona desde fuera de tu WiFi!).
- Para simular el coche mientras no tenemos el real: abre `simulator/car-sim.html`
  (con la misma config) en una pestaña — escribe datos y atiende comandos por Firebase.

## Resumen de lo que necesito de ti
- El bloque **firebaseConfig** + el **Project ID** (paso 5).
- Hacer tú el **`firebase login`** del paso 6 (requiere tu cuenta Google; yo no puedo).
Con eso, el resto lo dejo andando.
