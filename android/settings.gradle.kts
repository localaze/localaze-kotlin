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

rootProject.name = "localaze-android"

// The pure-JVM core, as a composite build rather than a subproject. Each keeps
// its own plugin versions, the core never sees AGP, and this module depends on
// it by coordinates — exactly as it will once both are published.
includeBuild("..")
