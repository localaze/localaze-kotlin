#!/usr/bin/env bash
#
# Compiles and runs the conformance suite with kotlinc alone.
#
# `./gradlew test` is the real entry point. This exists because Gradle is not
# always present — and because it proves the property the pure-JVM core was
# chosen for: the suite needs no Android SDK, no emulator, and no build tool.
#
# Requires kotlinc on PATH. JUnit 5's API jar is needed only so `kotlin.test`
# can bind its @Test annotation; the suite is driven by ConformanceRunner
# rather than by a JUnit engine.

set -euo pipefail

cd "$(dirname "$0")"

KOTLIN_LIB="$(dirname "$(readlink -f "$(command -v kotlinc)")")/../libexec/lib"
if [ ! -d "$KOTLIN_LIB" ]; then
  KOTLIN_LIB="$(dirname "$(readlink -f "$(command -v kotlinc)")")/../lib"
fi

M2="${HOME}/.m2/repository"
JUNIT="${M2}/org/junit/jupiter/junit-jupiter-api/5.11.4/junit-jupiter-api-5.11.4.jar"
GUARDIAN="${M2}/org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar"
OPENTEST="${M2}/org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar"

for jar in "$JUNIT" "$GUARDIAN" "$OPENTEST"; do
  if [ ! -f "$jar" ]; then
    echo "missing $jar" >&2
    echo "run ./gradlew test instead, which resolves these itself" >&2
    exit 1
  fi
done

mkdir -p build

echo "compiling the core..."
kotlinc src/main/kotlin -d build/localaze.jar

# -Xfriend-paths is what makes `internal` visible to the test sources, which is
# what Gradle does for src/test by default.
echo "compiling the tests..."
CP="build/localaze.jar:${KOTLIN_LIB}/kotlin-test.jar:${KOTLIN_LIB}/kotlin-test-junit5.jar:${JUNIT}:${GUARDIAN}:${OPENTEST}"
kotlinc src/test/kotlin -cp "$CP" -Xfriend-paths=build/localaze.jar -d build/tests.jar

echo "running the conformance suite..."
kotlin -cp "build/tests.jar:${CP}" dev.sharpy.localaze.conformance.ConformanceRunner
