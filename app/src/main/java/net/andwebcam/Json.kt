package net.andwebcam

/**
 * Just enough JSON for ACME messages: objects, arrays, strings, numbers and
 * the three literals. ACME payloads are flat or shallowly nested, and keeping
 * this here avoids pulling a library into an app that currently has none.
 */
internal object Json {
    fun parse(s: String): Any? = P(s).value()

    fun stringify(v: Any?): String {
        val sb = StringBuilder()
        write(v, sb)
        return sb.toString()
    }

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> {
                sb.append('"')
                for (c in v) when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
                sb.append('"')
            }
            is Boolean, is Int, is Long, is Double, is Float -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, vv) in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(k.toString(), sb)
                    sb.append(':')
                    write(vv, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (vv in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(vv, sb)
                }
                sb.append(']')
            }
            else -> write(v.toString(), sb)
        }
    }

    private class P(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { lit("true"); true }
                'f' -> { lit("false"); false }
                'n' -> { lit("null"); null }
                else -> num()
            }
        }

        private fun lit(word: String) {
            if (s.startsWith(word, i)) i += word.length
        }

        private fun str(): String {
            val sb = StringBuilder()
            i++ // opening quote
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') break
                if (c == '\\' && i < s.length) {
                    when (val e = s[i++]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (i + 4 <= s.length) {
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: '?')
                                i += 4
                            }
                        }
                        else -> sb.append(e)
                    }
                } else sb.append(c)
            }
            return sb.toString()
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            while (i < s.length) {
                ws()
                if (s[i] == '}') { i++; break }
                val k = if (s[i] == '"') str() else { i++; "" }
                ws()
                if (i < s.length && s[i] == ':') i++
                m[k] = value()
                ws()
                if (i < s.length && s[i] == ',') i++
            }
            return m
        }

        private fun arr(): List<Any?> {
            val a = ArrayList<Any?>()
            i++ // [
            while (i < s.length) {
                ws()
                if (s[i] == ']') { i++; break }
                a.add(value())
                ws()
                if (i < s.length && s[i] == ',') i++
            }
            return a
        }

        private fun num(): Any? {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull()
        }
    }
}
