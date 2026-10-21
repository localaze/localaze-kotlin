package dev.sharpy.localaze.generator

import dev.sharpy.localaze.Json

/**
 * Everything the generator decides before it writes a line of Kotlin.
 *
 * The checks of `contract/generator-contract.md` §4 all live here, so the
 * emitter receives a plan that is already known to compile. Nothing in this
 * file touches the network or the filesystem: `Main.kt` reads the inputs and
 * writes the outputs, and the tests drive this directly.
 */

/** One parameter of a key, as the manifest declares it. */
data class PlannedParam(
    /** The name the catalog uses. Passed to the runtime unchanged. */
    val name: String,
    /** The lower-camel identifier the accessor declares, before escaping. */
    val identifier: String,
    /** `STRING`, `NUMBER` or `DATETIME`. */
    val type: String,
)

/** One key, ready to become an accessor. */
data class PlannedKey(
    val key: String,
    /** The lower-camel accessor name, before escaping. */
    val accessor: String,
    val params: List<PlannedParam>,
    /** The parameter the message selects on, or null for a simple message. */
    val selector: String?,
    /** The source-language text, for the accessor's documentation comment. */
    val preview: String,
)

/** One language's plural resolver. */
data class PlannedPlural(
    val language: String,
    /** Category to condition, sorted by category name. */
    val categories: List<Pair<String, String>>,
) {
    /** Whether any condition mentions [operand]. */
    fun uses(operand: String): Boolean =
        categories.any { Regex("\\b$operand\\b").containsMatchIn(it.second) }
}

/** A plan the emitter can turn into source without any further checks. */
data class GenerationPlan(
    val environment: String,
    val version: String,
    val contentHash: String,
    val sourceLanguage: String,
    /** The manifest's languages, in its order. */
    val languages: List<String>,
    val keys: List<PlannedKey>,
    val plurals: List<PlannedPlural>,
    /** Language to the catalog exactly as it was read, in [languages] order. */
    val catalogs: Map<String, String>,
)

/** What planning produced: a plan, or every reason there is none. */
data class PlanResult(
    val plan: GenerationPlan?,
    /** Each one fails generation. */
    val problems: List<String>,
    /** Reported, not fatal. Only `--allow-gaps` produces these. */
    val warnings: List<String>,
)

/**
 * Members of the generated type, plus members a base type already has on at
 * least one platform. Reserved on every platform alike, so a key set that
 * generates for Kotlin also generates for Dart and Swift.
 */
val reservedAccessors: Set<String> = setOf(
    "runtime", "forLocale", "supportedLocales", "sourceLocale", "generatedFrom",
    "contentHash", "hashCode", "toString", "equals", "runtimeType", "noSuchMethod",
)

private val types = setOf("STRING", "NUMBER", "DATETIME")
private val operands = setOf("n", "i", "v")
private val identifierPattern = Regex("^[A-Za-z][A-Za-z0-9]*$")
private val ruleCharacters = Regex("^[niv0-9\\s()%=!<>&|]*$")
private val categoryPattern = Regex("^[a-z]+$")
private const val PREVIEW_LENGTH = 80

/** `auth.sign_out` becomes `authSignOut`. The same rule names parameters. */
fun lowerCamel(raw: String): String {
    val out = StringBuilder()
    for (segment in raw.split('.', '_', '-').filter { it.isNotEmpty() }) {
        val head = segment.substring(0, 1)
        out.append(if (out.isEmpty()) head.lowercase() else head.uppercase())
        out.append(segment.substring(1))
    }
    return out.toString()
}

private class ManifestKey(val key: String, val params: List<Pair<String, String>>, val selects: Boolean)

/**
 * Plans generation from a manifest and one catalog per language, each the raw
 * text the server returned or the file a build saved.
 */
