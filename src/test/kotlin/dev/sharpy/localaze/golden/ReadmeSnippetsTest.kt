package dev.sharpy.localaze.golden

import dev.sharpy.localaze.LocalazeConfig
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The README's typed-accessor example, compiled and run against the golden
 * generated code for the contract's fixture project: what the README says it
 * renders is what it renders. The Android examples are in the Compose demo,
 * compiled by its own build.
 */
class ReadmeSnippetsTest {
    private fun use(t: Localaze): List<String> {
        // #region use
        val lines = listOf(
            t.authSignOut,                          // "Выход"
            t.authWelcome(name = "Аян"),            // "Добро пожаловать, Аян"
            t.inboxUnread(count = 1),               // "У вас 1 сообщение"
            t.inboxUnread(count = 11),              // "У вас 11 сообщений"
            t.billingDue(`when` = Instant.parse("2026-03-01T00:00:00Z")),
        )
        // Neither of these compiles:
        // t.inboxUnread()                  a plural needs its count
        // t.inboxUnread(count = "eleven")  the count is a number
        // #endregion use
        return lines
    }

    @Test
    fun theReadmeRendersWhatItSays() {
        val t = Localaze.forLocale("ru", LocalazeConfig(apiKey = "test-key"))
        assertEquals(
            listOf(
                "Выход",
                "Добро пожаловать, Аян",
                "У вас 1 сообщение",
                "У вас 11 сообщений",
                "Оплата до 2026-03-01T00:00:00Z",
            ),
            use(t),
        )
    }
}
