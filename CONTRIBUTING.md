# Contributing

Thanks for helping with Localaze for Kotlin and Android. Bug reports, fixes and documentation are all welcome.

## Before you start

- **A bug:** open an issue with what you expected and what happened, the SDK version, and any `onDiagnostic` output. That output is usually the fastest route to the cause.
- **A change in behaviour, or a new option:** open an issue first, so we can agree on it before you write it.
- **A security problem:** do not open an issue. See [`SECURITY.md`](SECURITY.md).

## Behaviour changes start in the contract

The three SDKs promise to behave identically, so a new or changed behaviour is not a change to one SDK. It starts as a pull request to [localaze-contract](https://github.com/localaze/localaze-contract): the rule, the conformance case and any fixture. Once it merges, this repository gets a pull request that moves `contract/` forward, and the implementation lands there. A fix that makes this SDK match the contract it already pins needs no contract change.

## Building and testing

You need JDK 17 or later; the Android builds also need an Android SDK.

```sh
git clone --recurse-submodules https://github.com/localaze/localaze-kotlin.git
cd localaze-kotlin
./gradlew test                                   # runtime and generator
(cd examples/android-app && ./verify-generated.sh)  # needs kotlinc
(cd android && ../gradlew -p . assembleRelease)     # needs ANDROID_HOME
(cd examples/android-app && ../../gradlew -p . assembleDebug)
```

When a change alters what the generator writes, the golden file changes with it: run `UPDATE_GOLDEN=1 ./gradlew :generator:test --tests '*GeneratorTest.golden'` and review the diff.

CI runs the same steps on every pull request, and a pull request merges only when they pass.

## The rules

The rules every Localaze SDK follows are in [`contract/CLAUDE.md`](https://github.com/localaze/localaze-contract/blob/main/CLAUDE.md). The ones that matter most for a contribution:

- **No public API throws.** A failure leaves the app on the text it already had, and calls `onDiagnostic` saying what failed and why.
- **No MF2 parsing and no CLDR data on the device.** Catalogs arrive parsed; plural rules arrive compiled into generated code.
- **Generated output is deterministic,** and a change to it is a change to the golden files, reviewed like any other API change.
- **No new runtime dependency** without discussing it first. Every dependency here is one every app using the SDK carries.

## Pull requests

- One change per pull request, with a description of what it changes and why.
- Tests for the change. A fix comes with the test that would have caught it.
- Update `CHANGELOG.md` under `Unreleased`.
- README code is checked by CI against compiled code (`contract/scripts/check-snippets.py`). To change an example, change the file it comes from, and copy the region into the README.

By contributing you agree that your contribution is licensed under the MIT License, like the rest of this repository.