fun plan(manifest: String, catalogs: Map<String, String>, allowGaps: Boolean = false): PlanResult {
    val problems = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    val parsed = decode(manifest, "The manifest", problems)
        ?: return PlanResult(null, problems, warnings)

    val version = string(parsed, "version", problems)
    val sourceLanguage = string(parsed, "sourceLanguage", problems)
    val languages = strings(parsed["languages"], "languages", problems)
    val keys = manifestKeys(parsed["keys"], problems)
    val rules = pluralRules(parsed["pluralRules"], problems)
    if (problems.isNotEmpty()) return PlanResult(null, problems, warnings)

    val messages = LinkedHashMap<String, Map<*, *>>()
    for (language in languages) {
        val raw = catalogs[language]
        if (raw == null) {
            problems.add("No catalog for language \"$language\".")
            continue
        }
        val catalog = decode(raw, "The \"$language\" catalog", problems) ?: continue
        if (catalog["version"] != version) {
            problems.add("The \"$language\" catalog is version ${catalog["version"]}, but the manifest is $version.")
        }
        val body = catalog["messages"] as? Map<*, *>
        if (body == null) {
            problems.add("The \"$language\" catalog has no \"messages\".")
            continue
        }
        messages[language] = body
    }

    for (key in keys) {
        for ((language, body) in messages) {
            if (!body.containsKey(key.key)) {
                val gap = "${key.key} is missing in $language."
                if (allowGaps) warnings.add(gap) else problems.add(gap)
            }
        }
    }

    val planned = planKeys(keys, sourceLanguage, languages, messages, problems)
    val plurals = planPlurals(languages, rules, problems)

    if (problems.isNotEmpty()) return PlanResult(null, problems, warnings)

    return PlanResult(
        GenerationPlan(
            environment = parsed["environment"] as? String ?: "unknown",
            version = version,
            contentHash = parsed["contentHash"] as? String ?: "",
            sourceLanguage = sourceLanguage,
            languages = languages,
            keys = planned,
            plurals = plurals,
            catalogs = languages.associateWith { catalogs.getValue(it) },
        ),
        problems,
        warnings,
    )
}

private fun planKeys(
    keys: List<ManifestKey>,
    sourceLanguage: String,
    languages: List<String>,
    messages: Map<String, Map<*, *>>,
    problems: MutableList<String>,
): List<PlannedKey> {
    val planned = mutableListOf<PlannedKey>()
    val owners = HashMap<String, String>()

    for (key in keys) {
        val accessor = lowerCamel(key.key)
        when {
            !identifierPattern.matches(accessor) -> problems.add(
                "Key \"${key.key}\" gives no valid accessor name (\"$accessor\"). " +
                    "Use ASCII letters and digits, starting with a letter.")
            accessor in reservedAccessors -> problems.add(
                "Key \"${key.key}\" would generate \"$accessor\", which is reserved.")
            accessor in owners -> problems.add(
                "Keys \"${owners[accessor]}\" and \"${key.key}\" both generate the accessor \"$accessor\".")
            else -> owners[accessor] = key.key
        }

        val params = mutableListOf<PlannedParam>()
        val seen = HashMap<String, String>()
        for ((name, type) in key.params) {
            val identifier = lowerCamel(name)
            when {
                !identifierPattern.matches(identifier) -> problems.add(
                    "Parameter \"$name\" of \"${key.key}\" gives no valid identifier (\"$identifier\").")
                identifier in seen -> problems.add(
                    "Parameters \"${seen[identifier]}\" and \"$name\" of \"${key.key}\" both generate \"$identifier\".")
                else -> seen[identifier] = name
            }
            params.add(PlannedParam(name, identifier, type))
        }

        val reference = reference(key.key, sourceLanguage, languages, messages)
        val selector = if (key.selects) selector(key, reference, problems) else null

        planned.add(PlannedKey(key.key, accessor, params, selector, preview(reference)))
    }
    return planned
}

/** The source language's message, or the first language that has the key. */
private fun reference(
    key: String,
    sourceLanguage: String,
    languages: List<String>,
    messages: Map<String, Map<*, *>>,
): Map<*, *>? {
    for (language in listOf(sourceLanguage) + languages) {
        val message = messages[language]?.get(key)
        if (message is Map<*, *>) return message
    }
    return null
}

private fun selector(key: ManifestKey, reference: Map<*, *>?, problems: MutableList<String>): String? {
    val name = (reference?.get("selectors") as? List<*>)?.firstOrNull() as? String
    if (name == null) {
        problems.add("\"${key.key}\" selects, but no catalog names its selector.")
        return null
    }
    val declared = key.params.firstOrNull { it.first == name }
    if (declared == null) {
        problems.add("\"${key.key}\" selects on \$$name, which the manifest does not declare.")
        return null
    }
    if (declared.second != "NUMBER") {
        problems.add("\"${key.key}\" selects on \$$name, a ${declared.second}. Only NUMBER selection runs on device.")
        return null
    }
    return name
}

