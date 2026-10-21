plugins {
    kotlin("jvm") version "2.1.21"
    `maven-publish`
}

group = "dev.sharpy.localaze"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // No runtime dependencies. Every package here is one a consuming app must
    // also carry and keep in step, which is why the JSON reader in Json.kt is
    // written rather than pulled in — see the note at the top of that file.
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        // 1.8 is the widest target an Android consumer can use without
        // trouble, and nothing here needs more.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
}

tasks.test {
    useJUnitPlatform()

    // The fixture project is shared with the Dart and Swift packages. Copying
    // it in would mean a Kotlin-only version that drifts within a release.
    systemProperty("localaze.fixtures", "${projectDir}/../contract/fixtures")
    systemProperty("localaze.sources", "${projectDir}/src/main/kotlin")

    testLogging {
        events("passed", "failed")
    }
}

publishing {
    publications {
        create<MavenPublication>("runtime") {
            artifactId = "localaze"
            from(components["java"])
            pom {
                name.set("Localaze runtime")
                description.set("Fetches, caches and applies Localaze catalogs and renders messages from them. Never throws.")
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
