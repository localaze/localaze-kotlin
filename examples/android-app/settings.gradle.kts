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
    }
}

// The root project is the app itself — build.gradle.kts here applies the
// application plugin — so there is no :app subproject to include.
rootProject.name = "localaze-android-demo"

// The SDK is consumed by path, not from a registry. Publishing to Localaze's
// internal Maven repository is a later ticket — see ../../README.md.
include(":localaze-kotlin")
project(":localaze-kotlin").projectDir = file("../../localaze-kotlin")

include(":localaze-kotlin-android")
project(":localaze-kotlin-android").projectDir = file("../../localaze-kotlin-android")
