plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Descomentar cuando añadas google-services.json (ver DEPLOY.md):
    // id("com.google.gms.google-services")
}

android {
    namespace = "com.bydlink.carapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bydlink.carapp"
        // La pantalla BYD de este APK es Android 7 (API 25). Mantener minSdk bajo.
        minSdk = 25
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    // SDK de BYD: sólo en compilación. En el coche lo aporta el framework del sistema.
    compileOnly(project(":byd-stubs"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Firebase (realtime db + auth + mensajería). Requiere google-services.json.
    implementation(platform("com.google.firebase:firebase-bom:33.3.0"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")

    // WebRTC para la cámara en vivo (build mantenido por la comunidad de stream.io).
    implementation("io.getstream:stream-webrtc-android:1.1.3")

    // Cliente ADB en Kotlin: auto-permisos vía localhost:5555 (como Vektra/SentryEV/SealNav).
    // TODO: confirmar la última versión disponible al compilar.
    implementation("dev.mobile:dadb:1.2.10")
}
