plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.andwebcam"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.andwebcam"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        // Debug builds are what we sideload during development.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // No AndroidX / no extra dependencies: keeps the download small on a slow link.
    dependenciesInfo {
        includeInApk = false
    }
}
