package dev.sharpy.localaze.android

import android.content.Context
import dev.sharpy.localaze.LocalazeResponse
import dev.sharpy.localaze.LocalazeStorage
import dev.sharpy.localaze.LocalazeTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Android side of the SDK: the two seams the pure-Kotlin core leaves open.
 *
 * Everything that decides *what* happens — policy, the signature check,
 * rendering, resolution order — is in `localaze-kotlin` and has no Android
 * dependency, which is what lets its 52-case conformance suite run on the JVM
 * in milliseconds. This module only supplies *how* bytes move, and it is
 * deliberately small enough to read in one sitting.
 *
 * Nothing here throws, in keeping with the rest of the SDK.
 */

/**
 * A transport over `HttpURLConnection`.
 *
 * `HttpURLConnection` rather than OkHttp on purpose: OkHttp would be a
 * dependency every consuming app carries and keeps in step, and an app that
 * already has it can supply its own transport in three lines. This is the
 * floor, not the recommendation.
 */
class AndroidTransport(
    private val timeoutMillis: Int = 10_000,
) : LocalazeTransport {

    /**
     * Returns a failed response rather than throwing, whatever goes wrong.
     *
     * A caller that has to wrap every call in a try/catch has an interface that
     * lied to it, and this one promises not to throw.
     */
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): LocalazeResponse = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }

            val status = connection.responseCode
            val etag = connection.getHeaderField("ETag")

            // 304 carries no body, and reading one would block until the
            // timeout rather than returning the cheap answer the ETag bought.
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return@withContext LocalazeResponse(status = status, etag = etag)
            }

            // utf8 explicitly: catalogs carry Cyrillic and Azerbaijani, and the
            // platform default is not guaranteed to be either.
            val stream =
                if (status in 200..299) connection.inputStream else connection.errorStream

            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }

            LocalazeResponse(status = status, body = body, etag = etag)
        } catch (error: Throwable) {
            LocalazeResponse.failed(error.toString())
        } finally {
            connection?.disconnect()
        }
    }
}

/**
 * Catalogs from `assets/` and a cache under `filesDir`.
 *
 * Unlike the Dart package, persistence is on by default: Android can find a
 * writable directory without an extra dependency. Behaviour given the same
 * configuration is identical across platforms; only the default differs.
 *
 * Note what this does **not** do: it never reads `strings.xml`. `LZ-57` decides
 * the SDK bypasses Android string resources, because routing through them would
 * make Android the one platform whose plural handling is the system's rather
 * than the generated rules', and resources cannot be replaced over the air.
 */
class AndroidStorage(
    context: Context,
    private val assetDirectory: String = "localaze",
    cacheDirectory: File? = null,
) : LocalazeStorage {

    // The application context, not the one passed in: an Activity held here
    // would leak for as long as the SDK lives.
    private val assets = context.applicationContext.assets

    private val cache: File =
        cacheDirectory ?: File(context.applicationContext.filesDir, "localaze")

    override suspend fun readAsset(language: String): String? =
        withContext(Dispatchers.IO) {
            try {
                assets.open("$assetDirectory/$language.json")
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            } catch (_: Throwable) {
                // A missing asset is expected rather than exceptional — the
                // compiled-in copy sits behind it, which is what makes the
                // asset path safe to configure.
                null
            }
        }

    override suspend fun readCache(language: String): String? =
        withContext(Dispatchers.IO) {
            try {
                File(cache, "$language.json").takeIf { it.exists() }
                    ?.readText(Charsets.UTF_8)
            } catch (_: Throwable) {
                null
            }
        }

    override suspend fun writeCache(language: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!cache.exists()) cache.mkdirs()
                File(cache, "$language.json").writeText(body, Charsets.UTF_8)
                true
            } catch (_: Throwable) {
                // Reported by the updater, which knows whether it mattered.
                false
            }
        }
}
