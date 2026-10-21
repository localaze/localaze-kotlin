package dev.sharpy.localaze.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.sharpy.localaze.LocalazeFetchOutcome
import dev.sharpy.localaze.LocalazeUpdatePolicy
import dev.sharpy.localaze.android.AndroidStorage
import dev.sharpy.localaze.android.AndroidTransport
import java.time.Instant

/**
 * A Compose demo that exercises every feature of the Localaze Kotlin SDK.
 *
 * Point it at the stub server in `../stub-server` (`dart run serve.dart`), then
 * use the Server card to put a patch, a major, or a retyped catalog on offer
 * and watch the SDK accept or refuse it.
 *
 * Note what is absent: `R.string` and `strings.xml`. `LZ-57` decides the SDK
 * bypasses Android string resources — every string on this screen that is a
 * translation comes from a generated accessor.
 */
class MainActivity : ComponentActivity() {

    private val model: DemoViewModel by lazy {
        ViewModelProvider(
            this,
            viewModelFactory {
                initializer {
                    DemoViewModel(
                        transport = AndroidTransport(),
                        storage = AndroidStorage(applicationContext),
                        baseUrl = BuildConfig.LOCALAZE_BASE_URL)
                }
            })[DemoViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Localaze's purple.
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB794F6))) {
                DemoScreen(model)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoScreen(model: DemoViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val t = state.translations

    Scaffold(topBar = { TopAppBar(title = { Text("Localaze SDK — v${state.version}") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Panel(
                    "Rendered strings",
                    "Every accessor is typed. inboxUnread(count = ) cannot be " +
                        "called without an Int — a compile error, not a runtime one.",
                ) {
                    Line("authSignOut", t.authSignOut)
                    Line("authWelcome(name =)", t.authWelcome(name = "Ayan"))
                    Line(
                        "billingDue(when =)",
                        t.billingDue(Instant.parse("2026-03-01T00:00:00Z")))
                    Line("inboxUnread(count =)", t.inboxUnread(count = state.count))

                    Slider(
                        value = state.count.toFloat(),
                        onValueChange = { model.setCount(it.toInt()) },
                        valueRange = 0f..120f,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0, 1, 2, 5, 11, 21, 101, 111).forEach { value ->
                            OutlinedButton(onClick = { model.setCount(value) }) {
                                Text("$value")
                            }
                        }
                    }
                    Text(
                        "In Russian, 11 and 111 select \"many\" despite ending in 1. " +
                            "That rule is compiled into Localaze.g.kt, so no CLDR " +
                            "data ships and no MessageFormat is parsed on device.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                Panel("Locale", "Switches at runtime, surviving configuration change.") {
                    SingleChoiceSegmentedButtonRow {
                        listOf("az" to "Azərbaycan", "ru" to "Русский")
                            .forEachIndexed { index, (code, label) ->
                                SegmentedButton(
                                    selected = state.locale == code,
                                    onClick = { model.setLocale(code) },
                                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                                ) { Text(label) }
                            }
                    }
                }
            }

            item {
                Panel(
                    "Server",
                    "Puts a release on offer. The stub server serves the same " +
                        "fixtures the conformance suites assert on.",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Scenario.entries.take(4).forEach { scenario ->
                            FilterChip(
                                selected = state.scenario == scenario,
                                onClick = { model.serve(scenario) },
                                label = { Text(scenario.wire) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Scenario.entries.drop(4).forEach { scenario ->
                            FilterChip(
                                selected = state.scenario == scenario,
                                onClick = { model.serve(scenario) },
                                label = { Text(scenario.wire) })
                        }
                    }
                    Text(
                        state.scenario.explanation,
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                Panel(
                    "Update policy",
                    "Policy is about intent. The signature check is about " +
                        "possibility, and it runs regardless — a major is refused " +
                        "under every policy, including a range.",
                ) {
                    listOf(
                        "none — never fetch" to LocalazeUpdatePolicy.NONE,
                        "patchOnly — wording fixes" to LocalazeUpdatePolicy.PATCH_ONLY,
                        "minorAndPatch — the default" to
                            LocalazeUpdatePolicy.MINOR_AND_PATCH,
                    ).forEach { (label, policy) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = state.policy == policy,
                                onClick = { model.setPolicy(policy) })
                            Text(label)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = state.applyImmediately,
                            onCheckedChange = { model.setApplyImmediately(it) })
                        Text(
                            "  applyImmediately — off in the SDK by default, because " +
                                "text changing under a user mid-session is worse " +
                                "than being one launch out of date",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Panel("Update", "Never throws, and never blocks a screen.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { model.update() }, enabled = !state.busy) {
                            Text(if (state.busy) "Checking…" else "Check for update")
                        }
                        OutlinedButton(onClick = { model.applyPending() }) {
                            Text("Apply pending")
                        }
                    }
                    state.lastStatus?.let { status ->
                        Text(
                            headline(status.outcome),
                            style = MaterialTheme.typography.titleSmall,
                            color = colour(status.outcome))
                        status.detail?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Panel(
                    "Diagnostics",
                    "Nothing in the SDK throws, so a wrong key and a quiet network " +
                        "look identical from outside. This callback is how anyone " +
                        "finds out an integration never worked.",
                ) {
                    if (state.diagnostics.isEmpty()) {
                        Text("Nothing reported.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        state.diagnostics.take(12).forEach {
                            Text(
                                "• $it",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                        }
                        OutlinedButton(onClick = { model.clearDiagnostics() }) {
                            Text("Clear")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@Composable
private fun Line(call: String, rendered: String) {
    Column {
        Text(
            call,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace)
        Text(rendered, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun headline(outcome: LocalazeFetchOutcome): String = when (outcome) {
    LocalazeFetchOutcome.APPLIED -> "Applied"
    LocalazeFetchOutcome.UP_TO_DATE -> "Already up to date"
    LocalazeFetchOutcome.REFUSED_BY_POLICY -> "Refused by policy"
    LocalazeFetchOutcome.REFUSED_BY_SIGNATURE -> "Refused by signature"
    LocalazeFetchOutcome.FAILED -> "Failed"
    LocalazeFetchOutcome.NONE -> "—"
}

private fun colour(outcome: LocalazeFetchOutcome): Color = when (outcome) {
    LocalazeFetchOutcome.APPLIED -> Color(0xFF4CAF50)
    LocalazeFetchOutcome.UP_TO_DATE -> Color(0xFF9E9E9E)
    LocalazeFetchOutcome.REFUSED_BY_POLICY -> Color(0xFFFFA726)
    LocalazeFetchOutcome.REFUSED_BY_SIGNATURE, LocalazeFetchOutcome.FAILED ->
        Color(0xFFEF5350)
    LocalazeFetchOutcome.NONE -> Color(0xFF9E9E9E)
}
