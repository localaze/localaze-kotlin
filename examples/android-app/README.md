# android-app

A Compose demo over `localaze-kotlin` and `localaze-kotlin-android`.

> **Not built or run.** AGP needs JDK 17 or newer, and this was written on a
> machine with only JDK 8. Of the three example apps this is the one to treat
> with most suspicion: expect to adjust the AGP, Compose and lifecycle versions
> on the first run.
>
> What **is** verified is the part that matters most — see below.

## What is verified

```
./verify-generated.sh
```

Compiles `Localaze.g.kt` against the SDK core with `kotlinc` alone — no Gradle,
no Android SDK — and checks what it renders against the same strings the Dart
and Swift demos assert. It passes.

That covers the generated accessors, the compiled Russian plural rule including
the 11 and 111 exceptions, and the ISO-8601 date rendering. It does not cover
the Compose UI or the Android transport and storage, which need a real Android
build.

The SDK underneath is verified independently: `../../localaze-kotlin` builds and
runs its 52 conformance tests under real Gradle.

## Run it

Start the stub server first:

```
cd ../stub-server && dart run serve.dart
```

Then:

```
./gradlew installDebug
```

**An emulator reaches the host at `10.0.2.2`, not `localhost`.** That one
catches everybody once, so it is the default. A device on the same network wants
the machine's address — change `LOCALAZE_BASE_URL` in `build.gradle.kts`.

## What to try

Set the Server card to **`retyped`** and press Check for update.

It offers `1.0.2`, a patch. The policy accepts it. Every key is present.
`inbox.unread` still declares a parameter called `count`. Only the *type*
changed, `NUMBER` to `STRING` — and `Localaze.g.kt` has already promised every
call site `inboxUnread(count: Int)`.

The SDK refuses the whole catalog and the Diagnostics card says why.

## No `strings.xml`, and no `R.string`

Search this app for either and you will find nothing. `LZ-57` left the question
open and it is decided: the SDK bypasses Android string resources, because
routing through them would make Android the one platform whose plural handling
is the system's rather than the generated rules', and resources compile into the
APK so they cannot be replaced over the air.

**The cost is real:** system-level locale features and accessibility tooling see
no resources for these strings. Worth raising with the Android team.

## Structure

| | |
|---|---|
| `Localaze.g.kt` | Written by hand. The generator does not exist yet; this is the reference for what Phase 4 must emit |
| `DemoViewModel.kt` | All the behaviour, in plain Kotlin with a `StateFlow`. No Compose |
| `MainActivity.kt` | Compose, presentational only |

The view model holds no Compose types on purpose, which is the same split the
SDK itself makes and for the same reason: the part worth testing should not need
a UI toolkit to run.
