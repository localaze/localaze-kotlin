package dev.sharpy.localaze.fakes

import dev.sharpy.localaze.LocalazeConfig
import dev.sharpy.localaze.LocalazeDiagnostic
import dev.sharpy.localaze.LocalazeResponse
import dev.sharpy.localaze.LocalazeRuntime
import dev.sharpy.localaze.LocalazeSignature
import dev.sharpy.localaze.LocalazeStorage
import dev.sharpy.localaze.LocalazeTransport
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Fakes and the shared fixture project.
 *
 * These exist because every failure row in contract section 8 is reachable with
 * a fake and unreachable without one. A runtime testable only against a live
 * server is a runtime that is not tested.
 */

/**
 * Runs a suspending block to completion.
 *
 * Hand-rolled rather than `kotlinx.coroutines.runBlocking`, so the test sources
 * need no dependency either. Every fake here completes without ever suspending,
 * so this returns immediately in practice.
 */
fun <T> blocking(block: suspend () -> T): T {
    val latch = CountDownLatch(1)
    var outcome: Result<T>? = null

    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                outcome = result
                latch.countDown()
            }
        })

    latch.await()
    return outcome!!.getOrThrow()
}

/** Records what it was asked for and returns what it was told to. */
class FakeTransport(
    /**
     * Path fragment to response, matched by `contains`, so a caller writes
     * `/version` rather than the whole URL.
     */
    val responses: MutableMap<String, LocalazeResponse> = LinkedHashMap(),
) : LocalazeTransport {

    val urls = ArrayList<String>()
    val headers = ArrayList<Map<String, String>>()

    /** Set to make `get` behave as a transport that violates its contract. */
    var throwOnGet: Throwable? = null

    fun headerValue(name: String): String? =
        headers.firstNotNullOfOrNull { it[name] }

    override suspend fun get(url: String, headers: Map<String, String>): LocalazeResponse {
        urls.add(url)
        this.headers.add(headers)

        throwOnGet?.let { throw it }

        for ((fragment, response) in responses) {
            if (url.contains(fragment)) return response
        }
        return LocalazeResponse(status = 404)
    }
}

/** In-memory storage, so a test never touches the disk. */
class FakeStorage(
    var asset: String? = null,
    var cache: String? = null,
    var writable: Boolean = true,
) : LocalazeStorage {

    /** Set to throw from a read, for the unreadable cases. */
    var throwOnRead: Throwable? = null

    var writes = 0
        private set

    override suspend fun readAsset(language: String): String? {
        throwOnRead?.let { throw it }
        return asset
    }

    override suspend fun readCache(language: String): String? {
        throwOnRead?.let { throw it }
        return cache
    }

    override suspend fun writeCache(language: String, body: String): Boolean {
        if (!writable) return false
        writes++
        cache = body
        return true
    }
}

/** Collects diagnostics so a test can assert something was reported. */
class Reported {
    val messages = ArrayList<String>()

    val callback: LocalazeDiagnostic = { message, _ -> messages.add(message) }

    fun mentions(fragment: String): Boolean = messages.any { it.contains(fragment) }

    val isEmpty: Boolean get() = messages.isEmpty()
}

// The shared fixture project

/**
 * Loads a file from `contract/fixtures`, the contract submodule.
 *
 * One fixture project, shared by all three packages. A Kotlin-only copy would
 * drift from Dart's and Swift's within a release.
 */
fun fixture(path: String): String {
    val root = System.getProperty("localaze.fixtures")
        ?: "${System.getProperty("user.dir")}/contract/fixtures"
    return File("$root/$path").readText()
}

/**
 * Compiled CLDR rules for Russian, as `PluralRuleCompiler` emits them.
 *
 * The `i % 100 != 11` exclusion is the whole reason this is generated rather
 * than guessed: without it 11, 111 and 211 select `one` and read as
 * "У вас 11 сообщение".
 */
fun russianPlural(value: Double): String? {
    val i = Math.floor(Math.abs(value)).toInt()
    val v = if (value == i.toDouble()) 0 else 1

    if (v == 0 && (i % 10) == 1 && !((i % 100) == 11)) return "one"
    if (v == 0 && ((i % 10) in 2..4) && !((i % 100) in 12..14)) return "few"
    if (v == 0 && ((i % 10) == 0 || (i % 10) in 5..9 || (i % 100) in 11..14)) return "many"
    return null // `other`, which is the catch-all.
}

/** Azerbaijani has one rule and no exception. */
fun azerbaijaniPlural(value: Double): String? = if (value == 1.0) "one" else null

fun plural(locale: String, value: Double): String? =
    if (locale == "ru") russianPlural(value) else azerbaijaniPlural(value)

/** The signature the generator emits for the fixture project. */
val fixtureSignature: LocalazeSignature = LocalazeSignature.of(
    mapOf(
        "auth.sign_out" to emptyList(),
        "auth.welcome" to listOf("name" to "STRING"),
        "billing.due" to listOf("when" to "DATETIME"),
        "inbox.unread" to listOf("count" to "NUMBER"),
    ))

fun catalogFor(locale: String): String = fixture("catalogs/$locale.json")

/** A runtime configured the way generated code configures one. */
fun runtimeFor(
    locale: String,
    config: LocalazeConfig? = null,
    compiledIn: String? = null,
): LocalazeRuntime =
    LocalazeRuntime(
        locale = locale,
        config = config ?: LocalazeConfig(apiKey = "test-key"),
        signature = fixtureSignature,
        compiledIn = compiledIn ?: catalogFor(locale),
        pluralResolver = { plural(locale, it) })

/** A response carrying a fixture file. */
fun ok(body: String, etag: String? = null): LocalazeResponse =
    LocalazeResponse(status = 200, body = body, etag = etag)

/**
 * The `/version` endpoint's body. Note it carries no `messages`, which is why
 * the updater reads the field directly rather than through the catalog parser.
 */
fun versionBody(version: String): String =
    """
    {"environment":"production","version":"$version","bump":"PATCH",
     "note":null,"publishedAt":"2026-08-01T00:00:00Z","keyCount":4,
     "available":["1.0.0","$version"]}
    """.trimIndent()

/** A minimal ru catalog whose auth.sign_out reads [text]. */
fun catalogSaying(text: String, version: String): String =
    """
    {"version":"$version","language":"ru","messages":{
      "auth.sign_out":{"selectors":[],"params":[],"variants":{"*":[{"text":"$text"}]}},
      "auth.welcome":{"selectors":[],"params":[{"name":"name","type":"STRING"}],
        "variants":{"*":[{"text":"hi "},{"var":"name","type":"STRING"}]}},
      "billing.due":{"selectors":[],"params":[{"name":"when","type":"DATETIME"}],
        "variants":{"*":[{"var":"when","type":"DATETIME"}]}},
      "inbox.unread":{"selectors":["count"],"params":[{"name":"count","type":"NUMBER"}],
        "variants":{"*":[{"var":"count","type":"NUMBER"}]}}}}
    """.trimIndent()
