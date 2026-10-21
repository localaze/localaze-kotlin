package dev.sharpy.localaze.generator

import dev.sharpy.localaze.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.system.exitProcess

/**
 * `java -jar localaze-generator.jar` — the typed source and catalogs for one
 * published release.
 *
 * See `contract/generator-contract.md`. This file is the only part of the
 * generator that touches the network or the filesystem; the decisions are in
 * `Plan.kt`, where the tests reach them without either.
 */

private val usage = """
Generates typed Localaze translations for one published release.

Usage:
  java -jar localaze-generator.jar --key <integration key> --version <x.y.z> --package <name>
  java -jar localaze-generator.jar --manifest <file> --catalogs <dir> --package <name>

Options:
  --key          Integration key. Carries the project and the environment.
  --version      Release to generate from. A build should pin it.
                 Omitted means the environment's live release.
  --base-url     Default https://localize.sharpy.dev
  --package      Package of the generated file. Required.
  --out          Generated file. Default Localaze.g.kt
  --assets       Catalog directory. Default src/main/assets/localaze,
                 the folder the Android storage reads.
  --allow-gaps   Generate even when a translation is missing.
  --manifest     Read the manifest from a file instead of the server.
  --catalogs     Read <language>.json catalogs from a directory.
  --help         Show this text.

""".trimStart()

fun main(arguments: Array<String>) {
    exitProcess(run(arguments.toList()))
}

private class UsageError(message: String) : Exception(message)

private val flags = setOf("allow-gaps", "help")
private val valued = setOf("key", "version", "base-url", "package", "out", "assets", "manifest", "catalogs")
private val packagePattern = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$")

/** Runs the generator and returns the process exit code. */
fun run(arguments: List<String>): Int {
    val options = try {
        parse(arguments)
    } catch (error: UsageError) {
        System.err.println("error: ${error.message}\n")
        System.err.print(usage)
        return 64
    }

    if ("help" in options) {
        print(usage)
        return 0
    }

    val offline = options["manifest"] != null || options["catalogs"] != null
    if (offline && (options["manifest"] == null || options["catalogs"] == null)) {
        System.err.println("error: --manifest and --catalogs go together.")
        return 64
    }
    if (!offline && options["key"].isNullOrEmpty()) {
        System.err.println("error: --key is required, or --manifest with --catalogs.")
        return 64
    }
    val packageName = options["package"]
    if (packageName == null || !packagePattern.matches(packageName)) {
        System.err.println("error: --package is required, as a Kotlin package name.")
        return 64
    }

    val allowGaps = "allow-gaps" in options
    val inputs = if (offline) {
        readOffline(options.getValue("manifest")!!, options.getValue("catalogs")!!)
    } else {
        fetch(options.getValue("key")!!, options["version"],
            options["base-url"] ?: "https://localize.sharpy.dev", allowGaps)
    }
    val manifest = inputs.manifest
    if (inputs.problems.isNotEmpty() || manifest == null) {
        return fail(inputs.problems.ifEmpty { listOf("No manifest was read.") })
    }

    val result = plan(manifest, inputs.catalogs, allowGaps)
    result.warnings.forEach { System.err.println("warning: $it") }
    val planned = result.plan ?: return fail(result.problems)

    val out = options["out"] ?: "Localaze.g.kt"
    val assets = options["assets"] ?: "src/main/assets/localaze"
    try {
        write(File(out), emitKotlin(planned, packageName))
        for (language in planned.languages) {
            write(File(assets, "$language.json"), planned.catalogs.getValue(language))
        }
    } catch (error: IOException) {
        return fail(listOf("Could not write: ${error.message}"))
    }

    println("Generated $out from ${planned.version} (${planned.environment}): " +
        "${planned.keys.size} keys, ${planned.languages.size} languages. Catalogs in $assets/.")
    return 0
}

private fun fail(problems: List<String>): Int {
    System.err.println("Generation failed:")
    problems.forEach { System.err.println("  - $it") }
    return 1
}

private fun write(file: File, content: String) {
    file.absoluteFile.parentFile?.mkdirs()
    file.writeText(content, Charsets.UTF_8)
}

