pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}
rootProject.name = "zoom-room"

// Active product: the tablet appliance. The legacy v1 remote-control system
// (legacy/app, legacy/ios, legacy/server) is archived and not part of this build.
include(":room")
