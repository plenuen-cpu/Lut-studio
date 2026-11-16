package com.plenuen.lumen.data

/**
 * Minimal JSON reader (objects, arrays, strings, numbers, booleans, null).
 *
 * The preset catalog is a static asset, so a tiny dependency-free parser that also runs on a
 * plain JVM unit test is all that is needed.
 */
object Json {

    fun parse(text: String): Any? = Parser(text).document()

    @Suppress("UNCHECKED_CAST")
    fun asMap(value: Any?): Map<String, Any?> {
        require(value is Map<*, *>) { "expected object, got $value" }
        return value as Map<String, Any?>
    }

    fun asList(value: Any?): List<Any?> {
        require(value is List<*>) { "expected array, got $value" }
        @Suppress("UNCHECKED_CAST")
        return value as List<Any?>
    }

    fun asString(value: Any?): String {
        require(value is String) { "expected string, got $value" }
        return value
    }

    fun asInt(value: Any?): Int {
        require(value is Number) { "expected number, got $value" }
        return value.toInt()
    }

    fun mapOf(value: Any?, key: String): Any? = asMap(value)[key]

    private class Parser(private val s: String) {
        private var i = 0

        fun document(): Any? {
            val value = value()
            ws()
            require(i == s.length) { "trailing characters at $i" }
            return value
        }

        private fun ws() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++ else break
            }
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException("$message at $i")

        private fun value(): Any? {
            ws()
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected '$c'")
            }
        }

        private fun literal(token: String, result: Any?): Any? {
            if (!s.startsWith(token, i)) fail("bad literal")
            i += token.length
            return result
        }

        private fun obj(): Map<String, Any?> {
            i++ // {
            val map = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return map
            }
            while (true) {
                ws()
                val key = str()
                ws()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++
                map[key] = value()
                ws()
                if (i >= s.length) fail("unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return map
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(): List<Any?> {
            i++ // [
            val list = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return list
            }
            while (true) {
                list.add(value())
                ws()
                if (i >= s.length) fail("unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return list
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun str(): String {
            if (i >= s.length || s[i] != '"') fail("expected string")
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                val hex = s.substring(i, i + 4)
                                i += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> fail("bad escape '$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var isFloat = false
            while (i < s.length) {
                val c = s[i]
                if (c in '0'..'9') {
                    i++
                } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    if (c == '.' || c == 'e' || c == 'E') isFloat = true
                    i++
                } else {
                    break
                }
            }
            val token = s.substring(start, i)
            return if (isFloat) token.toDouble() else (token.toLongOrNull() ?: token.toDouble())
        }
    }
}
