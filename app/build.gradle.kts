import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "pt.diogo.glowbar"
    compileSdk = 35

    defaultConfig {
        applicationId = "pt.diogo.glowbar"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.1"
    }

    signingConfigs {
        create("glowbarRelease") {
            val keystoreFile = file("glowbar-release.jks")

            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("GLOWBAR_KEYSTORE_PASSWORD")
                keyAlias = "glowbar"
                keyPassword = System.getenv("GLOWBAR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("glowbarRelease")
        }

        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("glowbarRelease")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
}