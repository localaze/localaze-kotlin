package dev.sharpy.localaze.conformance

import dev.sharpy.localaze.LocalazeCatalog
import dev.sharpy.localaze.LocalazeConfig
import dev.sharpy.localaze.LocalazeFetchOutcome
import dev.sharpy.localaze.LocalazeResponse
import dev.sharpy.localaze.LocalazeUpdatePolicy
import dev.sharpy.localaze.LocalazeVersion
import dev.sharpy.localaze.localazeIsoUtc
import dev.sharpy.localaze.localazeRender
import dev.sharpy.localaze.fakes.FakeStorage
import dev.sharpy.localaze.fakes.FakeTransport
import dev.sharpy.localaze.fakes.Reported
import dev.sharpy.localaze.fakes.blocking
import dev.sharpy.localaze.fakes.catalogSaying
import dev.sharpy.localaze.fakes.fixture
import dev.sharpy.localaze.fakes.fixtureSignature
import dev.sharpy.localaze.fakes.ok
import dev.sharpy.localaze.fakes.plural
import dev.sharpy.localaze.fakes.runtimeFor
import dev.sharpy.localaze.fakes.versionBody
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The conformance suite, mirroring `contract/conformance.json`.
 *
 * Annotated with `kotlin.test.@Test`, so Gradle runs these through JUnit
 * unchanged. `ConformanceRunner` also drives them directly, which is how they
 * run on a machine with no Gradle.
 */
class ConformanceTest {

    private fun parse(path: String): LocalazeCatalog = LocalazeCatalog.parse(fixture(path))!!

    // Rendering — contract section 3

    @Test
    fun renderAll() {
        // Driven from the shared expected-renders.json, so Dart, Swift and
        // Kotlin assert against the identical set.
        //
        // Json is internal to the package; test sources are a friend module,
        // which is what Gradle does by default and what -Xfriend-paths does
        // when this runs without Gradle.
        @Suppress("UNCHECKED_CAST")
        val decoded = dev.sharpy.localaze.Json.parse(
            fixture("expected-renders.json")) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val rows = decoded["renders"] as List<Map<String, Any?>>

        assertTrue(rows.size >= 20, "a dropped row would hollow out this test")

        for (row in rows) {
            val locale = row["locale"] as String
            val key = row["key"] as String
            val want = row["expect"] as String
            val args = coerce(row["args"] as Map<String, Any?>)

            val runtime = runtimeFor(locale)
            val selector = (args["count"] as? Int)?.toDouble()

            assertEquals(want, runtime.render(key, args, selector), "$locale $key")
        }
    }

    private fun coerce(args: Map<String, Any?>): Map<String, Any?> =
        args.mapValues { (key, value) ->
            if (key == "when" && value is String) Instant.parse(value) else value
        }

    @Test
    fun renderMissingCategoryFallsBack() {
        // A ru catalog whose inbox.unread has only `one` and `*`. Count 5
        // resolves to `many`, which is absent, so `*` must answer.
        val runtime = runtimeFor("ru", compiledIn = fixture("bundled/partial-ru.json"))

        assertEquals(
            "У вас 5 сообщения",
            runtime.render("inbox.unread", mapOf("count" to 5), 5.0))
    }

    @Test
    fun renderMalformedPartSkipped() {
        val body = """
            {"version":"1.0.0","messages":{"a.b":{"selectors":[],"params":[],
             "variants":{"*":[{"text":"before "},{"note":"neither"},{"text":"after"}]}}}}
        """.trimIndent()
        val parts = LocalazeCatalog.parse(body)!!.messages["a.b"]!!.partsFor(null)!!

        assertEquals("before after", localazeRender(parts, emptyMap()))
    }

    @Test
    fun isoUtcIsSecondPrecision() {
        assertEquals("2026-03-01T00:00:00Z", localazeIsoUtc(Instant.parse("2026-03-01T00:00:00Z")))
        assertEquals(
            "2026-12-31T23:59:59Z", localazeIsoUtc(Instant.parse("2026-12-31T23:59:59Z")))
        // Sub-second precision is dropped, not rendered, so the three platforms
        // agree on the string.
        assertEquals(
            "2026-03-01T00:00:00Z", localazeIsoUtc(Instant.parse("2026-03-01T00:00:00.123Z")))
    }

