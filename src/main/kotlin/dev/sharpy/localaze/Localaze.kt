package dev.sharpy.localaze

/**
 * What generated code sits on.
 *
 * A generated `Localaze` class holds one of these and calls [render] from each
 * typed accessor. The type safety lives in the generated signatures; this
 * supplies the content and the update machinery behind them.
 *
 * Nothing in this package throws. Every failure leaves the app on the content
 * it already had and is reported through [LocalazeConfig.onDiagnostic].
 */

/**
 * Resolves a numeric value to a CLDR plural category for one locale.
 *
 * Generated at build time from the project's locales, so the device resolves
 * categories without shipping plural data and without an MF2 parser. Returning
 * null means "no category", and the catch-all is used.
 */
typealias LocalazePluralResolver = (Double) -> String?

class LocalazeRuntime(
    val locale: String,
    val config: LocalazeConfig,
    val signature: LocalazeSignature,
    compiledIn: String,
    private val pluralResolver: LocalazePluralResolver? = null,
) {
    private val diagnostics = Diagnostics(config.onDiagnostic)
    private val storage = config.storage ?: LocalazeNoStorage
    private val store: CatalogStore
    private val updater: Updater

    /** Held back until next launch when [LocalazeConfig.applyImmediately] is off. */
    private var pending: LocalazeCatalog? = null

    /**
     * What happened on the last fetch. Readable so an app can report it rather
     * than guess, which matters when nothing throws.
     */
    var lastFetch: LocalazeFetchStatus = LocalazeFetchStatus.IDLE
        private set

    init {
        val parsed = LocalazeCatalog.parse(compiledIn)
        if (parsed == null) {
            // A compiled-in copy that will not parse is a generator bug, not a
            // runtime condition. Failing soft here keeps the no-throw rule
            // intact and leaves the app rendering key names, which is loud
            // enough to notice.
            diagnostics.report("The compiled-in $locale catalog could not be read")
        }

        store = CatalogStore(
            language = locale,
            compiledIn = parsed ?: LocalazeCatalog.EMPTY,
            signature = signature,
            diagnostics = diagnostics)

        updater = Updater(
            config = config,
            signature = signature,
            diagnostics = diagnostics,
            transport = config.transport,
            storage = storage)
    }

    /** The version currently rendering. */
    val version: String get() = store.version

    /**
     * Reads the bundled asset and any cached catalog.
     *
     * Optional — everything renders from the compiled-in copy without it — but
     * an app that skips it gets no cached updates.
     */
    suspend fun load() {
        store.load(storage)
    }

    /**
     * Fetches, checks and applies a newer catalog if the policy allows one.
     *
     * Never throws, and never blocks a screen: the app renders from what it has
     * throughout.
     */
    suspend fun update(pinnedVersion: String? = null): LocalazeFetchStatus {
        val (catalog, status) = updater.fetchIfAccepted(
            locale, store.version, pinnedVersion)
        lastFetch = status

        if (catalog == null) return status

        if (config.applyImmediately) {
            store.apply(catalog)
        } else {
            // Text changing under a user mid-session is worse than text being
            // one launch out of date. It is cached either way.
            pending = catalog
        }
        return status
    }

    /**
     * Applies a catalog held back by [LocalazeConfig.applyImmediately].
     *
     * An app with a natural seam — a navigation event, a background return —
     * can call this rather than waiting for a relaunch.
     */
    fun applyPending(): Boolean {
        val catalog = pending ?: return false
        store.apply(catalog)
        pending = null
        return true
    }

    /**
     * Renders [key]. Called by generated accessors, which supply typed
     * arguments; the loose map here is an internal boundary, not public API.
     *
     * Returns the key itself when it is not found anywhere. That is a generator
     * bug rather than a runtime condition — generation fails on a missing
     * translation — so it is reported rather than rendered blank.
     */
    fun render(
        key: String,
        args: Map<String, Any?> = emptyMap(),
        selector: Double? = null,
    ): String {
        val message = store.lookup(key)
        if (message == null) {
            diagnostics.report("No message for \"$key\" in $locale")
            return key
        }

        val category =
            if (message.selects && selector != null) pluralResolver?.invoke(selector)
            else null

        val parts = message.partsFor(category)
        if (parts == null) {
            diagnostics.report("\"$key\" has no variants in $locale")
            return key
        }

        return localazeRender(parts, args, config.dateFormatter)
    }
}
