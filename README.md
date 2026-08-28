# localaze-kotlin

The Localaze runtime for Kotlin and Android. `LZ-57`.

Generated code gives an app typed accessors. This package gives it everything else: fetching a newer catalog, caching it, deciding whether it is safe to apply, and rendering a message from it.

> **Not yet implemented.** The contract and fixtures are in place; the runtime is blocked on the baselines below. See `../README.md` for repository-wide status.

## Blocked on

`build.gradle.kts` is not written until these are confirmed with the Android team:

- Kotlin and AGP versions
- **`minSdk`**, which decides whether desugaring is needed for `java.time` — the ISO-8601 date rendering depends on it
- `compileSdk` and `targetSdk`
- Whether the core is a pure JVM module with a thin Android module on top, or one Android library

They are recorded as `_to be confirmed_` in `CLAUDE.md`.

## Intended use

```kotlin
val localaze = Localaze(locale = "ru", config = LocalazeConfig(apiKey = "..."))

localaze.authSignOut                      // "Выход"
localaze.authWelcome(name = "Аян")        // "Добро пожаловать, Аян"
localaze.inboxUnread(count = 11)          // "У вас 11 сообщений"
localaze.inboxUnread()                    // compile error — a plural needs its count
localaze.inboxUnread(count = "eleven")    // compile error — count is numeric
```

The last two lines are the point of the whole SDK. A missing or wrongly typed translation argument is caught by the compiler, not by a user reading a broken screen.

Updating is explicit and never blocks a screen:

```kotlin
localaze.update()   // fetches, checks, applies per policy. Never throws.
```

## Configuration

Every option has a default except the key.

```kotlin
LocalazeConfig(
    apiKey = "...",                      // required; carries project and environment
    baseUrl = "https://localize.sharpy.dev",
    assetPath = "localaze",              // under assets/
    cacheDirectory = null,               // defaults to filesDir/localaze
    policy = LocalazeUpdatePolicy.MINOR_AND_PATCH,
    timeout = 10.seconds,
    applyImmediately = false,
    onDiagnostic = { message, error -> Log.w("Localaze", message, error) },
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
