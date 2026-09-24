package app.mizan.domain.error

/**
 * Typed failure. [code] is stable and mapped to localized copy in the app.
 * [diagnostic] is for engineers and must already be redacted.
 */
sealed class AppError(
    val code: String,
    val retryable: Boolean,
    val severity: Severity,
    val diagnostic: String,
) {
    enum class Severity { INFO, WARNING, ERROR, SECURITY }

    class Validation(code: String, diagnostic: String, val field: String? = null) :
        AppError(code, retryable = false, Severity.WARNING, diagnostic)

    class Authentication(code: String, diagnostic: String) :
        AppError(code, retryable = false, Severity.SECURITY, diagnostic)

    class Authorization(code: String, diagnostic: String) :
        AppError(code, retryable = false, Severity.SECURITY, diagnostic)

    class Network(code: String, diagnostic: String, retryable: Boolean = true) :
        AppError(code, retryable, Severity.ERROR, diagnostic)

    class Timeout(code: String, diagnostic: String, val dispatch: DispatchState) :
        AppError(code, retryable = dispatch == DispatchState.NOT_SENT, Severity.ERROR, diagnostic)

    class Erp(code: String, diagnostic: String) :
        AppError(code, retryable = false, Severity.ERROR, diagnostic)

    class Policy(code: String, diagnostic: String) :
        AppError(code, retryable = false, Severity.WARNING, diagnostic)

    class Conflict(code: String, diagnostic: String) :
        AppError(code, retryable = false, Severity.WARNING, diagnostic)

    class Ambiguous(code: String, diagnostic: String) :
        AppError(code, retryable = false, Severity.ERROR, diagnostic)

    class Security(code: String, diagnostic: String) :
        AppError(code, retryable = false, Severity.SECURITY, diagnostic)

    class Serialization(code: String, diagnostic: String) :
        AppError(code, retryable = false, Severity.ERROR, diagnostic)

    class Database(code: String, diagnostic: String) :
        AppError(code, retryable = false, Severity.ERROR, diagnostic)

    class Configuration(code: String, diagnostic: String) :
        AppError(code, retryable = false, Severity.ERROR, diagnostic)

    class Unknown(code: String, diagnostic: String) :
        AppError(code, retryable = false, Severity.ERROR, diagnostic)
}

enum class DispatchState {
    /** The request never left the device. A later attempt may be safe. */
    NOT_SENT,

    /** The request left the device. The ERP may have accepted it. */
    SENT,

    /** Process death or a missing marker. Treat as possibly sent. */
    UNKNOWN,
}