private fun parse(arguments: List<String>): Map<String, String?> {
    val options = LinkedHashMap<String, String?>()
    var index = 0
    while (index < arguments.size) {
        val argument = arguments[index]
        if (!argument.startsWith("--")) throw UsageError("Unexpected argument \"$argument\".")
        val equals = argument.indexOf('=')
        val name = if (equals < 0) argument.substring(2) else argument.substring(2, equals)
        when (name) {
            in flags -> options[name] = null
            in valued -> options[name] = when {
                equals >= 0 -> argument.substring(equals + 1)
                index + 1 < arguments.size -> arguments[++index]
                else -> throw UsageError("--$name needs a value.")
            }
            else -> throw UsageError("Unknown option \"--$name\".")
        }
        index++
    }
    return options
}

private class Inputs(
    val manifest: String?,
    val catalogs: Map<String, String> = emptyMap(),
    val problems: List<String> = emptyList(),
)

private fun readOffline(manifestPath: String, catalogsPath: String): Inputs {
    val manifestFile = File(manifestPath)
    if (!manifestFile.isFile) return Inputs(null, problems = listOf("No manifest at $manifestPath."))
    val directory = File(catalogsPath)
    if (!directory.isDirectory) {
        return Inputs(manifestFile.readText(), problems = listOf("No catalog directory at $catalogsPath."))
    }
    // Every catalog present. A missing language is the plan's to report,
    // alongside every other problem.
    val catalogs = directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }
        .orEmpty()
        .associate { it.name.removeSuffix(".json") to it.readText(Charsets.UTF_8) }
    return Inputs(manifestFile.readText(Charsets.UTF_8), catalogs)
}

private class Response(val body: String?, val problems: List<String>)

private fun fetch(key: String, version: String?, baseUrl: String, allowGaps: Boolean): Inputs {
    val base = baseUrl.trimEnd('/')
    return try {
        val query = buildList {
            if (!version.isNullOrEmpty()) add("version" to version)
            add("strict" to if (allowGaps) "false" else "true")
        }
        val manifest = get(endpoint(base, "/api/v1/read/manifest", query), key)
        val body = manifest.body ?: return Inputs(null, problems = manifest.problems)

        val parsed = Json.parse(body) as? Map<*, *>
        val pinned = parsed?.get("version") as? String
        val languages = (parsed?.get("languages") as? List<*>)?.filterIsInstance<String>()
        if (pinned == null || languages == null) return Inputs(body)

        // Pinned to the version the manifest returned, even when the caller
        // did not pin: a release published between the two requests would
        // otherwise pair one version's manifest with another's catalogs.
        val catalogs = LinkedHashMap<String, String>()
        val problems = mutableListOf<String>()
        for (language in languages) {
            val path = "/api/v1/read/translations/${URLEncoder.encode(language, "UTF-8")}"
            val catalog = get(endpoint(base, path, listOf("format" to "resolved", "version" to pinned)), key)
            problems += catalog.problems
            catalog.body?.let { catalogs[language] = it }
        }
        Inputs(body, catalogs, problems)
    } catch (error: Exception) {
        Inputs(null, problems = listOf("Could not reach $base: ${error.message ?: error.javaClass.simpleName}"))
    }
}

private fun endpoint(base: String, path: String, query: List<Pair<String, String>>): URL =
    URL(base + path + "?" + query.joinToString("&") { (name, value) ->
        "$name=${URLEncoder.encode(value, "UTF-8")}"
    })

private fun get(url: URL, key: String): Response {
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("X-Localaze-Key", key)
        connection.setRequestProperty("Accept", "application/json")
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return if (status == 200) Response(body, emptyList()) else Response(null, problemsOf(url, status, body))
    } finally {
        connection.disconnect()
    }
}

/** The server's RFC 9457 problem, read for its detail and any gaps. */
private fun problemsOf(url: URL, status: Int, body: String): List<String> {
    val problem = Json.parse(body) as? Map<*, *>
    val detail = (problem?.get("detail") as? String)?.let { ": $it" } ?: "."
    val gaps = (problem?.get("gaps") as? List<*>)?.map { "$it" }.orEmpty()
    val hint = if (status == 401 || status == 403) " Check the integration key." else ""
    return listOf("${url.path} returned $status$detail$hint") + gaps
}
