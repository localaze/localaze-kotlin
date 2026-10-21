# localaze-kotlin

The Localaze runtime for Kotlin and Android. `LZ-57`.

Generated code gives an app typed accessors. This package gives it everything else: fetching a newer catalog, caching it, deciding whether it is safe to apply, and rendering a message from it.

It also ships the generator that writes those accessors from a published release.

> **Core and generator implemented.** 80 tests pass against the shared fixture
> project under `./gradlew test`: 56 for the runtime and 24 for the generator.
> `./verify.sh` runs the runtime suite with `kotlinc` alone.

## Baselines

Chosen rather than confirmed — the app teams delegated the call. Reasoning is in `CLAUDE.md`, and each is a one-line change.

- **The core is a pure Kotlin/JVM module**, not an Android library. The suite then runs in milliseconds on every build instead of on an emulator
- Kotlin 2.1.21, `jvmTarget` 1.8
- **`minSdk` 26**, so `java.time` works with no desugaring
- `compileSdk` / `targetSdk` 36
- **Zero runtime dependencies**, which is why `Json.kt` is written rather than pulled in

The Android adapters live in `../localaze-kotlin-android`: an
`HttpURLConnection` transport and asset/`filesDir` storage, with no contract
logic in them.

## Generating

Build the generator once, then run it from the app's module with an integration key from the project's settings:

```
./gradlew :generator:jar        # generator/build/libs/localaze-generator.jar

java -jar localaze-generator.jar --key $LOCALAZE_KEY --version 1.4.0 \
    --package com.example.app.l10n \
    --out src/main/kotlin/com/example/app/l10n/Localaze.g.kt
```

That writes two things:

| Output | Default | Contents |
|---|---|---|
| `--out` | `Localaze.g.kt` | The `Localaze` class: typed accessors, the signature, plural rules, and a compiled-in copy of every catalog |
| `--assets` | `src/main/assets/localaze/` | One `<language>.json` per language, where `AndroidStorage` reads them |

The jar is self-contained and runs on Java 8 or later. **Pin `--version` in a build**, and commit the generated file: it is deterministic, so regenerating unchanged content produces no diff.

Generation fails, naming every problem at once, when a translation is missing, a catalog is from a different release, or a key cannot become a Kotlin name. `--allow-gaps` turns missing translations into warnings for early development. `--manifest` and `--catalogs` replay saved server responses for a build with no network.

The full specification is `../contract/generator-contract.md`. A Gradle plugin that runs this as a task is a natural next step; it would wrap the same jar.

## Using it

```kotlin
val t = Localaze.forLocale("ru", LocalazeConfig(
    apiKey = "...",
    transport = AndroidTransport(),           // from localaze-kotlin-android
    storage = AndroidStorage(context),
))

t.authSignOut                      // "Выход"
t.authWelcome(name = "Аян")        // "Добро пожаловать, Аян"
t.inboxUnread(count = 11)          // "У вас 11 сообщений"
t.inboxUnread()                    // compile error — a plural needs its count
t.inboxUnread(count = "eleven")    // compile error — count is numeric
```

The last two lines are the point of the whole SDK. A missing or wrongly typed translation argument is caught by the compiler, not by a user reading a broken screen. A `NUMBER` parameter takes any `Number`.

Updating is explicit and never blocks a screen:

```kotlin
t.runtime.update()   // a suspend function: fetches, checks, applies per policy. Never throws.
```

## Configuration

Every option has a default except the key.

```kotlin
LocalazeConfig(
    apiKey = "...",                      // required; carries project and environment
    baseUrl = "https://localize.sharpy.dev",
    policy = LocalazeUpdatePolicy.MINOR_AND_PATCH,
    timeoutMillis = 10_000,
    applyImmediately = false,
    dateFormatter = localazeIsoUtc,
    onDiagnostic = { message, error -> Log.w("Localaze", message, error) },
    transport = null,                    // no network; AndroidTransport supplies it
    storage = null,                      // session only; AndroidStorage persists
)
```

**Pass `onDiagnostic` during integration.** Nothing here throws, which means a wrong API key and a quiet network look identical from the outside.

## Translations do not come from `strings.xml`

`LZ-57` left this open; it is now decided. The SDK neither reads nor emits Android string resources.

Routing Android through resources would make it the one platform whose plural handling and locale resolution are the system's rather than the generated rules' — exactly the divergence the conformance suite exists to catch. It also cannot work with catalogs: resources compile into the APK and cannot be replaced over the air, so Android would lose the feature that justifies the design.

**The cost is real.** System-level locale features and accessibility tooling see no resources for these strings. Raise it if that matters for your app.

This does not remove `LZ-18` Android export, which serves teams not using the SDK and the migration path off Lokalise.

## What it will not do

- **It never throws.** Every failure leaves your app on the text it already had.
- **It never parses MF2**, and ships no CLDR data. Plural rules are generated Kotlin.
- **It never applies a major update**, whatever the policy says.
- **It never applies half a catalog.**

## Reading order

1. `../contract/runtime-contract.md` — the behaviour, specified once for all three platforms
2. `CLAUDE.md` — what is specific to Kotlin
3. `../contract/conformance.json` — every case this package must pass
