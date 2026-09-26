package app.mizan.domain.model

import java.security.MessageDigest

/**
 * Deterministic values for idempotency and audit. Object keys are sorted.
 * Numbers are decimal strings, never floating point.
 */
sealed interface CanonicalValue {
    data class Str(val value: String) : CanonicalValue
    data class Num(val value: String) : CanonicalValue
    data class Bool(val value: Boolean) : CanonicalValue
    data class Obj(val fields: List<Pair<String, CanonicalValue>>) : CanonicalValue
    data class Arr(val items: List<CanonicalValue>) : CanonicalValue
}

object CanonicalJson {
    fun write(value: CanonicalValue): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: CanonicalValue) {
        when (value) {
            is CanonicalValue.Str -> appendString(value.value)
            is CanonicalValue.Num -> append(value.value)
            is CanonicalValue.Bool -> append(if (value.value) "true" else "false")
            is CanonicalValue.Obj -> {
                append('{')
                value.fields.sortedBy { it.first }.forEachIndexed { index, (key, child) ->
                    if (index > 0) append(',')
                    appendString(key)
                    append(':')
                    appendValue(child)
                }
                append('}')
            }
            is CanonicalValue.Arr -> {
                append('[')
                value.items.forEachIndexed { index, child ->
                    if (index > 0) append(',')
                    appendValue(child)
                }
                append(']')
            }
        }
    }

    private fun StringBuilder.appendString(raw: String) {
        append('"')
        raw.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                0x0C.toChar() -> append("\\f")
                else -> if (ch < 0x20.toChar()) {
                    // Every other control character must be escaped or the
                    // result is not JSON and the hash is not reproducible.
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }
}

object Digests {
    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

object Idempotency {
    fun key(tenantId: TenantId, tool: ToolName, args: ToolArgs): IdempotencyKey {
        require(args.tool == tool) { "arguments do not match tool" }
        val material = listOf(
            tenantId.value,
            tool.wire,
            tool.version,
            CanonicalJson.write(args.canonical()),
        ).joinToString("\n")
        return IdempotencyKey(Digests.sha256(material))
    }
}