    @Test
    fun appSuppliedFormatterWins() {
        val runtime = runtimeFor(
            "az",
            config = LocalazeConfig(
                apiKey = "k",
                dateFormatter = { value ->
                    val date = value.atZone(java.time.ZoneOffset.UTC)
                    "${date.dayOfMonth}/${date.monthValue}"
                }))

        assertEquals(
            "Odenis 1/3 tarixindedir",
            runtime.render("billing.due", mapOf("when" to Instant.parse("2026-03-01T00:00:00Z"))))
    }

    @Test
    fun integralDoubleHasNoDecimalPoint() {
        // 3.0 must read as "3" here, in Dart and in Swift alike.
        val runtime = runtimeFor("az")
        assertEquals("3 mesaj", runtime.render("inbox.unread", mapOf("count" to 3.0), 3.0))
        assertEquals("2.5 mesaj", runtime.render("inbox.unread", mapOf("count" to 2.5), 2.5))
    }

    @Test
    fun unknownKeyRendersTheKeyAndReports() {
        val reported = Reported()
        val runtime = runtimeFor(
            "az", config = LocalazeConfig(apiKey = "k", onDiagnostic = reported.callback))

        assertEquals("nope.missing", runtime.render("nope.missing"))
        assertTrue(reported.mentions("nope.missing"))
    }

    // Policy — contract section 5

    private fun accepts(held: String, offered: String, policy: LocalazeUpdatePolicy) =
        LocalazeVersion.accepts(held, offered, policy)

    @Test
    fun policyAcceptsAndRefuses() {
        assertTrue(accepts("1.0.0", "1.0.1", LocalazeUpdatePolicy.MINOR_AND_PATCH))
        assertTrue(accepts("1.0.0", "1.0.1", LocalazeUpdatePolicy.PATCH_ONLY))
        assertTrue(accepts("1.0.0", "1.1.0", LocalazeUpdatePolicy.MINOR_AND_PATCH))
        assertFalse(accepts("1.0.0", "1.1.0", LocalazeUpdatePolicy.PATCH_ONLY))
        assertFalse(accepts("1.1.0", "1.0.1", LocalazeUpdatePolicy.MINOR_AND_PATCH))
        // accepts() says no for the same version too — there is nothing to
        // take. The updater turns that into upToDate rather than a refusal,
        // which sameVersionIsUpToDate covers.
        assertFalse(accepts("1.0.0", "1.0.0", LocalazeUpdatePolicy.MINOR_AND_PATCH))
    }

