package dev.sharpy.localaze.generator

import dev.sharpy.localaze.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The generator against the shared fixture project.
 *
 * See `contract/generator-contract.md` §9 and the `generator` group of
 * `contract/conformance.json`.
 */

internal val fixtures = File(System.getProperty("localaze.fixtures"))
internal val golden = File(System.getProperty("localaze.golden"))

internal fun fixture(path: String): String = File(fixtures, path).readText(Charsets.UTF_8)

@Suppress("UNCHECKED_CAST")
private fun json(text: String): MutableMap<String, Any?> = LinkedHashMap(Json.parse(text) as Map<String, Any?>)

/** A minimal JSON writer, enough to rebuild a fixture after changing it. */
private fun text(value: Any?): String = when (value) {
    null -> "null"
    is String -> literal(value)
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { "${text(it.key as String)}:${text(it.value)}" }
    is List<*> -> value.joinToString(",", "[", "]") { text(it) }
    else -> error("Cannot write $value")
}

private fun catalogs() = mapOf("az" to fixture("catalogs/az.json"), "ru" to fixture("catalogs/ru.json"))

private fun planFixture(
    manifest: Map<String, Any?>? = null,
    catalogs: Map<String, String>? = null,
    allowGaps: Boolean = false,
) = plan(text(manifest ?: json(fixture("manifest.json"))), catalogs ?: catalogs(), allowGaps)

private fun withKey(entry: Map<String, Any?>): Map<String, Any?> {
    val manifest = json(fixture("manifest.json"))
    manifest["keys"] = (manifest["keys"] as List<*>) + listOf(entry)
    return manifest
}

@Suppress("UNCHECKED_CAST")
private fun catalog(language: String, key: String, message: Map<String, Any?>): String {
    val catalog = json(fixture("catalogs/$language.json"))
    catalog["messages"] = LinkedHashMap(catalog["messages"] as Map<String, Any?>).apply { put(key, message) }
    return text(catalog)
}

@Suppress("UNCHECKED_CAST")
private fun ruWithout(key: String): String {
    val ru = json(fixture("catalogs/ru.json"))
    ru["messages"] = LinkedHashMap(ru["messages"] as Map<String, Any?>).apply { remove(key) }
    return text(ru)
}

private val simple: Map<String, Any?> = mapOf(
    "selectors" to emptyList<String>(), "params" to emptyList<Any>(),
    "variants" to mapOf("*" to listOf(mapOf("text" to "x"))),
)

class GeneratorTest {

    @Test
    fun golden() {
        val result = planFixture()
        assertEquals(emptyList(), result.problems)
        val source = emitKotlin(result.plan!!, "dev.sharpy.localaze.golden")

        // UPDATE_GOLDEN=1 rewrites the file. The diff is then reviewed like any
        // other change, which is the point of a golden.
        if (System.getenv("UPDATE_GOLDEN") == "1") golden.writeText(source, Charsets.UTF_8)
        assertEquals(golden.readText(Charsets.UTF_8), source)
    }

    @Test
    fun sameInputSameOutput() {
        assertEquals(emitKotlin(planFixture().plan!!, "p"), emitKotlin(planFixture().plan!!, "p"))
    }

    @Test
    fun catalogsAreCarriedByteForByte() {
        val planned = planFixture().plan!!
        assertEquals(catalogs(), planned.catalogs)
        assertEquals(listOf("az", "ru"), planned.languages)
    }

    @Test
    fun missingCatalog() {
        val result = planFixture(catalogs = mapOf("az" to fixture("catalogs/az.json")))
        assertNull(result.plan)
        assertTrue("No catalog for language \"ru\"." in result.problems)
    }

    @Test
    fun missingKeyFails() {
        val result = planFixture(catalogs = catalogs() + ("ru" to ruWithout("auth.sign_out")))
        assertNull(result.plan)
        assertTrue("auth.sign_out is missing in ru." in result.problems)
    }

    @Test
    fun allowGapsWarns() {
        val result = planFixture(catalogs = catalogs() + ("ru" to ruWithout("auth.sign_out")), allowGaps = true)
        assertNotNull(result.plan)
        assertEquals(listOf("auth.sign_out is missing in ru."), result.warnings)
    }

    @Test
    fun versionMismatch() {
        val ru = json(fixture("catalogs/ru.json")).apply { put("version", "1.0.1") }
        val result = planFixture(catalogs = catalogs() + ("ru" to text(ru)))
        assertNull(result.plan)
        assertTrue("The \"ru\" catalog is version 1.0.1, but the manifest is 1.0.0." in result.problems)
    }

