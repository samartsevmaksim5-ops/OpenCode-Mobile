package dev.opencode.mobile

/**
 * A small JSON reader and string quoter. The JVM tests and the device share this one parser, so no code depends on
 * `org.json` (which is only a stub off-device).
 */
object MiniJson {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipSpace()
        val value = reader.value(0)
        reader.skipSpace()
        require(reader.done()) { "Unexpected trailing characters at ${reader.position()}" }
        return value
    }

    /** Like [parse], but malformed input gives null instead of an exception. */
    fun parseOrNull(text: String): Any? = try {
        parse(text)
    } catch (_: Exception) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(text: String): Map<String, Any?>? = try {
        parse(text) as? Map<String, Any?>
    } catch (_: Exception) {
        null
    }

    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    /** Serializes maps, lists, strings and numbers back to JSON, so edited config files keep every unknown key. */
    fun write(value: Any?): String {
        val out = StringBuilder()

        writeTo(value, out)

        return out.toString()
    }

    private fun writeTo(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> out.append(quote(value))
            is Boolean -> out.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            // NaN/Infinity are not valid JSON values; writing them bare would corrupt the file (JSON.stringify
            // maps them to null, and so do we).
            is Double -> out.append(
                when {
                    value.isNaN() || value.isInfinite() -> "null"
                    value % 1.0 == 0.0 -> value.toLong().toString()
                    else -> value.toString()
                },
            )
            is Float -> writeTo(value.toDouble(), out)
            is Map<*, *> -> {
                out.append('{')
                var first = true

                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    out.append(quote(k.toString())).append(':')
                    writeTo(v, out)
                }

                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true

                for (v in value) {
                    if (!first) out.append(',')
                    first = false
                    writeTo(v, out)
                }

                out.append(']')
            }
            else -> out.append(quote(value.toString()))
        }
    }

    private class Reader(private val s: String) {
        private var i = 0

        fun position() = i

        fun done() = i >= s.length

        fun skipSpace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(depth: Int): Any? {
            require(depth < 64) { "JSON is nested too deeply" }
            require(i < s.length) { "Unexpected end of JSON" }
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw IllegalArgumentException("Unexpected '$c' at $i")
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            require(s.startsWith(word, i)) { "Bad literal at $i" }
            i += word.length
            return result
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val text = s.substring(start, i)
            return text.toLongOrNull() ?: text.toDouble()
        }

        private fun str(): String {
            i++
            val out = StringBuilder()
            while (true) {
                require(i < s.length) { "Unterminated string" }
                val c = s[i++]
                if (c == '"') return out.toString()
                if (c != '\\') {
                    out.append(c)
                    continue
                }
                require(i < s.length) { "Unterminated escape" }
                when (val e = s[i++]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000c')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        require(i + 4 <= s.length) { "Bad unicode escape" }
                        out.append(s.substring(i, i + 4).toInt(16).toChar())
                        i += 4
                    }
                    else -> throw IllegalArgumentException("Bad escape '\\$e'")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skipSpace()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (true) {
                skipSpace()
                out.add(value(depth + 1))
                skipSpace()
                require(i < s.length) { "Unterminated array" }
                val c = s[i++]
                if (c == ']') return out
                require(c == ',') { "Expected ',' at ${i - 1}" }
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (true) {
                skipSpace()
                require(i < s.length && s[i] == '"') { "Expected a key at $i" }
                val key = str()
                skipSpace()
                require(i < s.length && s[i++] == ':') { "Expected ':' after key" }
                skipSpace()
                out[key] = value(depth + 1)
                skipSpace()
                require(i < s.length) { "Unterminated object" }
                val c = s[i++]
                if (c == '}') return out
                require(c == ',') { "Expected ',' at ${i - 1}" }
            }
        }
    }
}
