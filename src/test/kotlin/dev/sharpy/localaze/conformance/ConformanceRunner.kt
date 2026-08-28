package dev.sharpy.localaze.conformance

import kotlin.test.Test

/**
 * Runs [ConformanceTest] without a JUnit engine.
 *
 * The tests are annotated with `kotlin.test.@Test`, so Gradle runs them through
 * JUnit unchanged and this file is never involved. It exists so the suite is
 * also runnable with nothing but `kotlinc` and `kotlin` — which is what makes
 * the pure-JVM core verifiable on a machine with no Gradle and no Android SDK,
 * and it is the reason the core is pure JVM in the first place.
 *
 * Reflection over the annotation rather than a hand-maintained list: a test
 * added to the class is picked up here without anyone remembering to.
 */
object ConformanceRunner {

    @JvmStatic
    fun main(args: Array<String>) {
        val suite = ConformanceTest::class.java
        val tests = suite.declaredMethods
            .filter { it.isAnnotationPresent(Test::class.java) }
            .sortedBy { it.name }

        if (tests.isEmpty()) {
            println("no @Test methods found — the runner is not seeing the suite")
            kotlin.system.exitProcess(1)
        }

        var passed = 0
        val failures = ArrayList<String>()

        for (test in tests) {
            val instance = suite.getDeclaredConstructor().newInstance()
            try {
                test.invoke(instance)
                passed++
                println("  ok    ${test.name}")
            } catch (error: Throwable) {
                val cause = error.cause ?: error
                failures.add("${test.name}: ${cause.message ?: cause}")
                println("  FAIL  ${test.name}")
                println("        ${cause.message ?: cause}")
            }
        }

        println()
        println("$passed passed, ${failures.size} failed, ${tests.size} total")

        if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
    }
}