    @Test
    fun versionEndpointReportsItsStatus() = blocking {
        // Found by the example app, not by a unit test. Without the status a
        // wrong API key and a quiet network produce the same diagnostic.
        val reported = Reported()
        val runtime = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k",
                onDiagnostic = reported.callback,
                transport = FakeTransport(
                    mutableMapOf("/version" to LocalazeResponse(status = 401)))))

        val status = runtime.update()

        assertEquals(LocalazeFetchOutcome.FAILED, status.outcome)
        assertTrue(
            reported.mentions("401"),
            "a 401 must be distinguishable from a quiet network")
    }

    @Test
    fun sameVersionIsUpToDate() = blocking {
        // Nothing was refused: the server and the app agree. This is the most
        // common outcome in production, so its wording matters most.
        val reported = Reported()
        val runtime = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k",
                onDiagnostic = reported.callback,
                transport = FakeTransport(
                    mutableMapOf("/version" to ok(versionBody("1.0.0"))))))

        val status = runtime.update()

        assertEquals(LocalazeFetchOutcome.UP_TO_DATE, status.outcome)
        assertEquals("1.0.0", status.version)
        assertTrue(reported.isEmpty, "${reported.messages}")
    }

    @Test
    fun majorRefusedUnderEveryPolicy() {
        // The one rule no policy may override.
        val policies = listOf(
            LocalazeUpdatePolicy.NONE,
            LocalazeUpdatePolicy.PATCH_ONLY,
            LocalazeUpdatePolicy.MINOR_AND_PATCH,
            LocalazeUpdatePolicy.Range("1.0.0", "1.2.0"),
            LocalazeUpdatePolicy.Range("1.0.0", "9.9.9"),
        )
        for (policy in policies) {
            assertFalse(accepts("1.0.0", "2.0.0", policy), policy.label)
        }
    }

    @Test
    fun noneFetchesNothing() {
        assertFalse(LocalazeUpdatePolicy.NONE.fetches)
        assertTrue(LocalazeUpdatePolicy.MINOR_AND_PATCH.fetches)
    }

    @Test
    fun versionsCompareNumerically() {
        // String comparison would put 1.10.0 below 1.9.0.
        assertTrue(accepts("1.9.0", "1.10.0", LocalazeUpdatePolicy.MINOR_AND_PATCH))
        assertFalse(accepts("1.10.0", "1.9.0", LocalazeUpdatePolicy.MINOR_AND_PATCH))
        assertTrue(LocalazeVersion.parse("1.10.0")!! > LocalazeVersion.parse("1.9.0")!!)
    }

    @Test
    fun malformedVersionRefused() {
        for (bad in listOf("latest", "2.0", "", "1.0.0-rc1", "x.y.z", "1.-1.0")) {
            assertNull(LocalazeVersion.parse(bad), bad)
            assertFalse(accepts("1.0.0", bad, LocalazeUpdatePolicy.MINOR_AND_PATCH), bad)
            assertFalse(accepts(bad, "1.0.1", LocalazeUpdatePolicy.MINOR_AND_PATCH), bad)
        }
    }

    @Test
    fun rangeBounds() {
        val range = LocalazeUpdatePolicy.Range("1.0.0", "1.2.0")
        assertTrue(accepts("1.0.0", "1.1.0", range))
        assertTrue(accepts("1.0.0", "1.2.0", range))
        assertFalse(accepts("1.0.0", "1.3.0", range))
        assertFalse(accepts("1.0.0", "1.1.0", LocalazeUpdatePolicy.Range("1.0", "latest")))
    }

    // Signature — contract section 4

    @Test
    fun acceptsTheCatalogItWasGeneratedFrom() {
        assertTrue(fixtureSignature.incompatibilities(parse("catalogs/ru.json")).isEmpty())
    }

    @Test
    fun retypedParameterRefused() {
        // 1.0.2 is a patch, so policy accepts it. Every key is present, so a
        // key-set check accepts it. inbox.unread still declares a parameter
        // named "count", so a name-only check accepts it too.
        //
        // The type changed from NUMBER to STRING. Generated code has already
        // promised every call site inboxUnread(count: Int).
        val problems = fixtureSignature.incompatibilities(
            parse("updates/retyped-1.0.2-ru.json"))

        assertEquals(1, problems.size, "$problems")
        assertTrue(problems[0].contains("inbox.unread.count"), problems[0])
        assertTrue(problems[0].contains("now string"), problems[0])
        assertTrue(problems[0].contains("generated as number"), problems[0])
    }

    @Test
    fun nameOnlyCheckWouldHaveAcceptedIt() {
        // Reproduces the old logic against the same fixture. If this ever
        // fails, the fixture stopped exercising the bug.
        val catalog = parse("updates/retyped-1.0.2-ru.json")
        val namesOnly = ArrayList<String>()

        for ((key, expected) in fixtureSignature.keys) {
            val message = catalog.messages[key]
            if (message == null) {
                namesOnly.add("missing $key")
                continue
            }
            val declared = message.params.map { it.name }.toSet()
            for (param in expected) {
                if (param.name !in declared) namesOnly.add("$key lost ${param.name}")
            }
        }

        assertTrue(
            namesOnly.isEmpty(),
            "the name-only check finds nothing wrong, which is the defect")
    }

    @Test
    fun missingKeyRefused() {
        val problems = fixtureSignature.incompatibilities(
            parse("updates/major-2.0.0-ru.json"))
        assertTrue(problems.contains("auth.sign_out is missing"), "$problems")
    }

    @Test
    fun addedKeyIgnored() {
        val catalog = parse("updates/minor-1.1.0-ru.json")
        assertTrue(fixtureSignature.incompatibilities(catalog).isEmpty())
        assertTrue(catalog.messages.containsKey("settings.title"))
        assertFalse(fixtureSignature.keys.containsKey("settings.title"))
    }

    @Test
    fun extraParameterIgnoredAndRenameRefused() {
        // The signature is a floor, not an equality.
        val extra = """
            {"version":"1.0.1","messages":{
              "auth.sign_out":{"selectors":[],"params":[],"variants":{"*":[{"text":"x"}]}},
              "auth.welcome":{"selectors":[],"params":[
                {"name":"name","type":"STRING"},{"name":"extra","type":"NUMBER"}],
                "variants":{"*":[{"var":"name","type":"STRING"}]}},
              "billing.due":{"selectors":[],"params":[{"name":"when","type":"DATETIME"}],
                "variants":{"*":[{"var":"when","type":"DATETIME"}]}},
              "inbox.unread":{"selectors":["count"],"params":[{"name":"count","type":"NUMBER"}],
                "variants":{"*":[{"var":"count","type":"NUMBER"}]}}}}
        """.trimIndent()
        assertTrue(fixtureSignature.incompatibilities(LocalazeCatalog.parse(extra)!!).isEmpty())

        val renamed = extra.replace("\"name\":\"name\"", "\"name\":\"username\"")
        val problems = fixtureSignature.incompatibilities(LocalazeCatalog.parse(renamed)!!)
        assertEquals(1, problems.size, "$problems")
        assertTrue(problems[0].contains("no longer takes \"name\""), problems[0])
    }

    @Test
    fun refusalIsWhole() = blocking {
        // One key of four is incompatible. None of the four may be applied.
        val transport = FakeTransport(
            mutableMapOf(
                "/version" to ok(versionBody("1.0.2")),
                "/translations" to ok(fixture("updates/retyped-1.0.2-ru.json"))))
        val runtime = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k", applyImmediately = true, transport = transport))

        val status = runtime.update()

        assertEquals(LocalazeFetchOutcome.REFUSED_BY_SIGNATURE, status.outcome)
        // auth.sign_out is identical in the refused catalog, and still must not
        // be taken from it.
        assertEquals("Выход", runtime.render("auth.sign_out"))
        assertEquals("1.0.0", runtime.version)
    }

    @Test
    fun recheckedAfterUpgrade() = blocking {
        val reported = Reported()
        val storage = FakeStorage(cache = fixture("updates/retyped-1.0.2-ru.json"))
        val runtime = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k", onDiagnostic = reported.callback, storage = storage))

        runtime.load()

        assertEquals(
            "У вас 5 сообщений", runtime.render("inbox.unread", mapOf("count" to 5), 5.0))
        assertTrue(reported.mentions("Dropping the cached ru catalog"))
    }

    // Fetch — contract section 6

    @Test
    fun sendsApiKeyAndNoProjectInUrl() = blocking {
        val transport = FakeTransport(
            mutableMapOf(
                "/version" to ok(versionBody("1.0.1")),
                "/translations" to ok(fixture("updates/patch-1.0.1-ru.json"))))

        runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "test-key", applyImmediately = true, transport = transport))
            .update()

        assertTrue(transport.headers.isNotEmpty())
        for (headers in transport.headers) {
            assertEquals("test-key", headers["X-Localaze-Key"])
        }
        // The key carries the project and the environment, so neither appears
        // in any URL.
        for (url in transport.urls) {
            assertFalse(url.contains("project"), url)
            assertFalse(url.contains("environment"), url)
        }
    }

    @Test
    fun sendsIfNoneMatch() = blocking {
        // Not done by the implementation this replaces, though the server has
        // served version-scoped ETags all along.
        val transport = FakeTransport(
            mutableMapOf(
                "/version" to ok(versionBody("1.0.1")),
                "/translations" to ok(
                    fixture("updates/patch-1.0.1-ru.json"), etag = "\"1.0.1-ab\"")))
        val runtime = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k", applyImmediately = true, transport = transport))

        runtime.update()
        assertNull(transport.headerValue("If-None-Match"), "nothing is held yet")

        transport.responses["/version"] = ok(versionBody("1.0.2"))
        runtime.update()

        assertEquals("\"1.0.1-ab\"", transport.headerValue("If-None-Match"))
    }

    @Test
    fun notModifiedKeepsContent() = blocking {
        val reported = Reported()
        val transport = FakeTransport(
            mutableMapOf(
                "/version" to ok(versionBody("1.0.1")),
                "/translations" to LocalazeResponse(status = 304, etag = "\"1.0.1-ab\"")))
        val runtime = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k", applyImmediately = true,
                onDiagnostic = reported.callback, transport = transport))

        val status = runtime.update()

        // A success, not a failure.
        assertEquals(LocalazeFetchOutcome.UP_TO_DATE, status.outcome)
        assertEquals("Выход", runtime.render("auth.sign_out"))
        assertTrue(reported.isEmpty, "${reported.messages}")
    }

    @Test
    fun pinnedVersion() = blocking {
        val transport = FakeTransport(
            mutableMapOf("/translations" to ok(fixture("updates/patch-1.0.1-ru.json"))))

        val status = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k", applyImmediately = true, transport = transport))
            .update(pinnedVersion = "1.0.1")

        assertEquals(LocalazeFetchOutcome.APPLIED, status.outcome)
        assertTrue(transport.urls[0].contains("version=1.0.1"))
        assertTrue(transport.urls[0].contains("format=resolved"))
        // A pin answers the version question, so that endpoint is not hit.
        assertTrue(transport.urls.none { it.contains("/read/version") })
    }

    @Test
    fun nothingWrittenBeforeSignaturePasses() = blocking {
        val storage = FakeStorage()
        val transport = FakeTransport(
            mutableMapOf(
                "/version" to ok(versionBody("1.0.2")),
                "/translations" to ok(fixture("updates/retyped-1.0.2-ru.json"))))

        val status = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k", applyImmediately = true,
                transport = transport, storage = storage))
            .update()

        assertEquals(LocalazeFetchOutcome.REFUSED_BY_SIGNATURE, status.outcome)
        assertEquals(0, storage.writes, "a refused catalog must leave no trace")
        assertNull(storage.cache)
    }

    @Test
    fun orderIsPolicyVersionDownloadSignaturePersist() = blocking {
        // policy none short-circuits before the transport is touched.
        val first = FakeTransport(mutableMapOf("/version" to ok(versionBody("1.0.1"))))
        runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k", policy = LocalazeUpdatePolicy.NONE, transport = first))
            .update()
        assertTrue(first.urls.isEmpty())

        // A version the policy refuses stops before the catalog is downloaded.
        val second = FakeTransport(
            mutableMapOf(
                "/version" to ok(versionBody("1.1.0")),
                "/translations" to ok(fixture("updates/minor-1.1.0-ru.json"))))
        runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k", policy = LocalazeUpdatePolicy.PATCH_ONLY, transport = second))
            .update()

        assertEquals(1, second.urls.size)
        assertTrue(second.urls[0].contains("/read/version"))
    }

    @Test
    fun patchChangesWordingWithoutRegenerating() = blocking {
        // The outcome the whole design exists for.
        val storage = FakeStorage()
        val transport = FakeTransport(
            mutableMapOf(
                "/version" to ok(versionBody("1.0.1")),
                "/translations" to ok(fixture("updates/patch-1.0.1-ru.json"))))
        val runtime = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k", applyImmediately = true,
                transport = transport, storage = storage))

        assertEquals("Выход", runtime.render("auth.sign_out"))
        runtime.update()
        assertEquals("Выйти", runtime.render("auth.sign_out"))
        assertEquals("1.0.1", runtime.version)
        assertEquals(1, storage.writes)
    }

    // Resolution — contract section 7

    @Test
    fun appliedOverCachedOverBundledOverCompiledIn() = blocking {
        val storage = FakeStorage(
            asset = catalogSaying("from-bundled", "1.0.0"),
            cache = catalogSaying("from-cached", "1.0.0"))
        val transport = FakeTransport(
            mutableMapOf(
                "/version" to ok(versionBody("1.0.1")),
                "/translations" to ok(catalogSaying("from-applied", "1.0.1"))))

        val runtime = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k", applyImmediately = true,
                transport = transport, storage = storage),
            compiledIn = catalogSaying("from-compiled-in", "1.0.0"))

        assertEquals("from-compiled-in", runtime.render("auth.sign_out"))

        runtime.load()
        assertEquals("from-cached", runtime.render("auth.sign_out"))

        runtime.update()
        assertEquals("from-applied", runtime.render("auth.sign_out"))
    }

    @Test
    fun perKeyFallback() = blocking {
        // partial-ru.json omits billing.due. The three keys it has come from
        // the asset; the missing one falls through to the compiled-in copy.
        val storage = FakeStorage(asset = fixture("bundled/partial-ru.json"))
        val runtime = runtimeFor(
            "ru", config = LocalazeConfig(apiKey = "k", storage = storage))

        runtime.load()

        assertEquals("Выход", runtime.render("auth.sign_out"))
        assertEquals(
            "Оплата до 2026-03-01T00:00:00Z",
            runtime.render(
                "billing.due", mapOf("when" to Instant.parse("2026-03-01T00:00:00Z"))))
    }

    @Test
    fun cacheSurvivesLaunch() = blocking {
        val storage = FakeStorage(asset = catalogSaying("from-bundled", "1.0.0"))
        val transport = FakeTransport(
            mutableMapOf(
                "/version" to ok(versionBody("1.0.1")),
                "/translations" to ok(catalogSaying("from-applied", "1.0.1"))))

        val first = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k", applyImmediately = true,
                transport = transport, storage = storage),
            compiledIn = catalogSaying("compiled", "1.0.0"))
        first.load()
        first.update()

        val relaunched = runtimeFor(
            "ru",
            config = LocalazeConfig(apiKey = "k", storage = storage),
            compiledIn = catalogSaying("compiled", "1.0.0"))
        relaunched.load()

        assertEquals("from-applied", relaunched.render("auth.sign_out"))
        assertEquals("1.0.1", relaunched.version)
    }

    @Test
    fun noStorageIsSessionOnly() = blocking {
        val transport = FakeTransport(
            mutableMapOf(
                "/version" to ok(versionBody("1.0.1")),
                "/translations" to ok(catalogSaying("from-applied", "1.0.1"))))
        val config = LocalazeConfig(
            apiKey = "k", applyImmediately = true, transport = transport)

        val session = runtimeFor(
            "ru", config = config, compiledIn = catalogSaying("compiled", "1.0.0"))
        val status = session.update()

        assertEquals(LocalazeFetchOutcome.APPLIED, status.outcome)
        assertTrue(status.detail!!.contains("this session only"))
        assertEquals("from-applied", session.render("auth.sign_out"))

        val relaunched = runtimeFor(
            "ru", config = config, compiledIn = catalogSaying("compiled", "1.0.0"))
        relaunched.load()
        assertEquals("compiled", relaunched.render("auth.sign_out"))
    }

    @Test
    fun offlineFirstLaunch() {
        // Fresh install, no network, no cache. Every key still renders, and no
        // request is made at all.
        val transport = FakeTransport()
        val runtime = runtimeFor(
            "ru", config = LocalazeConfig(apiKey = "k", transport = transport))

        assertEquals("Выход", runtime.render("auth.sign_out"))
        assertEquals(
            "Добро пожаловать, Аян",
            runtime.render("auth.welcome", mapOf("name" to "Аян")))
        assertEquals(
            "У вас 11 сообщений", runtime.render("inbox.unread", mapOf("count" to 11), 11.0))
        assertTrue(transport.urls.isEmpty())
    }

    @Test
    fun applyImmediatelyOffHoldsTheCatalog() = blocking {
        val transport = FakeTransport(
            mutableMapOf(
                "/version" to ok(versionBody("1.0.1")),
                "/translations" to ok(catalogSaying("from-applied", "1.0.1"))))
        val runtime = runtimeFor(
            "ru",
            config = LocalazeConfig(apiKey = "k", transport = transport),
            compiledIn = catalogSaying("compiled", "1.0.0"))

        runtime.update()
        assertEquals(
            "compiled", runtime.render("auth.sign_out"),
            "text must not change under a user mid-session")

        assertTrue(runtime.applyPending())
        assertEquals("from-applied", runtime.render("auth.sign_out"))
        assertFalse(runtime.applyPending())
    }

    // Failure — contract section 8

    @Test
    fun networkFailuresKeepContent() = blocking {
        for (response in listOf(
            LocalazeResponse.failed("connection refused"),
            LocalazeResponse.failed("operation timed out after 10s"))) {

            val reported = Reported()
            val runtime = runtimeFor(
                "ru",
                config = LocalazeConfig(
                    apiKey = "k",
                    onDiagnostic = reported.callback,
                    transport = FakeTransport(mutableMapOf("/version" to response))))

            val status = runtime.update()

            assertEquals(LocalazeFetchOutcome.FAILED, status.outcome)
            assertEquals("Выход", runtime.render("auth.sign_out"))
            assertFalse(reported.isEmpty)
        }
    }

    @Test
    fun non200IsReportedWithItsStatus() = blocking {
        for (status in listOf(401, 403, 404, 500, 503)) {
            val reported = Reported()
            val transport = FakeTransport(
                mutableMapOf(
                    "/version" to ok(versionBody("1.0.1")),
                    "/translations" to LocalazeResponse(status = status)))
            val runtime = runtimeFor(
                "ru",
                config = LocalazeConfig(
                    apiKey = "k", onDiagnostic = reported.callback, transport = transport))

            val result = runtime.update()

            assertEquals(LocalazeFetchOutcome.FAILED, result.outcome, "$status")
            // A 401 is a wrong key and must be distinguishable from a quiet
            // network by whoever reads the log.
            assertTrue(reported.mentions("$status"), "${reported.messages}")
            assertEquals("Выход", runtime.render("auth.sign_out"))
        }
    }

    @Test
    fun malformedPayloadsKeepContent() = blocking {
        for (path in listOf(
            "updates/malformed-syntax-ru.txt", "updates/malformed-shape-ru.json")) {

            val transport = FakeTransport(
                mutableMapOf(
                    "/version" to ok(versionBody("1.0.4")),
                    "/translations" to ok(fixture(path))))
            val runtime = runtimeFor(
                "ru", config = LocalazeConfig(apiKey = "k", transport = transport))

            assertEquals(LocalazeFetchOutcome.FAILED, runtime.update().outcome, path)
            assertEquals("Выход", runtime.render("auth.sign_out"))
        }
    }

    @Test
    fun corruptCacheIgnored() = blocking {
        val reported = Reported()
        val runtime = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k",
                onDiagnostic = reported.callback,
                storage = FakeStorage(cache = "{\"messages\": trunc")))

        runtime.load()

        assertTrue(reported.mentions("unreadable cached ru catalog"))
        assertEquals("Выход", runtime.render("auth.sign_out"))
    }

    @Test
    fun storageThatThrowsIsCaught() = blocking {
        // load() is the first thing an app calls, so letting this propagate
        // would break the no-throw rule at the worst moment.
        val reported = Reported()
        val storage = FakeStorage().apply { throwOnRead = IllegalStateException("disk gone") }
        val runtime = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k", onDiagnostic = reported.callback, storage = storage))

        runtime.load()

        assertTrue(reported.mentions("Could not read"))
        assertEquals("Выход", runtime.render("auth.sign_out"))
    }

    @Test
    fun missingAssetFallsBackToCompiledIn() = blocking {
        // This is why a path typo is stale text and never a blank screen.
        val runtime = runtimeFor(
            "ru", config = LocalazeConfig(apiKey = "k", storage = FakeStorage()))
        runtime.load()

        assertEquals("Выход", runtime.render("auth.sign_out"))
        assertEquals(
            "У вас 2 сообщения", runtime.render("inbox.unread", mapOf("count" to 2), 2.0))
    }

    @Test
    fun unwritableCacheAppliesForTheSession() = blocking {
        val reported = Reported()
        val transport = FakeTransport(
            mutableMapOf(
                "/version" to ok(versionBody("1.0.1")),
                "/translations" to ok(fixture("updates/patch-1.0.1-ru.json"))))
        val runtime = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k", applyImmediately = true,
                onDiagnostic = reported.callback, transport = transport,
                storage = FakeStorage(writable = false)))

        val status = runtime.update()

        // Applied, and reported as not persisted. Not an error.
        assertEquals(LocalazeFetchOutcome.APPLIED, status.outcome)
        assertTrue(status.detail!!.contains("session only"))
        assertEquals("Выйти", runtime.render("auth.sign_out"))
        assertTrue(reported.mentions("Could not cache"))
    }

    @Test
    fun throwingDiagnosticIsCaught() = blocking {
        // A broken diagnostic must not take the app with it.
        var called = 0
        val runtime = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k",
                onDiagnostic = { _, _ ->
                    called++
                    throw IllegalStateException("the app's logger is broken")
                },
                transport = FakeTransport(
                    mutableMapOf("/version" to LocalazeResponse.failed("nope")))))

        runtime.update()

        assertTrue(called > 0)
        assertEquals("Выход", runtime.render("auth.sign_out"))
    }

    @Test
    fun transportThatThrowsIsCaught() = blocking {
        val reported = Reported()
        val transport = FakeTransport().apply { throwOnGet = IllegalStateException("bad") }
        val runtime = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k", onDiagnostic = reported.callback, transport = transport))

        runtime.update()

        assertTrue(reported.mentions("threw"))
    }

    @Test
    fun noTransportConfiguredIsReported() = blocking {
        val reported = Reported()
        val runtime = runtimeFor(
            "ru", config = LocalazeConfig(apiKey = "k", onDiagnostic = reported.callback))

        assertEquals(LocalazeFetchOutcome.FAILED, runtime.update().outcome)
        assertTrue(reported.mentions("No transport configured"))
    }

    @Test
    fun unreadableCompiledInCatalogStillRuns() {
        val reported = Reported()
        val runtime = runtimeFor(
            "ru",
            config = LocalazeConfig(apiKey = "k", onDiagnostic = reported.callback),
            compiledIn = "not json at all")

        // A generator bug, not a runtime condition.
        assertEquals("auth.sign_out", runtime.render("auth.sign_out"))
        assertTrue(reported.mentions("compiled-in ru catalog"))
    }

    @Test
    fun lastStatusIsReadable() = blocking {
        val runtime = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k",
                transport = FakeTransport(
                    mutableMapOf("/version" to LocalazeResponse.failed("down")))))

        assertEquals(LocalazeFetchOutcome.NONE, runtime.lastFetch.outcome)
        runtime.update()
        assertEquals(LocalazeFetchOutcome.FAILED, runtime.lastFetch.outcome)
        assertTrue(runtime.lastFetch.detail != null)
    }

    @Test
    fun everyFailureAtOnce() = blocking {
        // Dead network, unreadable cache, no asset, unwritable storage,
        // throwing diagnostic.
        val runtime = runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k",
                onDiagnostic = { _, _ -> throw IllegalStateException("logger down") },
                transport = FakeTransport().apply { throwOnGet = RuntimeException("boom") },
                storage = FakeStorage(cache = "corrupt{", writable = false)))

        runtime.load()
        runtime.update()

        assertFalse(runtime.applyPending())
        assertEquals("Выход", runtime.render("auth.sign_out"))
        assertEquals("1.0.0", runtime.version)
        assertEquals(LocalazeFetchOutcome.FAILED, runtime.lastFetch.outcome)
    }

    // Config — contract section 9

    @Test
    fun keyOnlyWorks() {
        val config = LocalazeConfig(apiKey = "k")

        assertEquals("https://localize.sharpy.dev", config.baseUrl)
        assertEquals(LocalazeUpdatePolicy.MINOR_AND_PATCH, config.policy)
        assertEquals(10_000L, config.timeoutMillis)
        assertFalse(config.applyImmediately)
        assertNull(config.onDiagnostic)
        assertNull(config.transport)
        assertNull(config.storage)

        val runtime = runtimeFor("ru", config = config)
        assertEquals("Выход", runtime.render("auth.sign_out"))
        assertEquals("1.0.0", runtime.version)
    }

    @Test
    fun customBaseUrlIsHonoured() = blocking {
        val transport = FakeTransport(mutableMapOf("/version" to ok(versionBody("1.0.1"))))

        runtimeFor(
            "ru",
            config = LocalazeConfig(
                apiKey = "k",
                baseUrl = "https://staging.localaze.internal",
                transport = transport))
            .update()

        assertTrue(transport.urls[0].startsWith("https://staging.localaze.internal"))
    }

    // Purity — contract sections 2, 3 and 10

    @Test
    fun packageIsGenericAndPure() {
        val sources = java.io.File(
            System.getProperty("localaze.sources")
                ?: "${System.getProperty("user.dir")}/src/main/kotlin")

        val files = sources.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "no source found to check")

        for (file in files) {
            // Comments are stripped: these properties are about what the
            // package contains as code. A doc comment explaining why no plural
            // table ships here is the documentation working.
            val code = file.readText()
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .lines()
                .joinToString("\n") { it.substringBefore("//") }

            val name = file.name

            // No MF2 parser, no plural data.
            assertFalse(code.contains(".match \$"), name)
            assertFalse(code.contains(".input {"), name)
            assertFalse(code.lowercase().contains("cldr"), name)

            // No translations, no key names, no project identifier.
            for (leak in listOf(
                "inbox.unread", "auth.welcome", "auth.sign_out", "billing.due",
                "Postbox", "Выход", "mesaj")) {
                assertFalse(code.contains(leak), "$name contains \"$leak\"")
            }

            // No Android, no strings.xml, no Compose. LZ-57 decides the SDK
            // does not use or emit string resources, and the core being pure
            // JVM is what lets this suite run in milliseconds.
            assertFalse(code.contains("android."), name)
            assertFalse(code.contains("strings.xml"), name)
            assertFalse(code.contains("androidx."), name)
            assertFalse(code.contains("okhttp"), name)
        }
    }
}
