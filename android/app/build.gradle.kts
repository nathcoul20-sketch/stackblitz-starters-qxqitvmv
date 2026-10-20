plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.scorecast.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scorecast.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Clé fixe commitée dans le dépôt : chaque APK construit par la CI a la même
        // signature, ce qui permet d'installer une nouvelle version par-dessus l'ancienne.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    // Capture caméra + encodage + envoi RTMP/RTMPS, avec filtres OpenGL pour l'overlay
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")

    // Mini serveur web embarqué pour la télécommande (2e téléphone)
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Appels à l'API Twitch (titre du live)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Génération du QR code pour connecter la télécommande
    implementation("com.google.zxing:core:3.5.3")

    // Lecture de QR code (copie d'un club d'un téléphone à l'autre), sans permission caméra
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
