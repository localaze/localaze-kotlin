package dev.sharpy.localaze

/**
 * The resolved message shape, and parsing it.
 *
 * Catalogs arrive already parsed by the server, as variants and typed parameter
 * references. Nothing here parses MessageFormat 2 and no CLDR data ships with
 * the package.
 *
 * See `../../contract/runtime-contract.md` section 2.
 */

/**
 * The three types a parameter can have. Deliberately small; the generators map
 * these onto native types and the set is not widened per platform.
 */
enum class LocalazeParamType(val wire: String) {
    STRING("STRING"),
    NUMBER("NUMBER"),
    DATETIME("DATETIME");

    /** Lower case, matching the Dart and Swift diagnostics exactly. */
    val label: String get() = name.lowercase()

    companion object {
        /**
         * An unknown type reads as a string rather than failing, so a newer
         * server cannot break an older SDK.
         */
        fun parse(wire: Any?): LocalazeParamType =
            entries.firstOrNull { it.wire == wire } ?: STRING
    }
}

/** A parameter, with the type its MF2 function annotation implied. */
data class LocalazeParam(val name: String, val type: LocalazeParamType)

/**
 * One piece of a message: literal text, or a reference to a parameter.
 *
 * A part is one or the other. The server omits null fields, so a part with
 * neither is malformed and is dropped at parse time.
 */
sealed interface LocalazePart {
    data class Text(val value: String) : LocalazePart
    data class Variable(val name: String, val type: LocalazeParamType) : LocalazePart
}

/** A message resolved into parts and variants. */
data class LocalazeMessage(
    /** Parameters the message selects on. Empty for a simple message. */
    val selectors: List<String>,
    /** Every parameter, with its type. */
    val params: List<LocalazeParam>,
    /** Variant category to its ordered parts. A simple message has only `*`. */
    val variants: Map<String, List<LocalazePart>>,
) {
    val selects: Boolean get() = selectors.isNotEmpty()

    /**
     * The parts for [category], falling back to the catch-all.
     *
     * `LZ-07` guarantees `*` is present on every message, which is what makes
     * this fallback safe rather than optimistic.
     */
    fun partsFor(category: String?): List<LocalazePart>? {
        if (category != null) variants[category]?.let { return it }
        return variants["*"]
    }

    internal companion object {
        fun parse(raw: Any?): LocalazeMessage? {
            val obj = raw as? Map<*, *> ?: return null
            val rawVariants = obj["variants"] as? Map<*, *> ?: return null

            val variants = LinkedHashMap<String, List<LocalazePart>>()
            for ((category, rawParts) in rawVariants) {
                val list = rawParts as? List<*> ?: continue
                val parts = ArrayList<LocalazePart>()

                for (rawPart in list) {
                    val part = rawPart as? Map<*, *> ?: continue
                    val text = part["text"] as? String
                    val variable = part["var"] as? String

                    when {
                        text != null -> parts.add(LocalazePart.Text(text))
                        variable != null -> parts.add(
                            LocalazePart.Variable(
                                variable, LocalazeParamType.parse(part["type"])))
                        // A part with neither is dropped here rather than
                        // carried and skipped later.
                    }
                }
                variants["$category"] = parts
            }
            if (variants.isEmpty()) return null

            return LocalazeMessage(
                selectors = (obj["selectors"] as? List<*>)?.filterIsInstance<String>()
                    ?: emptyList(),
                params = parseParams(obj["params"]),
                variants = variants,
            )
        }

        private fun parseParams(raw: Any?): List<LocalazeParam> {
            val list = raw as? List<*> ?: return emptyList()
            return list.mapNotNull { entry ->
                val obj = entry as? Map<*, *> ?: return@mapNotNull null
                val name = obj["name"] as? String ?: return@mapNotNull null
                LocalazeParam(name, LocalazeParamType.parse(obj["type"]))
            }
        }
    }
}

/**
 * One language's messages, and the release they came from.
 *
 * A catalog file and a read-API response body have the same shape, so a bundled
 * asset and a fetched payload go through this one parser. Inventing a second
 * envelope for bundled files would mean two code paths where one is tested and
 * the other is not.
 */
data class LocalazeCatalog(
    val version: String,
    val messages: Map<String, LocalazeMessage>,
) {
    val isEmpty: Boolean get() = messages.isEmpty()

    companion object {
        val EMPTY = LocalazeCatalog("0.0.0", emptyMap())

        /**
         * Parses a catalog, returning null rather than throwing.
         *
         * Malformed JSON, a missing `messages` object, and a body that is not
         * an object at all all return null. The caller keeps what it had.
         */
        fun parse(body: String, fallbackVersion: String? = null): LocalazeCatalog? {
            val decoded = Json.parse(body) as? Map<*, *> ?: return null
            val rawMessages = decoded["messages"] as? Map<*, *> ?: return null

            val messages = LinkedHashMap<String, LocalazeMessage>()
            for ((key, raw) in rawMessages) {
                LocalazeMessage.parse(raw)?.let { messages["$key"] = it }
            }

            return LocalazeCatalog(
                version = decoded["version"] as? String ?: fallbackVersion ?: "0.0.0",
                messages = messages,
            )
        }

        /**
         * One top-level string field, or null for anything unreadable.
         *
         * The version endpoint returns no `messages`, so [parse] correctly
         * rejects it and this reads the one field that matters instead.
         */
        internal fun stringField(body: String, field: String): String? =
            (Json.parse(body) as? Map<*, *>)?.get(field) as? String
    }
}
