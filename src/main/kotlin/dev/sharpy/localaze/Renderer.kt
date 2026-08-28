package dev.sharpy.localaze

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * Turning a resolved message and its arguments into a string.
 *
 * The whole algorithm is: pick a variant, walk its parts, concatenate. If an
 * implementation needs more than that, the payload is wrong.
 *
 * See `../../contract/runtime-contract.md` section 3.
 */

/** How a `DATETIME` parameter becomes text. */
typealias LocalazeDateFormatter = (Instant) -> String

/**
 * ISO-8601, UTC, second precision: `2026-03-01T00:00:00Z`.
 *
 * The default is deliberate and worth arguing with. Locale-aware formatting is
 * usually what an app wants, but Dart, Swift and Kotlin do not produce the same
 * string for a given locale, so making it the default would break the one
 * guarantee these SDKs exist to make. This is identical on all three and
 * therefore testable. An app that wants a local format supplies one, and
 * accepts that it now owns the difference.
 *
 * `java.time` needs API 26, which is this package's `minSdk`. That is not a
 * coincidence: the baseline was chosen so no desugaring is required.
 */
val localazeIsoUtc: LocalazeDateFormatter = { value ->
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)
        .format(value)
}

/**
 * Renders [parts], substituting from [args].
 *
 * A missing argument cannot happen through generated code, which requires every
 * parameter at the call site. It is handled anyway, as the empty string,
 * because the alternative is throwing and nothing in this package throws.
 */
fun localazeRender(
    parts: List<LocalazePart>,
    args: Map<String, Any?>,
    dateFormatter: LocalazeDateFormatter = localazeIsoUtc,
): String {
    val out = StringBuilder()

    for (part in parts) {
        when (part) {
            is LocalazePart.Text -> out.append(part.value)
            is LocalazePart.Variable -> out.append(substitute(args[part.name], dateFormatter))
        }
    }
    return out.toString()
}

private fun substitute(value: Any?, dateFormatter: LocalazeDateFormatter): String =
    when (value) {
        null -> ""
        is Instant -> dateFormatter(value)
        is Date -> dateFormatter(value.toInstant())
        is Int, is Long -> value.toString()
        // An integral double renders without a decimal point, so a count passed
        // as 3.0 reads as "3" here, in Dart and in Swift alike. Left to
        // toString it would be "3.0" on all three: consistent, but not what a
        // count should look like on a screen.
        is Double ->
            if (value.isFinite() && value == Math.floor(value)) value.toLong().toString()
            else value.toString()
        is Float -> substitute(value.toDouble(), dateFormatter)
        else -> value.toString()
    }
