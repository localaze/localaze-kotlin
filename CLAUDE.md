# localaze-kotlin

The Kotlin/Android runtime package, its generator, and the Android adapters.

Read `contract/CLAUDE.md` for the rules that govern every SDK package, and `contract/runtime-contract.md` for the behaviour this one implements. This file records only what is specific to Kotlin.

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
| Distribution | Maven Central, group `dev.sharpy.localaze` | |

### Why the core is pure JVM

The cross-platform guarantee is only worth something if it is tested, and the conformance suite is 52 cases that must run on **every build**. On the JVM they take milliseconds. As an Android library they would need an emulator or Robolectric, and a suite that slow is a suite people start skipping.

Keeping `Context` out of the core is what makes this possible, which is why `Transport` and `Storage` are interfaces rather than a convenience over `Context`.

The Android adapters live in `android/`, a separate Gradle build: an `HttpURLConnection` transport and asset/`filesDir` storage. **None of the contract logic is there** — if a bug turns out to be in policy or the signature check, it is in this module, where the test for it can run fast.

`./gradlew test` runs the suite under real Gradle and JUnit 5. `verify.sh` runs
the same suite with `kotlinc` alone — no Gradle, no Android SDK. Both are
verified; that the second works at all is the property being protected.

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
├── build.gradle.kts               verified: ./gradlew test passes
├── settings.gradle.kts            the runtime, plus the generator module
├── verify.sh                      the runtime suite, kotlinc only
├── generator/                     the generator: a self-contained jar, never linked into an app
│   ├── build.gradle.kts           compiles ../src/main/.../Json.kt in, depends on nothing
│   └── src/
│       ├── main/kotlin/dev/sharpy/localaze/generator/
│       │   ├── Plan.kt                  decides and checks
│       │   ├── KotlinEmitter.kt         spells
│       │   └── Main.kt                  the command: network and files, nothing else
│       └── test/kotlin/…                the generator group, and the jar as a process
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
│   │   ├── Json.kt                      a minimal reader; see On the JSON reader
│   │   └── Seams.kt                     Transport, Storage, config, diagnostics
│   └── test/kotlin/dev/sharpy/localaze/
│       ├── conformance/                 one file per group in contract/conformance.json
│       ├── golden/                      Localaze.g.kt, the generator's golden, compiled and rendered
│       └── fakes/                       FakeTransport, FakeStorage
```

Package root `dev.sharpy.localaze` matches the backend, which is deliberate — one name for one platform across the whole repository.

## Transport and storage

`Transport` and `Storage` are interfaces, and **this module implements neither.** There is no `platform/` package here: the Android implementations live in `android/`, because the moment one of them touches `Context` this module stops being pure JVM and its suite stops being fast.

Defaults are that module's business — assets under `localaze/`, cache in `context.filesDir/localaze`. Persistence is on there by default, as on iOS and unlike Dart, because Android can find a writable directory without an extra dependency. A default that differs per platform is fine; behaviour given the same configuration is not.

## No `strings.xml`

It is decided: **the SDK does not use or emit Android string resources.** Translations come from the SDK.

The reasoning is the cross-platform guarantee. Routing Android through resources makes it the one platform whose plural handling and locale resolution are the system's rather than the generated rules', which is exactly the divergence the conformance suite exists to catch. It also cannot work with catalogs: resources are compiled into the APK and cannot be replaced over the air, so Android would lose the feature that justifies the whole design.

The cost is real and should be stated to the Android team rather than glossed: system-level locale features and accessibility tooling see no resources for these strings.

This does not remove the platform's Android `strings.xml` export. That serves teams not using the SDK, and it is a separate output.

## The generator

`java -jar localaze-generator.jar`, specified in `contract/generator-contract.md`.

- **It is its own Gradle module and its own jar.** Nothing an app links contains generator code, and the runtime jar gains nothing from it.
- **It shares `Json.kt` by compiling the file in**, not by depending on the runtime. `Json` stays internal, so the runtime's public API does not widen to serve a build tool.
- **It targets Java 8** and uses `HttpURLConnection`, so it runs wherever the runtime builds.
- **The JVM's limits shape the output.** A string constant holds at most 65,535 bytes, so a large compiled-in catalog is split into chunks. A method holds at most 64 KB of bytecode, so the signature map is built in functions of 500 entries.
- **Plural conditions are rewritten to `Double` arithmetic.** Kotlin will not compare a `Double` with an `Int` literal, so every operand is a `Double` and every literal gains `.0`.
- **File-private helpers start with an underscore.** No accessor can, so a key named `plural` cannot shadow them.
- The golden file is `src/test/kotlin/dev/sharpy/localaze/golden/Localaze.g.kt`, compiled with the runtime's tests. When output changes on purpose, run `UPDATE_GOLDEN=1 ./gradlew :generator:test --tests '*GeneratorTest.golden'` and review the diff.

## Views and Compose

The output serves both, plus a Compose-friendly accessor, and locale switching that survives configuration change.

Keep the Compose surface in its own file and out of the core, so a Views-only app carries no Compose dependency.

## Never do

Everything in `contract/CLAUDE.md`, plus:

- Never touch `Context`, the network, or the filesystem in this module at all — that is what `localaze-kotlin-android` is for
- Never read from or generate `strings.xml`
- Never make the core depend on Compose
- Never require an instrumented test for something a JVM test can cover
- Never add a dependency. `Json.kt` exists precisely so this line can stay true
