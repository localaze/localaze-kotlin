# localaze-android

The Android half of the Kotlin SDK, `dev.sharpy.localaze:localaze-android`: an `HttpURLConnection` transport and asset/`filesDir` storage, with no contract logic in them. Policy, the signature check, rendering and every failure path live in the pure-JVM runtime at the repository root, where their tests run in milliseconds.

It is a standalone Gradle build that includes the runtime as a composite build and depends on it by coordinates, exactly as an app does. It needs JDK 17 or newer and an Android SDK:

```sh
ANDROID_HOME=<sdk> ../gradlew -p . assembleRelease
```

**Documentation:** [the repository README](../README.md).
