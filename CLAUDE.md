# localaze-kotlin

The Kotlin/Android runtime package. `LZ-57`.

Read `../CLAUDE.md` for the rules that govern every SDK package, and `../contract/runtime-contract.md` for the behaviour this one implements. This file records only what is specific to Kotlin.

## Baselines

**Chosen, not confirmed.** The app teams were asked and delegated the decision, so these are recorded with their reasoning to make an override cheap.

| | | Why |
|---|---|---|
| Core module | **Pure Kotlin/JVM** | See below — this is the load-bearing one |
| Kotlin | 2.1.21 | Verified compiling on 2.4.10 |
| `jvmTarget` | 1.8 | The widest target an Android consumer takes without trouble, and nothing here needs more |
| `minSdk` | **26** | `java.time` natively, so no desugaring for the ISO-8601 rendering in contract §3. Android 8.0 is nine years old in 2026 |
| `compileSdk` / `targetSdk` | 36 | |
| AGP | Not needed by the core | Only the Android artifact needs it |
| Runtime dependencies | **None** | |
| Distribution | Local path dependency. The internal Maven repository is a later ticket | |

### Why the core is pure JVM

The `LZ-54` guarantee is only worth something if it is tested, and the conformance suite is 50 cases that must run on **every build**. On the JVM they take milliseconds. As an Android library they would need an emulator or Robolectric, and a suite that slow is a suite people start skipping.

Keeping `Context` out of the core is what makes this possible, which is why `Transport` and `Storage` are interfaces rather than a convenience over `Context`.

The Android adapters — assets from a `Context`, `filesDir` caching, an OkHttp transport — are a second module, about fifty lines, added when the Android team confirms AGP. **None of the contract logic lives there.**

`verify.sh` compiles and runs the suite with `kotlinc` alone, no Gradle and no Android SDK. That it works at all is the property being protected.

### On the JSON reader

Kotlin has no stdlib JSON parser, and the two obvious answers were both worse than writing one:

- `org.json` is on Android but not the JVM, which would force the core to be an Android library and undo everything above.
- `kotlinx-serialization` is a dependency plus a compiler plugin, and a dependency here is one every consuming app carries.

`Json.kt` is about a hundred lines for a small fixed format, and the catalog tests cover it. It is not a general-purpose parser and must not grow into one.

## Layout

```
localaze-kotlin/
├── CLAUDE.md
├── README.md
├── build.gradle.kts               blocked on Baselines
├── src/
│   ├── main/kotlin/dev/sharpy/localaze/
│   │   ├── Localaze.kt                  the public entry point
│   │   ├── LocalazeConfig.kt
│   │   ├── LocalazeCatalog.kt           parsing, part walking
│   │   ├── Renderer.kt                  variant selection and substitution
│   │   ├── Signature.kt                 names and types, compatibility check
│   │   ├── UpdatePolicy.kt              policy, version comparison
│   │   ├── CatalogStore.kt              applied, cached, bundled, compiled-in
│   │   ├── Updater.kt                   currentVersion, fetchIfAccepted, ETag
│   │   ├── Diagnostics.kt
│   │   ├── Transport.kt                 the interface
│   │   ├── Storage.kt                   the interface
│   │   └── platform/                    default implementations, the only place OkHttp and Context appear
│   └── test/kotlin/dev/sharpy/localaze/
│       ├── conformance/                 one file per group in ../contract/conformance.json
│       └── fakes/                       FakeTransport, FakeStorage
```

Package root `dev.sharpy.localaze` matches the backend, which is deliberate — one name for one platform across the whole repository.

**Worth deciding early:** whether the core is a pure JVM module with a thin Android module on top, or one Android library. Pure JVM makes the conformance tests run on the JVM in milliseconds rather than in an instrumented test on a device or emulator, which matters when the suite runs on every build. It costs a second module. Record the choice here once made.

## Transport and storage

`Transport` and `Storage` are interfaces. **Core code never touches OkHttp, `Context`, or the filesystem directly.**

`platform/` holds the defaults and is the only package permitted to. Keeping `Context` out of the core is also what makes the pure-JVM option above possible.

## Defaults

| Option | Kotlin default |
|---|---|
| `assetPath` | `localaze/` under `assets/` |
| `cacheDirectory` | `context.filesDir/localaze` |

Android can find a writable directory, so persistence is on by default, as on iOS and unlike Dart. A default that differs per platform is fine; behaviour given the same configuration is not.

## No `strings.xml`

`LZ-57` leaves this open and it is now decided: **the SDK does not use or emit Android string resources.** Translations come from the SDK.

The reasoning is the `LZ-54` guarantee. Routing Android through resources makes it the one platform whose plural handling and locale resolution are the system's rather than the generated rules', which is exactly the divergence the conformance suite exists to catch. It also cannot work with catalogs: resources are compiled into the APK and cannot be replaced over the air, so Android would lose the feature that justifies the whole design.

The cost is real and should be stated to the Android team rather than glossed: system-level locale features and accessibility tooling see no resources for these strings.

This does not remove `LZ-18` Android export. That serves teams not using the SDK and the migration path off Lokalise, and it is a separate output.

## Views and Compose

`LZ-57` requires both, plus a Compose-friendly accessor, and locale switching that survives configuration change.

Keep the Compose surface in its own file and out of the core, so a Views-only app carries no Compose dependency.

## Never do

Everything in `../CLAUDE.md`, plus:

- Never touch OkHttp, `Context`, or the filesystem outside `platform/`
- Never read from or generate `strings.xml`
- Never make the core depend on Compose
- Never require an instrumented test for something a JVM test can cover
- Never write `build.gradle.kts` before `minSdk` is confirmed
