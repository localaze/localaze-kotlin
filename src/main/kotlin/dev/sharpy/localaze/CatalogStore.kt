package dev.sharpy.localaze

/**
 * Where content comes from, and in what order.
 *
 * Holds the four levels of content for one language and answers lookups from
 * the highest that has the key.
 *
 * **Per key, not per catalog.** A partial or stale catalog degrades key by key
 * rather than wholly, so one missing key does not cost an app every string it
 * has. That is the only reason the levels are kept separate rather than merged
 * once at load.
 *
 * See `contract/runtime-contract.md` section 7.
 */
internal class CatalogStore(
    private val language: String,
    /**
     * Level 4, the floor. Shipped inside the generated source.
     *
     * It is duplication and it earns its place: without it the first launch of
     * a fresh install with no network has no text at all, and a mistyped asset
     * path fails as a blank screen rather than a stale one.
     */
    private val compiledIn: LocalazeCatalog,
    private val signature: LocalazeSignature,
    private val diagnostics: Diagnostics,
) {
    private var bundled: LocalazeCatalog? = null
    private var cached: LocalazeCatalog? = null
    private var applied: LocalazeCatalog? = null

    /** The version currently rendering, so an app can report it. */
    val version: String
        get() = (applied ?: cached ?: bundled ?: compiledIn).version

    /**
     * Reads the bundled asset and any cached catalog.
     *
     * Both are optional and neither failing is an error. The cached catalog is
     * checked against the signature again here, because an app upgrade can
     * leave yesterday's catalog next to code that now promises something else.
     */
    suspend fun load(storage: LocalazeStorage) {
        read("bundled") { storage.readAsset(language) }?.let { asset ->
            val parsed = LocalazeCatalog.parse(asset)
            if (parsed == null) {
                diagnostics.report("The bundled $language catalog could not be read")
            } else {
                bundled = parsed
            }
        }

        val raw = read("cached") { storage.readCache(language) } ?: return

        val parsed = LocalazeCatalog.parse(raw)
        if (parsed == null) {
            diagnostics.report("Ignoring an unreadable cached $language catalog")
            return
        }

        val problems = signature.incompatibilities(parsed)
        if (problems.isNotEmpty()) {
            diagnostics.report(
                "Dropping the cached $language catalog ${parsed.version}: " +
                    problems.joinToString(", "))
            return
        }
        cached = parsed
    }

    /**
     * A storage implementation is told not to throw. One supplied by an app
     * might anyway, and [load] is the first thing an app calls — letting it
     * propagate would break the no-throw rule at the worst moment.
     */
    private suspend fun read(what: String, read: suspend () -> String?): String? =
        try {
            read()
        } catch (error: Throwable) {
            diagnostics.report(
                "Could not read the $what $language catalog", error.toString())
            null
        }

    /** Takes a catalog that has already passed policy and signature. */
    fun apply(catalog: LocalazeCatalog) {
        applied = catalog
    }

    /** The message for [key], from the highest level that has it. */
    fun lookup(key: String): LocalazeMessage? =
        applied?.messages?.get(key)
            ?: cached?.messages?.get(key)
            ?: bundled?.messages?.get(key)
            ?: compiledIn.messages[key]
}
