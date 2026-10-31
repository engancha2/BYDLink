// Copia este archivo a   phone/firebase-config.js   y pega los datos de TU proyecto.
// Los sacas en: consola Firebase -> Configuración del proyecto -> Tus apps -> SDK -> Config.
// Mientras este archivo no exista con datos reales, la app del teléfono funciona en MODO DEMO.
//
// NOTA: estas claves "apiKey" de Firebase web NO son secretas (van en el navegador).
// La seguridad real la dan las reglas de database.rules.json + Firebase Auth.

window.FIREBASE_CONFIG = {
  apiKey: "PON_AQUI_TU_API_KEY",
  authDomain: "TU-PROYECTO.firebaseapp.com",
  databaseURL: "https://TU-PROYECTO-default-rtdb.europe-west1.firebasedatabase.app",
  projectId: "TU-PROYECTO",
  storageBucket: "TU-PROYECTO.appspot.com",
  messagingSenderId: "0000000000",
  appId: "1:0000000000:web:xxxxxxxxxxxx"
};

// Identificador de TU coche. El mismo valor debe ir en la app del coche.
window.CAR_ID = "mi-byd";
