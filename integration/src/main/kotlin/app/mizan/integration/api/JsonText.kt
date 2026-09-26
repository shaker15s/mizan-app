package app.mizan.integration.api

/**
 * A strict, small reader for the JSON the service answers with.
 *
 * The device does not trust the shape of a reply: the service is a different
 * program, on a network, and a client that scrapes a response with a regular
 * expression is a client that believes whatever happens to look like a field.
 * This reads the document once, fails closed on anything malformed, and hands
 * out values by name.
 *
 * It is deliberately not a serialisation library. It reads the flat objects
 * and arrays the contract uses, refuses depth it was not written for, and has
 * no opinion about anything else.
 */
sealed interface JsonText {

    data class Str(val value: String) : JsonText

    data class Num(val raw: String) : JsonText

    data class Bool(val value: Boolean) : JsonText

    data object Null : JsonText

    data class Arr(val items: List<JsonText>) : JsonText

    data class Obj(val fields: Map<String, JsonText>) : JsonText {

        fun text(name: String): String? = (fields[name] as? Str)?.value

        fun whole(name: String): Long? = when (val value = fields[name]) {
            is Num -> value.raw.toLongOrNull()
            is Str -> value.value.toLongOrNull()
            else -> null
        }

        fun flag(name: String): Boolean? = when (val value = fields[name]) {
            is Bool -> value.value
            is Str -> value.value.toBooleanStrictOrNull()
            else -> null
        }

        fun obj(name: String): Obj? = fields[name] as? Obj

        fun array(name: String): List<JsonText> = (fields[name] as? Arr)?.items.orEmpty()

        fun strings(name: String): List<String> = array(name).mapNotNull { (it as? Str)?.value }
    }

    companion object {

        /** Null when the text is not a JSON document this reader understands. */
        fun parse(text: String): JsonText? = Reader(text).document()
    }

    private class Reader(private val source: String) {

        private var index = 0

        fun document(): JsonText? {
            val value = value() ?: return null
            skipWhitespace()
            return if (index == source.length) value else null
        }

        private fun value(): JsonText? {
            skipWhitespace()
            if (index >= source.length) return null
            return when (source[index]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()?.let { Str(it) }
                't' -> literal("true", Bool(true))
                'f' -> literal("false", Bool(false))
                'n' -> literal("null", Null)
                else -> number()
            }
        }

        private fun obj(): JsonText? {
            index++ // {
            val fields = LinkedHashMap<String, JsonText>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val name = string() ?: return null
                skipWhitespace()
                if (peek() != ':') return null
                index++
                val child = value() ?: return null
                fields[name] = child
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return Obj(fields)
                    }
                    else -> return null
                }
            }
        }

        private fun array(): JsonText? {
            index++ // [
            val items = ArrayList<JsonText>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return Arr(items)
            }
            while (true) {
                val child = value() ?: return null
                items += child
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return Arr(items)
                    }
                    else -> return null
                }
            }
        }

        private fun string(): String? {
            if (peek() != '"') return null
            index++
            val builder = StringBuilder()
            while (index < source.length) {
                val character = source[index]
                when {
                    character == '"' -> {
                        index++
                        return builder.toString()
                    }
                    character == '\\' -> {
                        index++
                        if (index >= source.length) return null
                        when (val escape = source[index]) {
                            '"', '\\', '/' -> builder.append(escape)
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (index + 4 >= source.length) return null
                                val hex = source.substring(index + 1, index + 5)
                                val code = hex.toIntOrNull(16) ?: return null
                                builder.append(code.toChar())
                                index += 4
                            }
                            else -> return null
                        }
                        index++
                    }
                    character.code < 0x20 -> return null
                    else -> {
                        builder.append(character)
                        index++
                    }
                }
            }
            return null
        }

        private fun number(): JsonText? {
            val start = index
            if (peek() == '-') index++
            // JSON forbids a leading zero: "01" is not one, and a reader that
            // accepts it is a reader that will one day accept a mistyped id.
            if (peek() == '0' && source.getOrNull(index + 1)?.isDigit() == true) return null
            var digits = 0
            while (index < source.length && source[index].isDigit()) {
                index++
                digits++
            }
            if (digits == 0) return null
            if (peek() == '.') {
                index++
                var fraction = 0
                while (index < source.length && source[index].isDigit()) {
                    index++
                    fraction++
                }
                if (fraction == 0) return null
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                var exponent = 0
                while (index < source.length && source[index].isDigit()) {
                    index++
                    exponent++
                }
                if (exponent == 0) return null
            }
            return Num(source.substring(start, index))
        }

        private fun <T : JsonText> literal(word: String, value: T): T? {
            if (!source.startsWith(word, index)) return null
            index += word.length
            return value
        }

        private fun peek(): Char? = source.getOrNull(index)

        private fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }
    }
}
