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
    namespace = "com.bilal.meetingsremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bilal.meetingsremote"
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
            // Keep the reused AirPlay pairing creds out of the shipped APK (B7):
            // debug builds inherit them from local.properties; release ships
            // empty and pairs at runtime instead.
            buildConfigField("String", "AIRPLAY_PAIRING_ID", "\"\"")
            buildConfigField("String", "AIRPLAY_SEED_HEX", "\"\"")
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

    testOptions {
        // Unit tests exercise SyncEstimator's GCC-PHAT math; android.util.Log
        // calls inside it become no-ops instead of "not mocked" throws.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            // Uncompressed + page-aligned in the APK: required for 16 KB page
            // devices (Android 15+) and lets .so load straight from the APK.
            useLegacyPackaging = false
        }
    }
}

// A reinstall mid-meeting hard-kills the app before it can end the meeting,
// stranding the room's PMI "in progress" on Zoom's side — Start Meeting then
// fails 100/80 until Zoom reaps the zombie (~10 min, plan §17; this is what
// happened on 2026-07-09). Best-effort: ask the app to leave first.
val endMeetingBeforeInstall = tasks.register("endMeetingBeforeInstall") {
    doLast {
        runCatching {
            val adb = localProps.getProperty("sdk.dir")
                ?.let { "$it/platform-tools/adb" } ?: "adb"
            ProcessBuilder(adb, "shell", "am", "broadcast",
                "-n", "com.bilal.meetingsremote/.TestHooksReceiver",
                "-a", "com.bilal.meetingsremote.DEBUG_CMD", "--es", "cmd", "leave")
                .redirectErrorStream(true).start().waitFor()
            Thread.sleep(2000) // let the leave/end reach Zoom before the kill
        }
    }
}
tasks.matching { it.name == "installDebug" }.configureEach {
    dependsOn(endMeetingBeforeInstall)
}

dependencies {
    // AirPlay-2 mirror sender (doubletake's Go core via gomobile). Populate with
    // tools/airplay_sender/build_aar.sh. Provides mobile.Mobile.start / Session.
    implementation(files("libs/airplaysender.aar"))
    // Chrome Custom Tabs for the "Sign in with Zoom" OAuth flow (never a
    // WebView — Google and Zoom both block embedded-webview OAuth). Pure-Java
    // AndroidX lib, no native .so, so it doesn't affect 16 KB alignment.
    implementation("androidx.browser:browser:1.8.0")
    implementation("us.zoom.meetingsdk:zoomsdk:7.0.5")
    // ML lip-sync AV-offset estimator for mic-less cameras (plan
    // 2026-07-10-audio-and-av-sync): SyncNet embeddings via ONNX Runtime,
    // face/mouth localization via MediaPipe BlazeFace.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    // The SDK's pom mixes compose ui 1.9.x with foundation 1.8.x, which
    // crashes its join-flow UI (NoSuchMethodError ToggleableKt.toggleable).
    // Align foundation with the resolved compose-ui version.
    implementation("androidx.compose.foundation:foundation:1.9.4")
    testImplementation("junit:junit:4.13.2")
}
