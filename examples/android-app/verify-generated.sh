#!/usr/bin/env bash
#
# Compiles Localaze.g.kt against the SDK core and checks what it renders.
#
# `./gradlew installDebug` is the real entry point, and it needs JDK 17+ and the
# Android SDK. This needs neither — only kotlinc and the core jar — so the part
# of this app that matters most can be verified anywhere.
#
# What it covers: the generated accessors, the compiled Russian plural rule
# including the 11 and 111 exceptions, and the ISO-8601 date rendering. What it
# does not: the Compose UI and the Android transport and storage, which need a
# real Android build.
#
# The expected strings are the same ones the Dart and Swift demos assert, which
# is the LZ-54 guarantee in practice.

set -euo pipefail

cd "$(dirname "$0")"

CORE_DIR="../.."
CORE_JAR="$(ls "$CORE_DIR"/build/libs/localaze-*.jar 2>/dev/null | grep -v -- -sources.jar | head -1 || true)"

if [ -z "$CORE_JAR" ]; then
  echo "Building the core first..."
  (cd "$CORE_DIR" && ./gradlew --quiet jar) \
    || (cd "$CORE_DIR" && kotlinc src/main/kotlin -d build/libs/localaze-0.1.0.jar)
  CORE_JAR="$(ls "$CORE_DIR"/build/libs/localaze-*.jar | grep -v -- -sources.jar | head -1)"
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

cat > "$OUT/Check.kt" <<'KOTLIN'
import dev.sharpy.localaze.LocalazeConfig
import dev.sharpy.localaze.demo.Localaze
import java.time.Instant

fun main() {
    val az = Localaze.forLocale("az", LocalazeConfig(apiKey = "k"))
    val ru = Localaze.forLocale("ru", LocalazeConfig(apiKey = "k"))
    var bad = 0

    fun check(got: String, want: String) {
        if (got != want) {
            println("  FAIL  want \"$want\"  got \"$got\"")
            bad++
        } else {
            println("  ok    $got")
        }
    }

    check(az.authSignOut, "Cixis")
    check(az.authWelcome(name = "Ayan"), "Xos geldiniz, Ayan")
    check(
        az.billingDue(Instant.parse("2026-03-01T00:00:00Z")),
        "Odenis 2026-03-01T00:00:00Z tarixindedir")
    check(az.inboxUnread(count = 3), "3 mesaj")
    check(ru.authSignOut, "Выход")

    // The Russian rule is why plurals are generated rather than guessed:
    // 11 and 111 select `many` despite ending in 1.
    for ((n, want) in listOf(
        1 to "У вас 1 сообщение",
        2 to "У вас 2 сообщения",
        5 to "У вас 5 сообщений",
        11 to "У вас 11 сообщений",
        21 to "У вас 21 сообщение",
        101 to "У вас 101 сообщение",
        111 to "У вас 111 сообщений",
    )) check(ru.inboxUnread(count = n), want)

    println()
    if (bad == 0) {
        println("generated Kotlin renders identically to the Dart and Swift demos")
    } else {
        println("$bad wrong")
        kotlin.system.exitProcess(1)
    }
}
KOTLIN

kotlinc \
  src/main/kotlin/dev/sharpy/localaze/demo/Localaze.g.kt \
  "$OUT/Check.kt" \
  -cp "$CORE_JAR" -include-runtime -d "$OUT/check.jar"

java -cp "$OUT/check.jar:$CORE_JAR" CheckKt
