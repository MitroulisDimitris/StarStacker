package com.starstacker.json

/**
 * A minimal JSON writer and reader.
 *
 * `org.json` is a stub in JVM unit tests and a serialization library is an annotation processor
 * and a build-time cost for a handful of flat records. Everything this app persists — the device
 * profile (FR-3.2.1), stored focus (T-2.4), `session.json` (FR-9.2) — is plain nested maps,
 * lists, strings and numbers, which is a small enough target to own outright.
 *
 * Reading matters as much as writing: D-5 makes `session.json` on disk the source of truth, so
 * the app has to be able to parse back what it wrote — including files a user copied from a PC.
 */
object Json {

    // ---- writing --------------------------------------------------------------------

    fun write(value: Any?, indent: Int = 0): String {
        val pad = "  ".repeat(indent)
        val padInner = "  ".repeat(indent + 1)
        return when (value) {
            null -> "null"
            is String -> quote(value)
            is Boolean -> value.toString()
            is Number -> numberOf(value)
            is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(
                separator = ",\n",
                prefix = "{\n",
                postfix = "\n$pad}",
            ) { (k, v) -> "$padInner${quote(k.toString())}: ${write(v, indent + 1)}" }

            is Collection<*> -> if (value.isEmpty()) "[]" else value.joinToString(
                separator = ",\n",
                prefix = "[\n",
                postfix = "\n$pad]",
            ) { "$padInner${write(it, indent + 1)}" }

            else -> quote(value.toString())
        }
    }

    private fun numberOf(n: Number): String {
        val d = n.toDouble()
        return if (d.isNaN() || d.isInfinite()) "null" else n.toString()
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // ---- reading --------------------------------------------------------------------

    class JsonException(message: String) : Exception(message)

    /** Parses into `Map<String, Any?>`, `List<Any?>`, `String`, `Double`, `Boolean` or null. */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd) throw JsonException("trailing content at offset ${reader.offset}")
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw JsonException("expected a JSON object")

    private class Reader(private val text: String) {
        var offset = 0
            private set

        val atEnd: Boolean get() = offset >= text.length

        fun skipWhitespace() {
            while (offset < text.length && text[offset].isWhitespace()) offset++
        }

        fun readValue(): Any? {
            if (atEnd) throw JsonException("unexpected end of input")
            return when (val c = text[offset]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                else -> if (c == '-' || c.isDigit()) readNumber() else {
                    throw JsonException("unexpected '$c' at offset $offset")
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                offset++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                map[key] = readValue()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> Unit
                    '}' -> return map
                    else -> throw JsonException("expected ',' or '}' but found '$c' at $offset")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                offset++
                return list
            }
            while (true) {
                skipWhitespace()
                list += readValue()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> Unit
                    ']' -> return list
                    else -> throw JsonException("expected ',' or ']' but found '$c' at $offset")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val escape = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append(0x0C.toChar())
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (offset + 4 > text.length) throw JsonException("truncated \\u escape")
                            sb.append(text.substring(offset, offset + 4).toInt(16).toChar())
                            offset += 4
                        }

                        else -> throw JsonException("bad escape '\\$escape' at offset $offset")
                    }

                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Double {
            val start = offset
            if (peek() == '-') offset++
            while (!atEnd && (text[offset].isDigit() || text[offset] in ".eE+-")) offset++
            val literal = text.substring(start, offset)
            return literal.toDoubleOrNull()
                ?: throw JsonException("bad number '$literal' at offset $start")
        }

        private fun readBoolean(): Boolean = when {
            text.startsWith("true", offset) -> { offset += 4; true }
            text.startsWith("false", offset) -> { offset += 5; false }
            else -> throw JsonException("bad literal at offset $offset")
        }

        private fun readNull(): Any? {
            if (!text.startsWith("null", offset)) throw JsonException("bad literal at offset $offset")
            offset += 4
            return null
        }

        private fun peek(): Char =
            if (atEnd) throw JsonException("unexpected end of input") else text[offset]

        private fun next(): Char =
            if (atEnd) throw JsonException("unexpected end of input") else text[offset++]

        private fun expect(c: Char) {
            val actual = next()
            if (actual != c) throw JsonException("expected '$c' but found '$actual' at $offset")
        }
    }
}

// ---- typed accessors, so callers do not cast at every use ---------------------------

fun Map<String, Any?>.string(key: String): String? = this[key] as? String
fun Map<String, Any?>.double(key: String): Double? = (this[key] as? Number)?.toDouble()
fun Map<String, Any?>.float(key: String): Float? = (this[key] as? Number)?.toFloat()
fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
fun Map<String, Any?>.boolean(key: String): Boolean? = this[key] as? Boolean

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.objects(key: String): List<Map<String, Any?>> =
    (this[key] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
