// The generator: a build-time command, never linked into an app.
//
// It shares one source file with the runtime, the JSON reader, compiled in
// rather than depended on: the runtime keeps Json internal, and the generator
// emits text, so it needs nothing else from the runtime at all.

plugins {
    kotlin("jvm")
}

group = "dev.sharpy.localaze"
version = rootProject.version

repositories {
    mavenCentral()
}

// The runtime's source directory is added, then narrowed to Json.kt. The
// include patterns apply across both directories, so the generator's own
// package is listed too.
sourceSets["main"].kotlin {
    srcDir("${rootDir}/src/main/kotlin")
    include("dev/sharpy/localaze/Json.kt", "dev/sharpy/localaze/generator/**")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// One self-contained jar, so a build machine runs `java -jar` with nothing
// else installed: the Kotlin standard library goes inside.
tasks.jar {
    archiveBaseName.set("localaze-generator")
    archiveVersion.set("")
    manifest { attributes("Main-Class" to "dev.sharpy.localaze.generator.MainKt") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.map { classpath -> classpath.map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.jar)
    systemProperty("localaze.fixtures", "${rootDir}/../contract/fixtures")
    systemProperty("localaze.golden", "${rootDir}/src/test/kotlin/dev/sharpy/localaze/golden/Localaze.g.kt")
    systemProperty("localaze.generatorJar", tasks.jar.get().archiveFile.get().asFile.path)
    testLogging { events("passed", "failed") }
}
