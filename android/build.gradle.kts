// The Android half of the Kotlin SDK: the transport and storage the pure-JVM
// core leaves as interfaces. Nothing that decides behaviour lives here.
//
// NOT VERIFIED. AGP needs JDK 17 or newer, and the machine this was written on
// had only JDK 8 — the pure-Kotlin core's own Gradle build is verified, this
// one is not. Expect to adjust versions on first run.

plugins {
    id("com.android.library") version "8.7.3"
    kotlin("android") version "2.1.21"
}

android {
    namespace = "dev.sharpy.localaze.android"
    compileSdk = 36

    defaultConfig {
        // 26 for java.time without desugaring, matching the core. See
        // ../localaze-kotlin/CLAUDE.md for why.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    api(project(":localaze-kotlin"))

    // Coroutines is the one dependency, and it is unavoidable: the SDK's
    // interfaces are suspending, which is what an Android app expects. Every
    // Android project already has it.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
