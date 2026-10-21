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

// The SDK is consumed by coordinates, as an app will consume the published
// artifacts, and these composite builds substitute the local source for them.
// Composite rather than included subprojects, because each SDK build declares
// its own plugin versions and Gradle refuses those inside one build.
includeBuild("../../localaze-kotlin")
includeBuild("../../localaze-kotlin-android")
