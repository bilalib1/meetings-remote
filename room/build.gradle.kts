plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
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
    implementation("us.zoom.meetingsdk:zoomsdk:7.0.5")
    // The SDK's pom mixes compose ui 1.9.x with foundation 1.8.x, which
    // crashes its join-flow UI (NoSuchMethodError ToggleableKt.toggleable).
    // Align foundation with the resolved compose-ui version.
    implementation("androidx.compose.foundation:foundation:1.9.4")
    implementation("com.github.alexeyvasilyev:rtsp-client-android:5.6.4")
    testImplementation("junit:junit:4.13.2")
}
