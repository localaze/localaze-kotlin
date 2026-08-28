package dev.sharpy.localaze

/**
 * The network and persistence seams, and diagnostics.
 *
 * Interfaces rather than direct OkHttp and `Context` calls, for two reasons
 * that are both about this package being usable and testable: keeping `Context`
 * out of the core is what lets it be a pure Kotlin/JVM module, and every
 * failure row in the contract is reachable with a fake and unreachable without
 * one.
 *
 * See `../../contract/runtime-contract.md` sections 8 and 10.
 */

/**
 * What came back. Never an exception — an implementation that cannot reach the
 * network returns [failed] instead.
 */
data class LocalazeResponse(
    val status: Int,
    val body: String? = null,
    val etag: String? = null,
    val error: String? = null,
) {
    val isOk: Boolean get() = status == 200

    /**
     * The server had nothing newer. A success, not a failure — this is the
     * cheap path the version-scoped ETag exists to serve.
     */
    val isNotModified: Boolean get() = status == 304

    companion object {
        /**
         * A request that never completed. Status 0 is not a real HTTP status,
         * which is the point: it cannot collide with one the server sends.
         */
        fun failed(error: String): LocalazeResponse =
            LocalazeResponse(status = 0, error = error)
    }
}

/** One GET, with headers. Implementations must not throw. */
fun interface LocalazeTransport {
    suspend fun get(url: String, headers: Map<String, String>): LocalazeResponse
}

/**
 * Implementations must not throw. Anything unreadable is null.
 *
 * Two separate concerns behind one interface: reading the bundled asset that
 * shipped with the app, and reading or writing the cache that survives a
 * launch. They fail differently and are configured differently, so they are
 * separate methods rather than one keyed store.
 */
interface LocalazeStorage {
    /**
     * The catalog that shipped with the app, for [language].
     *
     * Null when there is no asset, or it cannot be read. A missing asset is not
     * an error: the compiled-in copy is behind it, which is what lets the asset
     * path be configurable without a typo becoming a blank screen.
     */
    suspend fun readAsset(language: String): String?

    /** A catalog written by an earlier run. */
    suspend fun readCache(language: String): String?

    /**
     * Persists [body]. Returns false when it could not be written.
     *
     * False is not a failure worth stopping for — the update still applies for
     * this session. It is reported so an app can tell "working, not persisted"
     * from "working".
     */
    suspend fun writeCache(language: String, body: String): Boolean
}

/** Storage that has nothing and keeps nothing. */
object LocalazeNoStorage : LocalazeStorage {
    override suspend fun readAsset(language: String): String? = null
    override suspend fun readCache(language: String): String? = null
    override suspend fun writeCache(language: String, body: String): Boolean = false
}

/**
 * Called for every failure the package declines to throw.
 *
 * Nothing here throws, which is a deliberate choice and a dangerous one: a
 * wrong API key and a quiet network look identical from the outside, so an
 * integration that never worked can go unnoticed for a month. This callback is
 * not optional polish, it is how anyone finds out.
 */
typealias LocalazeDiagnostic = (message: String, error: String?) -> Unit

/** Wraps the app's callback so a broken one cannot take the app with it. */
internal class Diagnostics(private val callback: LocalazeDiagnostic?) {
    fun report(message: String, error: String? = null) {
        val callback = callback ?: return
        try {
            callback(message, error)
        } catch (_: Throwable) {
            // An app whose diagnostic handler throws still gets its
            // translations. Reporting this would mean calling the thing that
            // just failed.
        }
    }
}

/** What happened on the last fetch, so an app can report it rather than guess. */
enum class LocalazeFetchOutcome {
    /** No fetch has been attempted. */
    NONE,

    /** A newer catalog was downloaded, checked and applied. */
    APPLIED,

    /** The server had nothing newer, or returned 304. */
    UP_TO_DATE,

    /** The update policy declined it. */
    REFUSED_BY_POLICY,

    /**
     * The signature check declined it. On a minor or patch release this is a
     * platform bug — `LZ-62` guarantees those are signature-compatible.
     */
    REFUSED_BY_SIGNATURE,

    /** The network, the server, or the payload failed. */
    FAILED,
}

/** The outcome of the last fetch, and why. */
data class LocalazeFetchStatus(
    val outcome: LocalazeFetchOutcome,
    /** Human-readable, and the same text passed to the diagnostic callback. */
    val detail: String? = null,
    /** The version involved, when there was one. */
    val version: String? = null,
) {
    val isApplied: Boolean get() = outcome == LocalazeFetchOutcome.APPLIED

    companion object {
        val IDLE = LocalazeFetchStatus(LocalazeFetchOutcome.NONE)
    }
}

/**
 * Everything an app can change, all of it optional but the key.
 *
 * See `../../contract/runtime-contract.md` section 9.
 */
data class LocalazeConfig(
    /**
     * Issued in project settings. Carries the project and the environment, so
     * neither is a parameter here and a staging build cannot read production.
     */
    val apiKey: String,
    val baseUrl: String = "https://localize.sharpy.dev",
    val policy: LocalazeUpdatePolicy = LocalazeUpdatePolicy.MINOR_AND_PATCH,
    val timeoutMillis: Long = 10_000,
    /**
     * Whether an applied catalog takes effect now or on next launch.
     *
     * Off by default: text changing under a user mid-session is worse than text
     * being one launch out of date.
     */
    val applyImmediately: Boolean = false,
    val dateFormatter: LocalazeDateFormatter = localazeIsoUtc,
    val onDiagnostic: LocalazeDiagnostic? = null,
    /** Defaults to no network. The Android artifact supplies the real one. */
    val transport: LocalazeTransport? = null,
    /** Defaults to [LocalazeNoStorage], so updates last for the session only. */
    val storage: LocalazeStorage? = null,
)
