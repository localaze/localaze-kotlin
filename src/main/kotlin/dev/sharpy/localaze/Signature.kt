package dev.sharpy.localaze

/**
 * What the generated code promised its call sites, and checking a catalog
 * against it.
 *
 * See `../../contract/runtime-contract.md` section 4.
 */
class LocalazeSignature(val keys: Map<String, List<LocalazeParam>>) {

    /**
     * Why [catalog] cannot be applied, empty when it can.
     *
     * Three rules, and each one is a decision rather than an oversight:
     *
     * - A **missing key** is incompatible. Generated code has an accessor for
     *   it and nothing to render.
     * - A parameter whose **name or type** differs is incompatible. Checking
     *   names alone is the defect this replaces: `{$count}` gaining `:number`
     *   leaves the key and the name intact while changing what every call site
     *   passes, and it is the likelier edit in practice than a removal.
     * - An **added key**, or an **extra parameter** on a key we do know, is
     *   fine. The app has no accessor for either, and a newer platform release
     *   is not a client failure. The signature is a floor, not an equality.
     */
    fun incompatibilities(catalog: LocalazeCatalog): List<String> {
        val problems = ArrayList<String>()

        // Sorted so the message is stable between runs, which matters when it
        // is the only thing in a log.
        for (key in keys.keys.sorted()) {
            val expected = keys[key].orEmpty()
            val message = catalog.messages[key]

            if (message == null) {
                problems.add("$key is missing")
                continue
            }

            val declared = message.params.associate { it.name to it.type }

            for (param in expected) {
                val actual = declared[param.name]
                when {
                    actual == null ->
                        problems.add("$key no longer takes \"${param.name}\"")
                    actual != param.type ->
                        problems.add(
                            "$key.${param.name} is now ${actual.label}, " +
                                "generated as ${param.type.label}")
                }
            }
        }
        return problems
    }

    fun accepts(catalog: LocalazeCatalog): Boolean = incompatibilities(catalog).isEmpty()

    companion object {
        /**
         * Builds a signature from the compact form generated code emits:
         * `mapOf("inbox.unread" to listOf("count" to "NUMBER"))`.
         */
        fun of(raw: Map<String, List<Pair<String, String>>>): LocalazeSignature =
            LocalazeSignature(
                raw.mapValues { (_, params) ->
                    params.map { (name, type) ->
                        LocalazeParam(name, LocalazeParamType.parse(type))
                    }
                })
    }
}
