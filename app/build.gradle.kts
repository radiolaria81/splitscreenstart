import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.radiolaria.splitscreenstart"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.radiolaria.splitscreenstart"
        // Android 7.0: ab hier gibt es Splitscreen, FLAG_ACTIVITY_LAUNCH_ADJACENT und GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN.
        minSdk = 24
        targetSdk = 35
        // versionCode: fortlaufende Build-Nummer aus GitHub Actions (muss steigen), lokal 1
        // versionName: aus der Datei VERSION im Repo
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = rootProject.file("VERSION").readText().trim()
    }

    // Fester Schlüssel im Repo, damit sich Updates über die alte Version installieren lassen.
    signingConfigs {
        create("fixed") {
            storeFile = file("signing.jks")
            storePassword = "splitstart"
            keyAlias = "split"
            keyPassword = "splitstart"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Keine Laufzeit-Abhängigkeiten außer der Kotlin-Standardbibliothek.
}
