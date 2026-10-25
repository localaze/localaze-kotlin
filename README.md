# Localaze for Kotlin and Android

Typed translations for Android and JVM apps, generated from a [Localaze](https://localize.sharpy.dev) release and updated over the air.

[![CI](https://github.com/localaze/localaze-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/localaze/localaze-kotlin/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/dev.sharpy.localaze/localaze?label=maven%20central)](https://central.sonatype.com/namespace/dev.sharpy.localaze)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Kotlin 2.1+](https://img.shields.io/badge/kotlin-2.1%2B-7F52FF)
![Android API 26+](https://img.shields.io/badge/android-API%2026%2B-3DDC84)

| Artifact | What it is |
|---|---|
| `dev.sharpy.localaze:localaze` | The runtime. Pure Kotlin/JVM, no dependencies |
| `dev.sharpy.localaze:localaze-android` | The Android transport and storage, over `HttpURLConnection`, assets and `filesDir`. Depends on the runtime |
| `dev.sharpy.localaze:localaze-generator` | A self-contained jar that writes your app's typed accessors from a release. Never linked into the app |

## Why

**A plural cannot be called without its count.** Every message becomes a typed accessor, so the compiler checks each call site:

<!-- snippet: src/test/kotlin/dev/sharpy/localaze/golden/ReadmeSnippetsTest.kt#use -->
```kotlin
val lines = listOf(
    t.authSignOut,                          // "Выход"
    t.authWelcome(name = "Аян"),            // "Добро пожаловать, Аян"
    t.inboxUnread(count = 1),               // "У вас 1 сообщение"
    t.inboxUnread(count = 11),              // "У вас 11 сообщений"
    t.billingDue(`when` = Instant.parse("2026-03-01T00:00:00Z")),
)
// Neither of these compiles:
// t.inboxUnread()                  a plural needs its count
// t.inboxUnread(count = "eleven")  the count is a number
```

A `NUMBER` parameter takes any `Number`.

**A missing translation fails the build, not the screen.** Generation refuses a release in which any language lacks a message, and names every gap at once.

**Wording fixes reach users without an app release.** The app ships with every catalog compiled in, and fetches newer wording when you ask it to. A new catalog is applied only if every accessor the app was built with still fits it.

## Install

From Maven Central, which every Android project already reads:

<!-- uncompiled -->
```kotlin
dependencies {
    implementation("dev.sharpy.localaze:localaze-android:0.2.0")
}
```

A JVM app without Android depends on `dev.sharpy.localaze:localaze` and supplies its own transport.

## Generate

Run the generator from your build, with an integration key from the project's settings in Localaze. In the app module's `build.gradle.kts`:

<!-- uncompiled -->
```kotlin
val localazeGenerator by configurations.creating

dependencies {
    localazeGenerator("dev.sharpy.localaze:localaze-generator:0.2.0")
}

tasks.register<JavaExec>("generateLocalaze") {
    classpath = localazeGenerator
    mainClass.set("dev.sharpy.localaze.generator.MainKt")
    args(
        "--key", providers.environmentVariable("LOCALAZE_KEY").get(),
        "--version", "1.4.0",
        "--package", "com.example.app.l10n",
        "--out", "src/main/kotlin/com/example/app/l10n/Localaze.g.kt",
        "--assets", "src/main/assets/localaze",
    )
}
```

then `./gradlew generateLocalaze`. Keep the key in an environment variable or your CI's secrets, never in the repository. The task writes:

| Output | Default | Check it in? |
|---|---|---|
| `--out` | `Localaze.g.kt` | **Yes.** Typed accessors, plural rules and a compiled-in copy of every catalog. Deterministic, so regenerating unchanged content gives no diff |
| `--assets` | `src/main/assets/localaze/` | **Yes.** One `<language>.json` per language, where `AndroidStorage` reads them |

**Pin `--version`.** Without it the generator takes the environment's live release. Regenerate when keys change; wording changes need no regeneration.

Generation exits non-zero, listing every problem, when a translation is missing, a catalog is from a different release, or a key cannot become a Kotlin name. `--allow-gaps` turns missing translations into warnings for early development. `--manifest manifest.json --catalogs catalogs/` generates offline from saved responses. The jar runs on Java 8 or later.

## Use

<!-- snippet: examples/android-app/src/main/kotlin/dev/sharpy/localaze/demo/ReadmeSnippets.kt#setup -->
```kotlin
val t = Localaze.forLocale(
    "ru",
    LocalazeConfig(
        apiKey = key, // from BuildConfig or your CI's secrets, never the source
        transport = AndroidTransport(),
        storage = AndroidStorage(context), // assets/localaze/, and a cache in filesDir
        onDiagnostic = { message, error -> Log.w("Localaze", "$message ${error ?: ""}") },
    ),
)
```

With Compose, hold the translations in a `ViewModel`, so a locale switch or a newly applied catalog recomposes:

<!-- snippet: examples/android-app/src/main/kotlin/dev/sharpy/localaze/demo/ReadmeSnippets.kt#compose -->
```kotlin
class TranslationsViewModel(app: Application) : AndroidViewModel(app) {
    private var current by mutableStateOf(make("az"))
    private var revision by mutableIntStateOf(0)

    /** Reading it subscribes a composable to locale switches and applied catalogs. */
    val t: Localaze get() = current.also { revision }

    fun setLocale(locale: String) {          // survives configuration change, no restart
        current = make(locale)
        viewModelScope.launch { current.runtime.load(); revision++ }
    }

    fun update() = viewModelScope.launch {
        if (current.runtime.update().isApplied) revision++
    }

    private fun make(locale: String) = Localaze.forLocale(
        locale,
        LocalazeConfig(apiKey = "…", transport = AndroidTransport(), storage = AndroidStorage(getApplication())),
    )
}

@Composable
fun InboxTitle(model: TranslationsViewModel) {
    LaunchedEffect(Unit) { model.update() }
    Text(model.t.inboxUnread(count = 3))
}
```

Translations do not go through `strings.xml`. Android resources compile into the APK and cannot be replaced over the air, and their plural handling is the system's rather than the generated rules', which would make Android the one platform that renders differently. The cost is that system locale features and accessibility tooling see no resources for these strings.

## Updates over the air

Fetching is explicit, so it never blocks a screen. Call it on launch, on resume, or from WorkManager:

<!-- snippet: examples/android-app/src/main/kotlin/dev/sharpy/localaze/demo/ReadmeSnippets.kt#update -->
```kotlin
t.runtime.load()                     // the cached catalog, if any
val status = t.runtime.update()      // suspends, never throws
Log.i("Localaze", if (status.isApplied) "now on ${t.runtime.version}" else "still on ${t.runtime.version}")
```

- **Offline**, the app renders the newest catalog it has: a cached update, else the bundled asset, else the compiled-in copy. A first launch with no network still shows every message.
- **The update policy** decides which releases are taken: `NONE`, `PATCH_ONLY`, `MINOR_AND_PATCH` (the default) or a range. A **major** release is never applied, whatever the policy, because its messages no longer fit the accessors the app was built with.
- **The signature check** applies a catalog only if every key the app knows is present with the same parameter names and types. An incompatible catalog is refused whole.
- **Wording changes need no regeneration.** Adding, removing or retyping a key does, and that is a new version of the app.

## Errors

Nothing in these artifacts throws. A network failure, a malformed payload, an unreadable cache or a refused update each leaves the app on the text it already had. A translation outage is a stale screen, not a crash.

The cost is that a wrong API key looks exactly like a quiet network. **Pass `onDiagnostic` while integrating**: it is called with a message naming what failed and why, for every failure that was swallowed.

```text
W/Localaze: /api/v1/read/version returned 401
W/Localaze: The published version could not be read
```

## Configuration

Every option has a default except the key:

<!-- snippet: examples/android-app/src/main/kotlin/dev/sharpy/localaze/demo/ReadmeSnippets.kt#config -->
```kotlin
return LocalazeConfig(
    apiKey = key,                    // carries the project and the environment
    baseUrl = "https://localize.sharpy.dev",
    policy = LocalazeUpdatePolicy.MINOR_AND_PATCH,
    timeoutMillis = 10_000,
    applyImmediately = false,
    dateFormatter = localazeIsoUtc,
    onDiagnostic = null,
    transport = null,                // no network: AndroidTransport()
    storage = null,                  // updates last for the session: AndroidStorage(context)
)
```

| Option | Default | What it does |
|---|---|---|
| `apiKey` | required | An integration key. It carries the project and the environment, so a staging build cannot read production |
| `baseUrl` | `https://localize.sharpy.dev` | Your Localaze server, if you run your own |
| `policy` | `MINOR_AND_PATCH` | Which releases an update may apply |
| `timeoutMillis` | 10 000 | Per request |
| `applyImmediately` | `false` | Apply a fetched catalog at once rather than on `applyPending()` or the next launch |
| `dateFormatter` | ISO-8601 UTC | How `DATETIME` parameters render |
| `onDiagnostic` | none | Called for every swallowed failure |
| `transport` | none | How to fetch. `AndroidTransport()`, or your own `LocalazeTransport` over OkHttp or Ktor |
| `storage` | none | Where to read bundled catalogs and cache updates. `AndroidStorage(context)`; without it, updates last for the session |

`AndroidStorage` keeps the application context, never the one passed in, so handing it an `Activity` does not leak it.

## Compatibility

| | |
|---|---|
| Kotlin | 2.1 or later; the runtime targets JVM 1.8 |
| Android | API 26 (Android 8.0) or later; compiled against API 36 |
| Localaze server | Any that serves `/api/v1/read/manifest` and `format=resolved` catalogs, which every release of Localaze does |

**One version across Dart, Swift and Kotlin.** The three SDKs implement one [contract](https://github.com/localaze/localaze-contract) and are released together, so `0.2.0` behaves the same on every platform. Versions are semantic; a change to what the generator writes counts as API. See [`RELEASING.md`](https://github.com/localaze/localaze-contract/blob/main/RELEASING.md).

## Links

- **Other platforms:** [Dart and Flutter](https://github.com/localaze/localaze-dart) · [Swift](https://github.com/localaze/localaze-swift)
- **The contract** all three implement: [localaze-contract](https://github.com/localaze/localaze-contract)
- **Changelogs:** [runtime and generator](CHANGELOG.md) · [Android](android/CHANGELOG.md)
- **A working app:** [`examples/android-app`](examples/android-app/), against the [stub read API](https://github.com/localaze/localaze-contract/tree/main/stub-server)
- **Problems:** [open an issue](https://github.com/localaze/localaze-kotlin/issues). Security issues: see [`SECURITY.md`](SECURITY.md)
- **Contributing:** [`CONTRIBUTING.md`](CONTRIBUTING.md)

MIT licensed. See [`LICENSE`](LICENSE).
