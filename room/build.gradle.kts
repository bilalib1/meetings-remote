plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun airplayProp(key: String, default: String) = localProps.getProperty(key, default)

android {
    namespace = "com.bilal.zoomroom"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bilal.zoomroom"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk {
            // Dev tablet (SM-P620) is arm64; single ABI keeps the APK ~half the size.
            abiFilters += "arm64-v8a"
        }
        // AirPlay TV target + reused pairing creds, sourced from local.properties
        // (gitignored) so no secrets land in git.
        buildConfigField("String", "AIRPLAY_HOST", "\"${airplayProp("airplay.host", "")}\"")
        buildConfigField("int", "AIRPLAY_PORT", airplayProp("airplay.port", "7000"))
        buildConfigField("String", "AIRPLAY_PAIRING_ID", "\"${airplayProp("airplay.pairingId", "")}\"")
        buildConfigField("String", "AIRPLAY_SEED_HEX", "\"${airplayProp("airplay.seedHex", "")}\"")
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=none" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // Dev release signing with the standard debug keystore, so we can ship
        // a non-debuggable APK (which stops Android's 16 KB "app compatibility"
        // warning — that only nags on debuggable test builds). Replace with a
        // real keystore for production.
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Uncompressed + page-aligned in the APK: required for 16 KB page
            // devices (Android 15+) and lets .so load straight from the APK.
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // AirPlay-2 mirror sender (doubletake's Go core via gomobile). Populate with
    // tools/airplay_sender/build_aar.sh. Provides mobile.Mobile.start / Session.
    implementation(files("libs/airplaysender.aar"))
    implementation("us.zoom.meetingsdk:zoomsdk:7.0.5")
    // The SDK's pom mixes compose ui 1.9.x with foundation 1.8.x, which
    // crashes its join-flow UI (NoSuchMethodError ToggleableKt.toggleable).
    // Align foundation with the resolved compose-ui version.
    implementation("androidx.compose.foundation:foundation:1.9.4")
    testImplementation("junit:junit:4.13.2")
}
