package dev.sharpy.localaze.golden

import dev.sharpy.localaze.Json
import dev.sharpy.localaze.LocalazeConfig
import dev.sharpy.localaze.fakes.fixture
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generated code, compiled against the runtime, rendering the fixture.
 *
 * This is the `LZ-54` guarantee for generation: the three generators agree
 * because their output renders the same strings, not because their source
 * looks alike. See `contract/generator-contract.md` §9.
 */
class GoldenRenderTest {

    private fun translations(locale: String) =
        Localaze.forLocale(locale, LocalazeConfig(apiKey = "test-key"))

    /** Calls the typed accessor for [key], the way an app would. */
    private fun render(t: Localaze, key: String, args: Map<*, *>): String = when (key) {
        "auth.sign_out" -> t.authSignOut
        "auth.welcome" -> t.authWelcome(name = args["name"] as String)
        "billing.due" -> t.billingDue(`when` = Instant.parse(args["when"] as String))
        "inbox.unread" -> t.inboxUnread(count = args["count"] as Number)
        else -> error("No accessor for $key")
    }

    @Test
    fun rendersAll() {
        val renders = (Json.parse(fixture("expected-renders.json")) as Map<*, *>)["renders"] as List<*>
        assertTrue(renders.isNotEmpty())
        for (row in renders.map { it as Map<*, *> }) {
            val locale = row["locale"] as String
            val key = row["key"] as String
            assertEquals(
                row["expect"],
                render(translations(locale), key, row["args"] as Map<*, *>),
                "$locale $key ${row["args"]}")
        }
    }

    @Test
    fun typedAccessorsAndConstants() {
        assertEquals(listOf("az", "ru"), Localaze.supportedLocales)
        assertEquals("az", Localaze.SOURCE_LOCALE)
        assertEquals("1.0.0", Localaze.GENERATED_FROM)

        // Any Number is accepted, integral or not.
        val ru = translations("ru")
        assertEquals("У вас 11 сообщений", ru.inboxUnread(count = 11))
        assertEquals("У вас 21 сообщение", ru.inboxUnread(count = 21L))
        assertEquals("У вас 1.5 сообщения", ru.inboxUnread(count = 1.5))
    }

    @Test
    fun pluralGuardRendersTheCatchAll() {
        val ru = translations("ru")
        for (value in listOf(Double.POSITIVE_INFINITY, Double.NaN, 1e15, -1e16)) {
            assertTrue(ru.inboxUnread(count = value).endsWith("сообщения"), "$value")
        }
    }

    @Test
    fun uncarriedLocaleStartsFromTheSourceLanguage() {
        assertEquals("Cixis", translations("en").authSignOut)
    }
}
