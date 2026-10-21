package dev.sharpy.localaze.generator

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `localaze-generator.jar` as a build runs it: a real process, real files. */
class CommandTest {

    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("localaze-gen-").toFile()
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private class Result(val status: Int, val stdout: String, val stderr: String)

    private fun generate(vararg arguments: String): Result {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(listOf(java, "-jar", System.getProperty("localaze.generatorJar")) + arguments)
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return Result(process.waitFor(), stdout, stderr)
    }

    private fun outputs(): Array<String> = arrayOf(
        "--package", "dev.sharpy.localaze.golden",
        "--out", File(directory, "src/Localaze.g.kt").path,
        "--assets", File(directory, "assets/localaze").path,
    )

    @Test
    fun writesTheGoldenSourceAndEveryCatalog() {
        val result = generate(
            "--manifest", File(fixtures, "manifest.json").path,
            "--catalogs", File(fixtures, "catalogs").path, *outputs())

        assertEquals(0, result.status, result.stderr)
        assertEquals(golden.readText(), File(directory, "src/Localaze.g.kt").readText())
        for (language in listOf("az", "ru")) {
            assertTrue(File(fixtures, "catalogs/$language.json").readBytes()
                .contentEquals(File(directory, "assets/localaze/$language.json").readBytes()))
        }
    }

    @Test
    fun aFailureExitsOneNamesTheProblemAndWritesNothing() {
        val onlyAz = File(directory, "only-az").apply { mkdirs() }
        File(fixtures, "catalogs/az.json").copyTo(File(onlyAz, "az.json"))

        val result = generate(
            "--manifest", File(fixtures, "manifest.json").path, "--catalogs", onlyAz.path, *outputs())

        assertEquals(1, result.status)
        assertTrue(result.stderr.contains("No catalog for language \"ru\"."))
        assertFalse(File(directory, "src").exists())
        assertFalse(File(directory, "assets").exists())
    }

    @Test
    fun aUsageErrorExits64() {
        val result = generate("--nonsense")
        assertEquals(64, result.status)
        assertTrue(result.stderr.contains("Unknown option \"--nonsense\""))
    }

    @Test
    fun aMissingPackageIsAUsageError() {
        val result = generate("--manifest", "m", "--catalogs", "c")
        assertEquals(64, result.status)
        assertTrue(result.stderr.contains("--package is required"))
    }

    @Test
    fun anUnreachableServerIsAFailureNotACrash() {
        val result = generate("--key", "k", "--base-url", "http://127.0.0.1:9", *outputs())
        assertEquals(1, result.status)
        assertTrue(result.stderr.contains("Could not reach http://127.0.0.1:9"), result.stderr)
    }

    // ── Online, against an in-process server ─────────────────────────────────

    private val requests = mutableListOf<String>()
    private var manifestStatus = 200

    private fun serve(block: (port: Int) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            synchronized(requests) { requests.add(exchange.requestURI.toString()) }
            val (status, body) = when {
                exchange.requestHeaders.getFirst("X-Localaze-Key") != "good-key" ->
                    401 to "{\"detail\":\"A valid key is required.\"}"
                exchange.requestURI.path == "/api/v1/read/manifest" ->
                    if (manifestStatus == 200) 200 to fixture("manifest.json")
                    else manifestStatus to "{\"detail\":\"Release 1.0.0 cannot back generated code.\"," +
                        "\"gaps\":[\"inbox.unread is missing in az\"]}"
                else -> 200 to fixture("catalogs/${exchange.requestURI.path.substringAfterLast('/')}.json")
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(server.address.port)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fetchesTheManifestThenCatalogsPinnedToItsVersion() = serve { port ->
        val result = generate("--key", "good-key", "--base-url", "http://127.0.0.1:$port", *outputs())

        assertEquals(0, result.status, result.stderr)
        assertEquals(golden.readText(), File(directory, "src/Localaze.g.kt").readText())
        assertEquals(listOf(
            "/api/v1/read/manifest?strict=true",
            "/api/v1/read/translations/az?format=resolved&version=1.0.0",
            "/api/v1/read/translations/ru?format=resolved&version=1.0.0",
        ), requests)
    }

    @Test
    fun aWrongKeySaysSo() = serve { port ->
        val result = generate("--key", "wrong-key", "--base-url", "http://127.0.0.1:$port", *outputs())
        assertEquals(1, result.status)
        assertTrue(result.stderr.contains("returned 401"))
        assertTrue(result.stderr.contains("Check the integration key."))
    }

    @Test
    fun aRefusedReleasePrintsEveryGapTheServerNamed() = serve { port ->
        manifestStatus = 422
        val result = generate("--key", "good-key", "--base-url", "http://127.0.0.1:$port", *outputs())
        assertEquals(1, result.status)
        assertTrue(result.stderr.contains("returned 422"))
        assertTrue(result.stderr.contains("inbox.unread is missing in az"))
        assertFalse(File(directory, "src").exists())
    }
}
