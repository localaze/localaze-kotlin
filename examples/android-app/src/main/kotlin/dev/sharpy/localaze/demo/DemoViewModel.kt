package dev.sharpy.localaze.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sharpy.localaze.LocalazeConfig
import dev.sharpy.localaze.LocalazeFetchStatus
import dev.sharpy.localaze.LocalazeStorage
import dev.sharpy.localaze.LocalazeTransport
import dev.sharpy.localaze.LocalazeUpdatePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Where the stub server is listening.
 *
 * An **emulator** reaches the host at `10.0.2.2`, not `localhost` — that one
 * catches everybody once. A device on the same network wants the machine's
 * address; override it in `build.gradle.kts`.
 */
const val DEFAULT_BASE_URL: String = "http://10.0.2.2:8787"

/** The stub server's key. A real one carries the project and the environment. */
const val API_KEY: String = "demo-key"

/** Mirrors the scenarios in `../../stub-server/serve.dart`. */
enum class Scenario(val explanation: String) {
    BASE("1.0.0 — what the app was built against. Nothing to take."),
    PATCH("1.0.1 — a wording fix. Accepted under patchOnly and above."),
    MINOR("1.1.0 — adds a key. Refused under patchOnly."),
    RETYPED(
        "1.0.2 — a patch that retypes count from NUMBER to STRING. Policy " +
            "accepts it, every key is present, and the parameter is still " +
            "called \"count\". Only the type check refuses it."),
    MAJOR("2.0.0 — drops a key. Refused under every policy."),
    MALFORMED("1.0.4 — a body with no messages."),
    DOWN("Every request fails, for the offline path.");

    val wire: String get() = name.lowercase()
}

data class DemoState(
    val translations: Localaze,
    val locale: String = "az",
    val count: Int = 1,
    val scenario: Scenario = Scenario.BASE,
    val policy: LocalazeUpdatePolicy = LocalazeUpdatePolicy.MINOR_AND_PATCH,
    val applyImmediately: Boolean = true,
    val busy: Boolean = false,
    val lastStatus: LocalazeFetchStatus? = null,
    /**
     * Every diagnostic the SDK reports.
     *
     * Nothing in the SDK throws, which means a wrong API key and a quiet
     * network look identical from the outside. Showing these is the whole
     * reason the callback exists.
     */
    val diagnostics: List<String> = emptyList(),
) {
    val version: String get() = translations.runtime.version
}

class DemoViewModel(
    private val transport: LocalazeTransport,
    private val storage: LocalazeStorage,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : ViewModel() {

    private val _state = MutableStateFlow(DemoState(translations = build("az")))
    val state: StateFlow<DemoState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value.translations.runtime.load()
            refresh()
        }
    }

    /** Config is immutable, so changing an option means a fresh runtime. */
    private fun build(
        locale: String,
        policy: LocalazeUpdatePolicy = LocalazeUpdatePolicy.MINOR_AND_PATCH,
        applyImmediately: Boolean = true,
    ): Localaze = Localaze.forLocale(
        locale,
        LocalazeConfig(
            apiKey = API_KEY,
            baseUrl = baseUrl,
            policy = policy,
            applyImmediately = applyImmediately,
            transport = transport,
            storage = storage,
            onDiagnostic = { message, error ->
                val line = if (error == null) message else "$message — $error"
                _state.update { it.copy(diagnostics = listOf(line) + it.diagnostics) }
            },
        ))

    private fun rebuild(
        locale: String = _state.value.locale,
        policy: LocalazeUpdatePolicy = _state.value.policy,
        applyImmediately: Boolean = _state.value.applyImmediately,
    ) {
        val translations = build(locale, policy, applyImmediately)
        _state.update {
            it.copy(
                translations = translations,
                locale = locale,
                policy = policy,
                applyImmediately = applyImmediately)
        }
        viewModelScope.launch {
            translations.runtime.load()
            refresh()
        }
    }

    /** Nudges the flow so recomposition picks up in-place runtime changes. */
    private fun refresh() = _state.update { it.copy() }

    /** Switches locale without a restart, as `LZ-57` requires. */
    fun setLocale(locale: String) {
        if (locale == _state.value.locale || locale !in Localaze.supportedLocales) return
        rebuild(locale = locale)
    }

    fun setPolicy(policy: LocalazeUpdatePolicy) = rebuild(policy = policy)

    fun setApplyImmediately(value: Boolean) = rebuild(applyImmediately = value)

    fun setCount(count: Int) = _state.update { it.copy(count = count) }

    fun update() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val status = _state.value.translations.runtime.update()
            _state.update { it.copy(busy = false, lastStatus = status) }
        }
    }

    fun applyPending(): Boolean {
        val applied = _state.value.translations.runtime.applyPending()
        if (applied) refresh()
        return applied
    }

    fun clearDiagnostics() = _state.update { it.copy(diagnostics = emptyList()) }

    /**
     * Switches what the stub server offers, through its control endpoint.
     *
     * The real read API has no such thing — this is the demo harness, not the
     * SDK, which is why it goes straight to the transport.
     */
    fun serve(scenario: Scenario) {
        viewModelScope.launch {
            transport.get("$baseUrl/control/serve/${scenario.wire}", emptyMap())
            _state.update { it.copy(scenario = scenario) }
        }
    }
}