/** The `*` variant as text, variables as `{$name}`, on one line. */
private fun preview(message: Map<*, *>?): String {
    val parts = ((message?.get("variants") as? Map<*, *>)?.get("*") as? List<*>) ?: return ""
    val out = StringBuilder()
    for (part in parts) {
        if (part !is Map<*, *>) continue
        val text = part["text"]
        val variable = part["var"]
        if (text is String) out.append(text) else if (variable is String) out.append("{\$$variable}")
    }
    val line = out.toString().replace(Regex("[\\r\\n\\t]+"), " ")
    if (line.length <= PREVIEW_LENGTH) return line

    // Never cut a surrogate pair in half: a lone surrogate is not text.
    var end = PREVIEW_LENGTH
    if (Character.isHighSurrogate(line[end - 1])) end -= 1
    return line.substring(0, end) + "…"
}

private fun planPlurals(
    languages: List<String>,
    rules: Map<String, List<Pair<String, String>>>,
    problems: MutableList<String>,
): List<PlannedPlural> = languages.map { language ->
    val categories = rules[language] ?: emptyList()
    for ((category, condition) in categories) {
        if (!categoryPattern.matches(category)) {
            problems.add("The \"$language\" plural rules name an unrecognised category \"$category\".")
        }
        val unknown = Regex("[A-Za-z_]+").findAll(condition).map { it.value }
            .filter { it !in operands }.toSortedSet()
        if (unknown.isNotEmpty()) {
            problems.add("The \"$language\" rule for \"$category\" uses ${unknown.joinToString(", ")}. " +
                "Only n, i and v are supported.")
        } else if (!ruleCharacters.matches(condition)) {
            problems.add("The \"$language\" rule for \"$category\" is not a recognised condition: $condition")
        }
    }
    PlannedPlural(language, categories)
}

// ── Parsing ──────────────────────────────────────────────────────────────────

private fun decode(raw: String, what: String, problems: MutableList<String>): Map<*, *>? {
    val decoded = Json.parse(raw)
    if (decoded is Map<*, *>) return decoded
    problems.add(if (decoded == null) "$what is not valid JSON." else "$what is not a JSON object.")
    return null
}

private fun string(json: Map<*, *>, field: String, problems: MutableList<String>): String {
    val value = json[field]
    if (value is String && value.isNotEmpty()) return value
    problems.add("The manifest has no \"$field\".")
    return ""
}

private fun strings(value: Any?, field: String, problems: MutableList<String>): List<String> {
    if (value is List<*> && value.isNotEmpty() && value.all { it is String }) {
        return value.map { it as String }
    }
    problems.add("The manifest has no \"$field\".")
    return emptyList()
}

private fun manifestKeys(value: Any?, problems: MutableList<String>): List<ManifestKey> {
    if (value !is List<*>) {
        problems.add("The manifest has no \"keys\".")
        return emptyList()
    }
    val keys = mutableListOf<ManifestKey>()
    for (entry in value) {
        val key = (entry as? Map<*, *>)?.get("key") as? String
        val rawParams = (entry as? Map<*, *>)?.get("params") as? List<*>
        if (key == null || rawParams == null) {
            problems.add("The manifest has a key entry it cannot read: $entry")
            continue
        }
        val params = mutableListOf<Pair<String, String>>()
        for (param in rawParams) {
            val name = (param as? Map<*, *>)?.get("name") as? String
            val type = (param as? Map<*, *>)?.get("type") as? String
            if (name != null && type != null && type in types) {
                params.add(name to type)
            } else {
                problems.add("\"$key\" declares a parameter it cannot read: $param")
            }
        }
        keys.add(ManifestKey(key, params, (entry as Map<*, *>)["selects"] == true))
    }
    return keys
}

private fun pluralRules(value: Any?, problems: MutableList<String>): Map<String, List<Pair<String, String>>> {
    if (value == null) return emptyMap()
    if (value !is Map<*, *>) {
        problems.add("The manifest's \"pluralRules\" is not an object.")
        return emptyMap()
    }
    val rules = LinkedHashMap<String, List<Pair<String, String>>>()
    for ((language, categories) in value) {
        if (language !is String || categories !is Map<*, *>) {
            problems.add("The manifest has plural rules it cannot read for \"$language\".")
            continue
        }
        // Name order, per generator-contract.md §7, so every platform agrees
        // whether or not its JSON reader keeps an object's order.
        rules[language] = categories.entries
            .mapNotNull { (category, condition) ->
                if (category is String && condition is String) category to condition else null
            }
            .sortedBy { it.first }
    }
    return rules
}
