plugins { id("com.android.application") }

android {
    namespace = "com.bydlink.movil"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.bydlink.movil"
        minSdk = 23
        targetSdk = 34
        // Subir los dos en cada versión nueva.
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    applicationVariants.all {
        val v = versionName
        outputs.all { (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "BYDLink-movil-$v.apk" }
    }
}

dependencies {
    implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.5.0")
}
