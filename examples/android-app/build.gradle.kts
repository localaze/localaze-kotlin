// NOT VERIFIED. AGP needs JDK 17 or newer, and this was written on a machine
// with only JDK 8. `../../localaze-kotlin` builds and tests under real Gradle;
// this app does not, and `./verify-generated.sh` covers the one part of it that
// can be checked without an Android toolchain.
//
// Expect to adjust the AGP, Compose and lifecycle versions on the first run.

plugins {
    id("com.android.application") version "8.7.3"
    kotlin("android") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
}

android {
    namespace = "dev.sharpy.localaze.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.sharpy.localaze.demo"
        // 26 for java.time without desugaring, matching the SDK.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // An emulator reaches the host at 10.0.2.2, not localhost. A device on
        // the same network wants the machine's address — change it here.
        buildConfigField("String", "LOCALAZE_BASE_URL", "\"http://10.0.2.2:8787\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["main"].assets.srcDir("src/main/assets")

    // The demo talks to a stub server over plain HTTP on a developer machine.
    // Never do this in a shipping app.
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(project(":localaze-kotlin-android"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
