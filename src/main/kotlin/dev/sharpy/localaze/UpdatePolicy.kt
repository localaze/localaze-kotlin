package dev.sharpy.localaze

/**
 * Semantic versions and the update policy.
 *
 * See `contract/runtime-contract.md` section 5.
 */

/**
 * Which updates an app is willing to take.
 *
 * A major release is refused under every policy, including [Range]. `LZ-62`
 * defines major as signature-breaking by construction, so its messages no
 * longer take the parameters generated accessors pass. Taking one would not be
 * a stale screen, it would be a wrong one.
 */
sealed class LocalazeUpdatePolicy(val label: String) {

    /** Never fetch. The app ships with what it was built with. */
    data object NONE : LocalazeUpdatePolicy("none")

    /** Wording fixes only. */
    data object PATCH_ONLY : LocalazeUpdatePolicy("patchOnly")

    /**
     * Wording fixes and added keys. The default, and exactly the set that
     * leaves every existing call site valid.
     */
    data object MINOR_AND_PATCH : LocalazeUpdatePolicy("minorAndPatch")

    /** Same major, and within [from] and [to] inclusive. */
    data class Range(val from: String, val to: String) :
        LocalazeUpdatePolicy("range($from..$to)")

    /** Whether this policy would even make a request. Only [NONE] would not. */
    val fetches: Boolean get() = this != NONE

    /**
     * Whether [candidate] may replace [current].
     *
     * [LocalazeVersion.accepts] has already established that the candidate is a
     * greater version with the same major, so this decides only the narrower
     * question each case's name describes.
     */
    internal fun permits(current: LocalazeVersion, candidate: LocalazeVersion): Boolean =
        when (this) {
            NONE -> false
            PATCH_ONLY -> current.minor == candidate.minor
            MINOR_AND_PATCH -> true
            is Range -> {
                val lower = LocalazeVersion.parse(from)
                val upper = LocalazeVersion.parse(to)
                lower != null && upper != null && candidate >= lower && candidate <= upper
            }
        }
}

/**
 * A semantic version, compared numerically field by field.
 *
 * String comparison would put `1.10.0` below `1.9.0`, which is the kind of bug
 * that only shows up once a project has shipped ten minor releases.
 */
data class LocalazeVersion(val major: Int, val minor: Int, val patch: Int) :
    Comparable<LocalazeVersion> {

    override fun compareTo(other: LocalazeVersion): Int {
        if (major != other.major) return major - other.major
        if (minor != other.minor) return minor - other.minor
        return patch - other.patch
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /**
         * Returns null for anything that is not `major.minor.patch`.
         *
         * A malformed version is refused rather than coerced, because guessing
         * what `latest` or `2.0` meant is how a major update gets applied by
         * accident.
         */
        fun parse(raw: String?): LocalazeVersion? {
            val fields = raw?.trim()?.split(".") ?: return null
            if (fields.size != 3) return null

            val numbers = fields.map { field ->
                field.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            }
            return LocalazeVersion(numbers[0], numbers[1], numbers[2])
        }

        /**
         * Whether [candidate] may replace [current] under [policy].
         *
         * Three gates, in order of how cheaply they settle the question: the
         * policy fetches at all, the candidate is actually newer, and the major
         * matches. The major gate sits outside the policy on purpose — no
         * policy may override it, so putting it in each case would invite one
         * to forget.
         */
        fun accepts(
            current: String?,
            candidate: String?,
            policy: LocalazeUpdatePolicy,
        ): Boolean {
            if (!policy.fetches) return false

            val from = parse(current) ?: return false
            val to = parse(candidate) ?: return false

            if (to <= from) return false
            if (to.major != from.major) return false

            return policy.permits(from, to)
        }
    }
}
