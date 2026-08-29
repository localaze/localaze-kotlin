package dev.sharpy.localaze

/**
 * Fetching, checking and applying catalogs.
 *
 * Nothing here throws. Every failure path leaves the app on the catalog it
 * already had, because a translation outage must never become a crash.
 *
 * See `../../contract/runtime-contract.md` sections 5, 6 and 8.
 */
internal class Updater(
    private val config: LocalazeConfig,
    private val signature: LocalazeSignature,
    private val diagnostics: Diagnostics,
    private val transport: LocalazeTransport?,
    private val storage: LocalazeStorage,
) {
    /**
     * Held per language, so an unchanged catalog costs a round trip and nothing
     * else. The server's ETag is version-scoped, so a pinned version's tag
     * never changes and a rollback returns the earlier tag.
     */
    private val etags = HashMap<String, String>()

    /**
     * What the key currently serves, or null if it cannot be read.
     *
     * Cheap enough for every launch: it does not download the translations.
     */
    suspend fun currentVersion(): String? {
        val path = "/api/v1/read/version"
        val response = get(path) ?: return null

        if (!response.isOk) {
            // The status has to reach the diagnostic here, not only on the
            // translations path. A 401 is a wrong key, and without this it is
            // indistinguishable from a quiet network to whoever reads the log —
            // which is the exact failure the no-throw rule trades away.
            diagnostics.report("$path returned ${response.status}")
            return null
        }

        val version = LocalazeCatalog.stringField(response.body!!, "version")
        if (version == null) {
            diagnostics.report("$path carried no version")
        }
        return version
    }

    /**
     * Downloads [language] if the server has something this build accepts.
     *
     * The order is policy, version, download, signature, persist. Policy comes
     * first because it is the cheapest way to answer the question, and the
     * signature is checked before anything is written, because a refused
     * catalog must leave no trace.
     */
    suspend fun fetchIfAccepted(
        language: String,
        held: String,
        pinnedVersion: String? = null,
    ): Pair<LocalazeCatalog?, LocalazeFetchStatus> {
        if (!config.policy.fetches) {
            return null to LocalazeFetchStatus(
                LocalazeFetchOutcome.REFUSED_BY_POLICY,
                detail = "policy none makes no request")
        }

        // A pin answers the version question, so the endpoint is not called.
        val available = pinnedVersion ?: currentVersion()
        if (available == null) {
            return null to failed("The published version could not be read")
        }

        // Nothing new is not a refusal. Reporting it as one reads, in a log and
        // in a UI, as though the policy blocked something — when in fact the
        // server and the app already agree.
        if (available == held) {
            return null to LocalazeFetchStatus(
                LocalazeFetchOutcome.UP_TO_DATE, "already on $held", held)
        }

        if (!LocalazeVersion.accepts(held, available, config.policy)) {
            val detail = "Version $available refused by policy " +
                "${config.policy.label}, staying on $held"
            diagnostics.report(detail)
            return null to LocalazeFetchStatus(
                LocalazeFetchOutcome.REFUSED_BY_POLICY, detail, available)
        }

        val path = buildString {
            append("/api/v1/read/translations/$language?format=resolved")
            if (pinnedVersion != null) append("&version=$pinnedVersion")
        }

        val response = get(path, etags[language])
            ?: return null to failed("The $language catalog could not be fetched")

        if (response.isNotModified) {
            return null to LocalazeFetchStatus(
                LocalazeFetchOutcome.UP_TO_DATE, "not modified", held)
        }

        if (!response.isOk) {
            // The status goes in the message on purpose: a 401 is a wrong key
            // and must be distinguishable from a quiet network by whoever
            // reads the log.
            return null to failed("$path returned ${response.status}")
        }

        val catalog = LocalazeCatalog.parse(response.body!!, fallbackVersion = available)
        if (catalog == null || catalog.isEmpty) {
            return null to failed("The $language catalog had no readable messages")
        }

        // Belt and braces. LZ-62 guarantees a minor or patch release is
        // signature-compatible, so a failure here is a platform bug rather than
        // an expected refusal — which is why it is its own outcome.
        val problems = signature.incompatibilities(catalog)
        if (problems.isNotEmpty()) {
            val detail = "Catalog ${catalog.version} is not compatible: " +
                problems.joinToString(", ")
            diagnostics.report(detail)
            return null to LocalazeFetchStatus(
                LocalazeFetchOutcome.REFUSED_BY_SIGNATURE, detail, catalog.version)
        }

        response.etag?.let { etags[language] = it }

        val persisted = persist(language, response.body)

        return catalog to LocalazeFetchStatus(
            LocalazeFetchOutcome.APPLIED,
            detail = if (persisted) null else "applied for this session only, not cached",
            version = catalog.version)
    }

    private fun failed(detail: String): LocalazeFetchStatus {
        diagnostics.report(detail)
        return LocalazeFetchStatus(LocalazeFetchOutcome.FAILED, detail)
    }

    private suspend fun persist(language: String, body: String): Boolean =
        try {
            val written = storage.writeCache(language, body)
            if (!written) {
                diagnostics.report(
                    "Could not cache the $language catalog; " +
                        "it applies for this session only")
            }
            written
        } catch (error: Throwable) {
            diagnostics.report(
                "Could not cache the $language catalog", error.toString())
            false
        }

    private suspend fun get(path: String, etag: String? = null): LocalazeResponse? {
        val transport = transport
        if (transport == null) {
            diagnostics.report(
                "No transport configured; supply one from the Android artifact")
            return null
        }

        val headers = buildMap {
            put("X-Localaze-Key", config.apiKey)
            put("Accept", "application/json")
            if (etag != null) put("If-None-Match", etag)
        }

        return try {
            val response = transport.get(config.baseUrl + path, headers)

            when {
                response.error != null -> {
                    diagnostics.report("$path failed", response.error)
                    null
                }
                response.isOk && response.body == null -> {
                    diagnostics.report("$path returned 200 with no body")
                    null
                }
                else -> response
            }
        } catch (error: Throwable) {
            // A transport is not supposed to throw. One supplied by an app
            // might.
            diagnostics.report("$path threw", error.toString())
            null
        }
    }
}
