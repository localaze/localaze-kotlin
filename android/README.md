# localaze-kotlin-android

The Android half of the Kotlin SDK: an `HttpURLConnection` transport and
asset/`filesDir` storage. About 130 lines.

> **Built and run.** It compiles with AGP 8.7.3 on JDK 21, and the Compose demo
> in `../examples/android-app` ran on an API 34 emulator against the stub server:
> it fetched and applied a patch, refused a retyped catalog by signature, and
> sent `If-None-Match` with the previous ETag.

## Building

A standalone Gradle build that pulls in `../localaze-kotlin` as a composite
build and depends on it by coordinates, `dev.sharpy.localaze:localaze`, so
nothing changes when the core is published. It needs JDK 17 or newer and an
Android SDK; the core needs neither.

```
JAVA_HOME=<jdk 17+> ANDROID_HOME=<sdk> ../localaze-kotlin/gradlew -p . assembleRelease
```

## What is deliberately not here

**Any behaviour.** Policy, the signature check, rendering, resolution order and
every failure path live in `../localaze-kotlin`, which is pure Kotlin/JVM and
has no Android dependency. That split is the reason its conformance suite runs
on the JVM in milliseconds rather than on an emulator, and it is why this module
is small enough to read in one sitting.

If a bug turns out to be in policy or the signature check, it is not in this
module — and the test for it belongs next door, where it can run fast.

## `strings.xml`

Not read, not written. `LZ-57` left this open and it is decided: routing
through Android resources would make Android the one platform whose plural
handling and locale resolution are the system's rather than the generated
rules', which is the divergence the conformance suite exists to catch. Resources
also compile into the APK and cannot be replaced over the air, so Android would
lose the feature that justifies the whole design.

**The cost is real:** system-level locale features and accessibility tooling see
no resources for these strings. It is worth raising with the Android team rather
than leaving in a file.

`LZ-18` Android export is unaffected — it serves teams not using the SDK.

## Why `HttpURLConnection` and not OkHttp

OkHttp would be a dependency every consuming app carries and keeps in step. An
app that already has it supplies its own transport in three lines:

```kotlin
class OkHttpTransport(private val client: OkHttpClient) : LocalazeTransport {
    override suspend fun get(url: String, headers: Map<String, String>) = ...
}
```

This is the floor, not the recommendation.

## Usage

```kotlin
val localaze = Localaze.forLocale(
    "az",
    LocalazeConfig(
        apiKey = BuildConfig.LOCALAZE_KEY,
        transport = AndroidTransport(),
        storage = AndroidStorage(context),
    ),
)
```

`AndroidStorage` holds the **application** context, not the one passed in — an
Activity kept here would leak for as long as the SDK lives.
