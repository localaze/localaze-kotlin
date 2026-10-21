rootProject.name = "localaze"

// The generator: a command an app's build runs, never a dependency it links.
include("generator")

// One module, and it is pure Kotlin/JVM. The Android adapters — asset loading
// from a Context, filesDir caching, an OkHttp transport — are a second module
// added when the Android team confirms AGP, and they are about fifty lines.
//
// Keeping the core off Android is what lets the conformance suite run on the
// JVM in milliseconds instead of on an emulator, and it is why every failure
// path in the contract has a test.
