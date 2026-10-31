// Stubs del SDK de BYD (android.hardware.bydauto.*).
// SOLO para compilar: NO se incluyen en el APK (en el coche los aporta el framework).
// Mañana, con ADB al coche, sustituimos estas firmas por las reales del framework.jar.
plugins {
    id("com.android.library")
}
android {
    namespace = "android.hardware.bydauto"
    compileSdk = 34
    defaultConfig { minSdk = 25 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
