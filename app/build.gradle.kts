plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.qssecurity"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.qssecurity"
        minSdk = 36
        targetSdk = 36
        versionCode = 5
        versionName = "1.4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal-use build: sign the release APK with Android's debug key so the
            // GitHub Actions artifact is immediately installable.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Modern libxposed API. Framework provides this at runtime; never package it in the APK.
    compileOnly("io.github.libxposed:api:102.0.0")
    // Module app <-> LSPosed communication for official RemotePreferences.
    implementation("io.github.libxposed:service:102.0.0")
}
