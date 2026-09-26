package app.mizan.service.json

/**
 * The smallest JSON reader and writer that the reference service needs.
 *
 * It is deliberately dependency free. Parsing is strict: a malformed body is
 * rejected, never coerced into a partial request. Object members keep their
 * insertion order so responses are stable from run to run.
 */
sealed interface JsonValue {
    data object Null : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val raw: String) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
}

class JsonParseException(message: String) : RuntimeException(message)

fun JsonValue.asString(): String? = (this as? JsonValue.Str)?.value

fun JsonValue.asLong(): Long? = (this as? JsonValue.Num)?.raw?.toLongOrNull()

fun JsonValue.asBoolean(): Boolean? = (this as? JsonValue.Bool)?.value

fun JsonValue.asObject(): JsonValue.Obj? = this as? JsonValue.Obj

fun JsonValue.asArray(): JsonValue.Arr? = this as? JsonValue.Arr

fun JsonValue.Obj.field(name: String): JsonValue? = fields[name]

fun JsonValue.Obj.text(name: String): String? = fields[name]?.asString()

fun JsonValue.Obj.whole(name: String): Long? = fields[name]?.asLong()

fun JsonValue.Obj.flag(name: String): Boolean? = fields[name]?.asBoolean()

/**
 * Reads a whole number that may have arrived as a JSON string.
 *
 * The Android client canonicalises every number as a string before it hashes a
 * request -- `CanonicalValue.Num` -- so `amountMinor` reaches the service as
 * `"250000"` even though a plain JSON client would send `250000`. Both shapes
 * are one value; anything else is not a number and must be refused, not
 * coerced.
 */
fun JsonValue.Obj.wholeOrNumericText(name: String): Long? = when (val value = fields[name]) {
    is JsonValue.Num -> value.raw.toLongOrNull()
    is JsonValue.Str -> value.value.trim().toLongOrNull()
    else -> null
}

object Json {

    fun parse(text: String): JsonValue {
        val reader = Reader(text)
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) {
            throw JsonParseException("trailing content at ${reader.position}")
        }
        return value
    }

    /** Parses and returns null instead of throwing when the body is not JSON. */
    fun parseOrNull(text: String): JsonValue? = try {
        parse(text)
    } catch (_: JsonParseException) {
        null
    }

    fun write(value: JsonValue): String {
        val builder = StringBuilder()
        writeValue(builder, value)
        return builder.toString()
    }

    fun obj(vararg fields: Pair<String, JsonValue?>): JsonValue.Obj {
        val map = LinkedHashMap<String, JsonValue>()
        fields.forEach { (name, child) -> if (child != null) map[name] = child }
        return JsonValue.Obj(map)
    }

    fun str(value: String?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Str(value)

    fun num(value: Long?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Num(value.toString())

    fun num(value: Int?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Num(value.toString())

    fun bool(value: Boolean): JsonValue = JsonValue.Bool(value)

    fun arr(items: List<JsonValue>): JsonValue = JsonValue.Arr(items)

    private fun writeValue(builder: StringBuilder, value: JsonValue) {
        when (value) {
            JsonValue.Null -> builder.append("null")
            is JsonValue.Bool -> builder.append(if (value.value) "true" else "false")
            is JsonValue.Num -> builder.append(value.raw)
            is JsonValue.Str -> writeString(builder, value.value)
            is JsonValue.Arr -> {
                builder.append('[')
                value.items.forEachIndexed { index, child ->
                    if (index > 0) builder.append(',')
                    writeValue(builder, child)
                }
                builder.append(']')
            }
            is JsonValue.Obj -> {
                builder.append('{')
                var first = true
                value.fields.forEach { (name, child) ->
                    if (!first) builder.append(',')
                    first = false
                    writeString(builder, name)
                    builder.append(':')
                    writeValue(builder, child)
                }
                builder.append('}')
            }
        }
    }

    private fun writeString(builder: StringBuilder, raw: String) {
        builder.append('"')
        raw.forEach { ch ->
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                0x0C.toChar() -> builder.append("\\f")
                else -> if (ch < 0x20.toChar()) {
                    builder.append("\\u")
                    builder.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(ch)
                }
            }
        }
        builder.append('"')
    }

    private class Reader(private val text: String) {
        var position: Int = 0
            private set

        fun atEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        fun readValue(): JsonValue {
            skipWhitespace()
            if (atEnd()) throw JsonParseException("unexpected end of input")
            return when (val ch = text[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't' -> readLiteral("true", JsonValue.Bool(true))
                'f' -> readLiteral("false", JsonValue.Bool(false))
                'n' -> readLiteral("null", JsonValue.Null)
                else -> if (ch == '-' || ch in '0'..'9') readNumber() else {
                    throw JsonParseException("unexpected '$ch' at $position")
                }
            }
        }

        private fun readObject(): JsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                position++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val name = readString()
                skipWhitespace()
                expect(':')
                fields[name] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return JsonValue.Obj(fields)
                    }
                    else -> throw JsonParseException("expected ',' or '}' at $position")
                }
            }
        }

        private fun readArray(): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                position++
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return JsonValue.Arr(items)
                    }
                    else -> throw JsonParseException("expected ',' or ']' at $position")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonParseException("unterminated string")
                val ch = text[position++]
                when (ch) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonParseException("unterminated escape")
                        val escaped = text[position++]
                        when (escaped) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append(0x0C.toChar())
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) {
                                    throw JsonParseException("truncated unicode escape")
                                }
                                val hex = text.substring(position, position + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonParseException("bad unicode escape '$hex'")
                                builder.append(code.toChar())
                                position += 4
                            }
                            else -> throw JsonParseException("bad escape '\\$escaped'")
                        }
                    }
                    else -> builder.append(ch)
                }
            }
        }

        private fun readNumber(): JsonValue {
            val start = position
            if (peek() == '-') position++
            while (!atEnd() && text[position] in '0'..'9') position++
            if (!atEnd() && text[position] == '.') {
                position++
                while (!atEnd() && text[position] in '0'..'9') position++
            }
            if (!atEnd() && (text[position] == 'e' || text[position] == 'E')) {
                position++
                if (!atEnd() && (text[position] == '+' || text[position] == '-')) position++
                while (!atEnd() && text[position] in '0'..'9') position++
            }
            val raw = text.substring(start, position)
            if (raw.isEmpty() || raw == "-") throw JsonParseException("bad number at $start")
            return JsonValue.Num(raw)
        }

        private fun readLiteral(literal: String, value: JsonValue): JsonValue {
            if (!text.startsWith(literal, position)) {
                throw JsonParseException("expected '$literal' at $position")
            }
            position += literal.length
            return value
        }

        private fun peek(): Char? = if (atEnd()) null else text[position]

        private fun expect(expected: Char) {
            if (atEnd() || text[position] != expected) {
                throw JsonParseException("expected '$expected' at $position")
            }
            position++
        }
    }
}
