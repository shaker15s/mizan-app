package app.mizan.integration.http

/**
 * Last line of defense before a string reaches a log or a crash report.
 * Prefer not logging the field at all. This catches accidents.
 */
object Redactor {
    private val patterns = listOf(
        Regex("""(?i)(authorization\s*[:=]\s*)(bearer\s+)?\S+"""),
        Regex("""(?i)(bearer\s+)\S+"""),
        Regex("""(?i)(basic\s+)[A-Za-z0-9+/=]{8,}"""),
        // A JWT is a secret even when no field name precedes it.
        Regex("""eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}"""),
        Regex("""(?i)(api[_-]?key|password|secret|token|cookie|apiKeyOrPassword)(["']?\s*[:=]\s*["']?)([^"'\s,}]+)"""),
    )

    fun redact(input: String): String {
        var current = input
        patterns.forEach { pattern ->
            current = pattern.replace(current) { match ->
                val groups = match.groupValues
                when (match.groups.size) {
                    3 -> groups[1] + groups[2].let { if (it.isEmpty()) "" else it } + "[REDACTED]"
                    4 -> groups[1] + groups[2] + "[REDACTED]"
                    else -> "[REDACTED]"
                }
            }
        }
        return current
    }
}

enum class CallKind {
    SAFE_READ,
    CONDITIONAL_WRITE,
    DESTRUCTIVE,
    AMBIGUOUS_RECOVERY,
}

object RetryPolicy {
    fun maxAttempts(kind: CallKind): Int = when (kind) {
        CallKind.SAFE_READ -> 3
        CallKind.CONDITIONAL_WRITE -> 1
        CallKind.DESTRUCTIVE -> 1
        CallKind.AMBIGUOUS_RECOVERY -> 1
    }

    /** True only when another attempt cannot create a second side effect. */
    fun mayRetry(kind: CallKind, attempt: Int, requestWasSent: Boolean, httpStatus: Int?): Boolean {
        if (attempt >= maxAttempts(kind)) return false
        if (kind != CallKind.SAFE_READ) return false
        if (requestWasSent && httpStatus == null) return false
        return httpStatus == null || httpStatus == 429 || httpStatus in 500..599
    }

    fun backoffMillis(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 4)
        return 200L shl shift
    }
}
