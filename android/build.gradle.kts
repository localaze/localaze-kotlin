// The Android half of the Kotlin SDK: the transport and storage the pure-JVM
// core leaves as interfaces. Nothing that decides behaviour lives here.
//
// Building it needs JDK 17 or newer, which AGP requires, and an Android SDK.
// The core does not: it stays a pure JVM build so its suite runs anywhere.

plugins {
    id("com.android.library") version "8.7.3"
    kotlin("android") version "2.1.21"
    `maven-publish`
}

group = "dev.sharpy.localaze"
version = "0.1.0"

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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    // By coordinates. settings.gradle.kts substitutes the local build until
    // the core is published.
    api("dev.sharpy.localaze:localaze:0.1.0")

    // Coroutines is the one dependency, and it is unavoidable: the SDK's
    // interfaces are suspending, which is what an Android app expects. Every
    // Android project already has it.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

publishing {
    publications {
        create<MavenPublication>("android") {
            artifactId = "localaze-android"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Localaze for Android")
                description.set("The Android transport and storage for the Localaze runtime.")
            }
        }
    }
}

// Publishing. The repository comes from the environment, so the same build
// publishes to whichever Maven registry the release runs against and no URL or
// credential is committed. `publishToMavenLocal` needs none of it.
publishing {
    repositories {
        System.getenv("LOCALAZE_MAVEN_URL")?.let { target ->
            maven {
                name = "release"
                url = uri(target)
                credentials {
                    username = System.getenv("LOCALAZE_MAVEN_USER")
                    password = System.getenv("LOCALAZE_MAVEN_PASSWORD")
                }
            }
        }
    }
}
