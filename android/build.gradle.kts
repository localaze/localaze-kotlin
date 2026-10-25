// The Android half of the Kotlin SDK: the transport and storage the pure-JVM
// core leaves as interfaces. Nothing that decides behaviour lives here.
//
// Building it needs JDK 17 or newer, which AGP requires, and an Android SDK.
// The core does not: it stays a pure JVM build so its suite runs anywhere.

plugins {
    id("com.android.library") version "8.7.3"
    kotlin("android") version "2.1.21"
    `maven-publish`
    signing
}

group = "dev.sharpy.localaze"
version = "0.1.0"

android {
    namespace = "dev.sharpy.localaze.android"
    compileSdk = 36

    defaultConfig {
        // 26 for java.time without desugaring, matching the core. See
        // ../CLAUDE.md for why.
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
            withJavadocJar()
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
            // The POM Maven Central requires. The same as the root build's
            // buildSrc/src/main/kotlin/LocalazePublishing.kt, which a separate
            // build cannot see; keep the two in step.
            pom {
                name.set("Localaze for Android")
                description.set("The Android transport and storage for the Localaze runtime.")
                url.set("https://github.com/localaze/localaze-kotlin")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("sharabiddin")
                        name.set("Sharabiddin Ahmayev")
                        url.set("https://github.com/sharabiddin")
                    }
                }
                scm {
                    url.set("https://github.com/localaze/localaze-kotlin")
                    connection.set("scm:git:https://github.com/localaze/localaze-kotlin.git")
                    developerConnection.set("scm:git:https://github.com/localaze/localaze-kotlin.git")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/localaze/localaze-kotlin/issues")
                }
            }
        }
    }
    repositories {
        // The repository root's staging folder, beside the runtime and the
        // generator, so the three are uploaded as one deployment.
        maven {
            name = "staging"
            url = uri(System.getenv("LOCALAZE_STAGING_DIR") ?: "${rootDir}/../build/staging-deploy")
        }
    }
}

// Signed in the release job, where the key is in the environment; unsigned
// otherwise, so a local build needs no key.
System.getenv("LOCALAZE_SIGNING_KEY")?.let { key ->
    signing {
        useInMemoryPgpKeys(key, System.getenv("LOCALAZE_SIGNING_PASSWORD") ?: "")
        sign(publishing.publications)
    }
}
