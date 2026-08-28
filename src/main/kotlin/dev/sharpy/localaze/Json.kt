package dev.sharpy.localaze

/**
 * A minimal JSON reader, enough for the catalog format and nothing more.
 *
 * Writing one is a decision worth defending. The alternatives were worse:
 *
 * - `org.json` is on Android but not on the JVM, which would make the core an
 *   Android library and push the conformance suite onto an emulator. The whole
 *   reason this module is pure Kotlin/JVM is that the suite then runs in
 *   milliseconds on every build.
 * - `kotlinx-serialization` is a dependency plus a compiler plugin, and every
 *   dependency here is one each consuming app must also carry and keep in step.
 *
 * The catalog format is small and fixed — objects, arrays, strings, numbers,
 * booleans, null — so this is about a hundred lines that the catalog tests
 * cover completely. It is not a general-purpose parser and does not try to be.
 *
 * Returns null rather than throwing, like everything else in this package.
 */
internal object Json {

    fun parse(body: String): Any? =
        try {
            Reader(body).run {
                val value = readValue()
                skipWhitespace()
                if (atEnd()) value else null
            }
        } catch (error: Exception) {
            null
        }

    private class Reader(private val source: String) {
        private var at = 0

        fun atEnd(): Boolean = at >= source.length

        fun skipWhitespace() {
            while (at < source.length && source[at].isWhitespace()) at++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw IllegalStateException("unexpected end")

            return when (source[at]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { at++; return out }

            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                out[key] = readValue()
                skipWhitespace()
                when (source[at]) {
                    ',' -> at++
                    '}' -> { at++; return out }
                    else -> throw IllegalStateException("expected , or }")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { at++; return out }

            while (true) {
                out.add(readValue())
                skipWhitespace()
                when (source[at]) {
                    ',' -> at++
                    ']' -> { at++; return out }
                    else -> throw IllegalStateException("expected , or ]")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()

            while (true) {
                val character = source[at++]
                when (character) {
                    '"' -> return out.toString()
                    '\\' -> out.append(readEscape())
                    else -> out.append(character)
                }
            }
        }

        private fun readEscape(): Char {
            val marker = source[at++]
            return when (marker) {
                '"', '\\', '/' -> marker
                'b' -> '\b'
                'f' -> ''
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    val hex = source.substring(at, at + 4)
                    at += 4
                    hex.toInt(16).toChar()
                }
                else -> throw IllegalStateException("bad escape \\$marker")
            }
        }

        private fun readNumber(): Any {
            val start = at
            if (peek() == '-') at++
            while (at < source.length && (source[at].isDigit() || source[at] in ".eE+-")) at++

            val raw = source.substring(start, at)
            if (raw.isEmpty()) throw IllegalStateException("expected a value")

            // An integral value stays an Int, so a count read back out of a
            // catalog renders as "3" rather than "3.0".
            return raw.toIntOrNull() ?: raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun <T> readLiteral(word: String, value: T): T {
            if (!source.startsWith(word, at)) throw IllegalStateException("expected $word")
            at += word.length
            return value
        }

        private fun peek(): Char = source[at]

        private fun expect(character: Char) {
            if (source[at] != character) {
                throw IllegalStateException("expected $character at $at")
            }
            at++
        }
    }
}
