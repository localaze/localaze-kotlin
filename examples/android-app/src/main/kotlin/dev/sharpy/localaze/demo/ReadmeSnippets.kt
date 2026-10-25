// The Android code the README shows, compiled by CI with the rest of this app.
//
// `Localaze.g.kt` beside this file is what the generator writes for the
// contract's fixture project; an app's own file has its own accessors.

package dev.sharpy.localaze.demo

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sharpy.localaze.LocalazeConfig
import dev.sharpy.localaze.LocalazeUpdatePolicy
import dev.sharpy.localaze.android.AndroidStorage
import dev.sharpy.localaze.android.AndroidTransport
import dev.sharpy.localaze.localazeIsoUtc
import kotlinx.coroutines.launch

internal object ReadmeSnippets {
    fun setup(context: Context, key: String): Localaze {
        // #region setup
        val t = Localaze.forLocale(
            "ru",
            LocalazeConfig(
                apiKey = key, // from BuildConfig or your CI's secrets, never the source
                transport = AndroidTransport(),
                storage = AndroidStorage(context), // assets/localaze/, and a cache in filesDir
                onDiagnostic = { message, error -> Log.w("Localaze", "$message ${error ?: ""}") },
            ),
        )
        // #endregion setup
        return t
    }

    suspend fun update(t: Localaze) {
        // #region update
        t.runtime.load()                     // the cached catalog, if any
        val status = t.runtime.update()      // suspends, never throws
        Log.i("Localaze", if (status.isApplied) "now on ${t.runtime.version}" else "still on ${t.runtime.version}")
        // #endregion update
    }

    fun everyOption(key: String): LocalazeConfig {
        // #region config
        return LocalazeConfig(
            apiKey = key,                    // carries the project and the environment
            baseUrl = "https://localize.sharpy.dev",
            policy = LocalazeUpdatePolicy.MINOR_AND_PATCH,
            timeoutMillis = 10_000,
            applyImmediately = false,
            dateFormatter = localazeIsoUtc,
            onDiagnostic = null,
            transport = null,                // no network: AndroidTransport()
            storage = null,                  // updates last for the session: AndroidStorage(context)
        )
        // #endregion config
    }
}

// #region compose
class TranslationsViewModel(app: Application) : AndroidViewModel(app) {
    private var current by mutableStateOf(make("az"))
    private var revision by mutableIntStateOf(0)

    /** Reading it subscribes a composable to locale switches and applied catalogs. */
    val t: Localaze get() = current.also { revision }

    fun setLocale(locale: String) {          // survives configuration change, no restart
        current = make(locale)
        viewModelScope.launch { current.runtime.load(); revision++ }
    }

    fun update() = viewModelScope.launch {
        if (current.runtime.update().isApplied) revision++
    }

    private fun make(locale: String) = Localaze.forLocale(
        locale,
        LocalazeConfig(apiKey = "…", transport = AndroidTransport(), storage = AndroidStorage(getApplication())),
    )
}

@Composable
fun InboxTitle(model: TranslationsViewModel) {
    LaunchedEffect(Unit) { model.update() }
    Text(model.t.inboxUnread(count = 3))
}
// #endregion compose