    @Test
    fun unsupportedOperand() {
        val manifest = json(fixture("manifest.json"))
        manifest["pluralRules"] = LinkedHashMap(manifest["pluralRules"] as Map<*, *>).apply { put("ru", mapOf("one" to "f == 1")) }
        val result = planFixture(manifest)
        assertNull(result.plan)
        assertEquals(1, result.problems.size)
        assertTrue(result.problems[0].contains("uses f"))
    }

    @Test
    fun unrecognisedRule() {
        val manifest = json(fixture("manifest.json"))
        manifest["pluralRules"] = LinkedHashMap(manifest["pluralRules"] as Map<*, *>).apply { put("ru", mapOf("one" to "n == 1; exit(0)")) }
        val result = planFixture(manifest)
        assertNull(result.plan)
        assertTrue(result.problems[0].contains("exit"))
    }

    @Test
    fun accessorCollision() {
        val manifest = json(fixture("manifest.json"))
        manifest["keys"] = listOf(
            mapOf("key" to "a.b_c", "params" to emptyList<Any>(), "selects" to false),
            mapOf("key" to "a_b.c", "params" to emptyList<Any>(), "selects" to false),
        )
        val result = planFixture(manifest, mapOf(
            "az" to catalog("az", "a_b.c", simple), "ru" to catalog("ru", "a_b.c", simple)), allowGaps = true)
        assertNull(result.plan)
        assertTrue("Keys \"a.b_c\" and \"a_b.c\" both generate the accessor \"aBC\"." in result.problems)
    }

    @Test
    fun reservedMember() {
        val result = planFixture(
            withKey(mapOf("key" to "runtime", "params" to emptyList<Any>(), "selects" to false)),
            mapOf("az" to catalog("az", "runtime", simple), "ru" to catalog("ru", "runtime", simple)))
        assertNull(result.plan)
        assertTrue(result.problems[0].contains("\"runtime\", which is reserved"))
    }

    @Test
    fun nonNumberSelector() {
        val message = mapOf(
            "selectors" to listOf("who"), "params" to listOf(mapOf("name" to "who", "type" to "STRING")),
            "variants" to mapOf("*" to listOf(mapOf("var" to "who", "type" to "STRING"))))
        val result = planFixture(
            withKey(mapOf("key" to "greeting", "params" to listOf(mapOf("name" to "who", "type" to "STRING")), "selects" to true)),
            mapOf("az" to catalog("az", "greeting", message), "ru" to catalog("ru", "greeting", message)))
        assertNull(result.plan)
        assertTrue(result.problems[0].contains("selects on \$who, a STRING"))
    }

    @Test
    fun allProblemsReported() {
        val manifest = json(fixture("manifest.json"))
        manifest["pluralRules"] = LinkedHashMap(manifest["pluralRules"] as Map<*, *>).apply { put("ru", mapOf("one" to "t == 1")) }
        val result = planFixture(manifest, mapOf("az" to fixture("catalogs/az.json")))
        assertEquals(2, result.problems.size)
    }

    @Test
    fun reservedKotlinWordIsBackticked() {
        val message = mapOf(
            "selectors" to emptyList<String>(), "params" to listOf(mapOf("name" to "in", "type" to "STRING")),
            "variants" to mapOf("*" to listOf(mapOf("var" to "in", "type" to "STRING"))))
        val result = planFixture(
            withKey(mapOf("key" to "object", "params" to listOf(mapOf("name" to "in", "type" to "STRING")), "selects" to false)),
            mapOf("az" to catalog("az", "object", message), "ru" to catalog("ru", "object", message)))
        val source = emitKotlin(result.plan!!, "p")
        assertTrue(source.contains("fun `object`(`in`: String): String ="))
        assertTrue(source.contains("mapOf(\"in\" to `in`)"))
    }

    @Test
    fun integerLiteralsBecomeDoubles() {
        assertEquals("(i % 10.0) == 1.0 && n >= 2.0 && !((i % 100.0) == 11.0)",
            kotlinCondition("(i % 10) == 1 && n >= 2 && !((i % 100) == 11)"))
    }

    @Test
    fun aLargeCatalogIsSplitBelowTheConstantLimit() {
        val big = "x".repeat(25_000)
        val result = planFixture()
        val planned = result.plan!!.copy(catalogs = mapOf("az" to big, "ru" to big))
        val source = emitKotlin(planned, "p")
        assertTrue(source.contains(").joinToString(\"\"),"))
        assertTrue(source.lines().none { it.length > 10_100 }, "no literal exceeds a chunk")
    }
}
