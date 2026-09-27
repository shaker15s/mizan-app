package app.mizan.domain.error

/**
 * The public failure contract.
 *
 * A failure is not a string. Every refusal this system can produce has a stable
 * code, a family, an HTTP status, a retry policy that a client can follow
 * without guessing, and a message key that the app resolves in the reader's
 * language.
 *
 * The rule that matters most: a write that may have reached the ERP is never
 * retryable. It is [RetryClass.RECONCILE].
 */
enum class ErrorFamily(val prefix: String) {
    AUTH("AUTH"),
    TENANT("TENANT"),
    INPUT("INPUT"),
    POLICY("POLICY"),
    APPROVAL("APPROVAL"),
    EXECUTION("EXECUTION"),
    ERP("ERP"),
    NETWORK("NETWORK"),
    VERIFICATION("VERIFICATION"),
    RECONCILIATION("RECONCILIATION"),
    SECURITY("SECURITY"),
    SYSTEM("SYSTEM"),
    ;

    companion object {
        fun of(code: String): ErrorFamily? =
            entries.firstOrNull { code == it.prefix || code.startsWith(it.prefix + "_") }
    }
}

/**
 * What a caller may do next. `RETRY_AFTER_BACKOFF` and `RETRY_SAME_REQUEST`
 * are only ever issued for reads; a write comes back as [RECONCILE],
 * [REPLAY_ORIGINAL] or [NEVER].
 */
enum class RetryClass {
    RETRY_SAME_REQUEST,
    RETRY_AFTER_BACKOFF,
    REAUTHENTICATE,
    REPLAY_ORIGINAL,
    RECONCILE,
    NEVER,
}

enum class OperationKind { READ, WRITE }

data class MizanError(
    val code: String,
    val family: ErrorFamily,
    val httpStatus: Int,
    val retry: RetryClass,
    val severity: AppError.Severity,
    val recoverable: Boolean,
    /** Message key resolved by the app. Never prose. */
    val messageKey: String,
    /** For engineers, already free of personal data. */
    val developerHint: String,
)

object ErrorTaxonomy {

    private fun error(
        code: String,
        httpStatus: Int,
        retry: RetryClass,
        severity: AppError.Severity = AppError.Severity.WARNING,
        recoverable: Boolean = true,
        hint: String = "",
    ) = MizanError(
        code = code,
        family = ErrorFamily.of(code) ?: ErrorFamily.SYSTEM,
        httpStatus = httpStatus,
        retry = retry,
        severity = severity,
        recoverable = recoverable,
        messageKey = "error_" + code.lowercase(),
        developerHint = hint,
    )

    val catalog: List<MizanError> = listOf(
        // Authentication and session
        error("AUTH_INVALID_CREDENTIALS", 401, RetryClass.NEVER, AppError.Severity.SECURITY),
        error("AUTH_SESSION_EXPIRED", 401, RetryClass.REAUTHENTICATE, AppError.Severity.SECURITY),
        error("AUTH_TOKEN_UNKNOWN", 401, RetryClass.REAUTHENTICATE, AppError.Severity.SECURITY),
        error("AUTH_ACCOUNT_LOCKED", 423, RetryClass.NEVER, AppError.Severity.SECURITY, recoverable = false),
        error("AUTH_PROOF_MISSING", 401, RetryClass.REAUTHENTICATE, AppError.Severity.SECURITY),
        error("AUTH_PROOF_EXPIRED", 401, RetryClass.REAUTHENTICATE, AppError.Severity.SECURITY),
        error("AUTH_PROOF_WRONG_OPERATION", 401, RetryClass.REAUTHENTICATE, AppError.Severity.SECURITY),
        error("AUTH_PROOF_WRONG_ACTOR", 401, RetryClass.REAUTHENTICATE, AppError.Severity.SECURITY),
        // Tenant
        error("TENANT_MISMATCH", 403, RetryClass.NEVER, AppError.Severity.SECURITY, recoverable = false),
        error("TENANT_REQUIRED", 400, RetryClass.NEVER),
        // Input
        error("INPUT_MISSING_FIELD", 422, RetryClass.NEVER),
        error("INPUT_MALFORMED", 400, RetryClass.NEVER),
        error("INPUT_TOO_LARGE", 413, RetryClass.NEVER),
        error("INPUT_AMBIGUOUS", 422, RetryClass.NEVER),
        error("INPUT_NEGATIVE_AMOUNT", 422, RetryClass.NEVER),
        error("INPUT_UNKNOWN_TOOL", 422, RetryClass.NEVER, recoverable = false),
        // Policy
        error("POLICY_DENIED", 422, RetryClass.NEVER, AppError.Severity.WARNING, recoverable = false),
        error("POLICY_VERSION_CHANGED", 409, RetryClass.NEVER, hint = "re-evaluate the proposal"),
        error("POLICY_CURRENCY_UNCONFIGURED", 422, RetryClass.NEVER),
        // Approval
        error("APPROVAL_REQUIRED", 409, RetryClass.NEVER),
        error("APPROVAL_INVALIDATED", 409, RetryClass.NEVER, hint = "the proposal changed after approval"),
        error("APPROVAL_EXPIRED", 409, RetryClass.NEVER),
        error("APPROVAL_ROLE_INSUFFICIENT", 403, RetryClass.NEVER, recoverable = false),
        error("APPROVAL_SELF_NOT_ALLOWED", 403, RetryClass.NEVER, recoverable = false),
        error("APPROVAL_SECOND_REQUIRED", 409, RetryClass.NEVER),
        error("APPROVAL_UNKNOWN_APPROVER", 422, RetryClass.NEVER),
        // Execution
        error("EXECUTION_IN_PROGRESS", 409, RetryClass.REPLAY_ORIGINAL),
        error("EXECUTION_IDEMPOTENCY_KEY_REUSE", 409, RetryClass.NEVER),
        error("EXECUTION_ALREADY_RESOLVED", 409, RetryClass.REPLAY_ORIGINAL),
        error("EXECUTION_CANCELLED", 409, RetryClass.NEVER),
        error("EXECUTION_ILLEGAL_TRANSITION", 409, RetryClass.NEVER, recoverable = false),
        // ERP
        error("ERP_UNAVAILABLE", 503, RetryClass.RETRY_AFTER_BACKOFF, AppError.Severity.ERROR),
        error("ERP_REJECTED", 422, RetryClass.NEVER, AppError.Severity.ERROR),
        error("ERP_AUTH_REJECTED", 502, RetryClass.NEVER, AppError.Severity.SECURITY),
        error("ERP_RATE_LIMITED", 503, RetryClass.RETRY_AFTER_BACKOFF, AppError.Severity.ERROR),
        error("ERP_RECORD_NOT_FOUND", 404, RetryClass.NEVER, AppError.Severity.ERROR),
        error("ERP_SCHEMA_DRIFT", 502, RetryClass.NEVER, AppError.Severity.ERROR, recoverable = false),
        error("ERP_MALFORMED_RESPONSE", 502, RetryClass.NEVER, AppError.Severity.ERROR),
        error("ERP_NOT_SUPPORTED", 422, RetryClass.NEVER, AppError.Severity.WARNING, recoverable = false),
        // Network
        error("NETWORK_UNREACHABLE", 503, RetryClass.RETRY_AFTER_BACKOFF, AppError.Severity.ERROR),
        error("NETWORK_TIMEOUT", 504, RetryClass.RECONCILE, AppError.Severity.ERROR,
            hint = "a write whose dispatch is unknown is never retried"),
        // Verification
        error("VERIFICATION_PENDING", 202, RetryClass.RETRY_AFTER_BACKOFF),
        error("VERIFICATION_FAILED", 502, RetryClass.RECONCILE, AppError.Severity.ERROR),
        error("VERIFICATION_FIELD_MISMATCH", 409, RetryClass.RECONCILE, AppError.Severity.ERROR),
        // Reconciliation
        error("RECONCILIATION_REQUIRED", 409, RetryClass.RECONCILE),
        error("RECONCILIATION_CANDIDATE_REQUIRED", 422, RetryClass.NEVER),
        error("RECONCILIATION_NOTE_REQUIRED", 422, RetryClass.NEVER),
        error("RECONCILIATION_ALREADY_CLOSED", 409, RetryClass.NEVER),
        // Security
        error("SECURITY_DEVICE_NOT_ENROLLED", 403, RetryClass.NEVER, AppError.Severity.SECURITY, recoverable = false),
        error("SECURITY_DEVICE_REVOKED", 403, RetryClass.NEVER, AppError.Severity.SECURITY, recoverable = false),
        error("SECURITY_SIGNATURE_INVALID", 403, RetryClass.NEVER, AppError.Severity.SECURITY, recoverable = false),
        error("SECURITY_CHALLENGE_REPLAYED", 403, RetryClass.NEVER, AppError.Severity.SECURITY, recoverable = false),
        error("SECURITY_CHALLENGE_EXPIRED", 403, RetryClass.REAUTHENTICATE, AppError.Severity.SECURITY),
        error("SECURITY_RATE_LIMITED", 429, RetryClass.RETRY_AFTER_BACKOFF, AppError.Severity.SECURITY),
        error("SECURITY_INJECTION_SUSPECTED", 422, RetryClass.NEVER, AppError.Severity.SECURITY, recoverable = false),
        // System
        error("SYSTEM_INTERNAL", 500, RetryClass.NEVER, AppError.Severity.ERROR),
        error("SYSTEM_UNAVAILABLE", 503, RetryClass.RETRY_AFTER_BACKOFF, AppError.Severity.ERROR),
        error("SYSTEM_DUPLICATE_REQUEST", 409, RetryClass.REPLAY_ORIGINAL),
    )

    private val byCode: Map<String, MizanError> = catalog.associateBy { it.code }

    fun find(code: String): MizanError? = byCode[code]

    /**
     * Unknown codes are placed in their family by prefix and treated as
     * non-retryable. A code this build does not know must never be retried
     * blindly, and must never be read as a success.
     */
    fun of(code: String): MizanError = byCode[code] ?: MizanError(
        code = code,
        family = ErrorFamily.of(code) ?: ErrorFamily.SYSTEM,
        httpStatus = 500,
        retry = RetryClass.NEVER,
        severity = AppError.Severity.ERROR,
        recoverable = false,
        messageKey = "error_unknown",
        developerHint = "code not in this build's catalogue",
    )

    /**
     * The retry a caller is allowed to perform for this kind of operation.
     * A write can only ever replay an idempotent original or reconcile.
     */
    fun retryFor(kind: OperationKind, error: MizanError): RetryClass = when {
        error.retry == RetryClass.RETRY_SAME_REQUEST && kind == OperationKind.WRITE -> RetryClass.RECONCILE
        error.retry == RetryClass.RETRY_AFTER_BACKOFF && kind == OperationKind.WRITE -> RetryClass.RECONCILE
        else -> error.retry
    }

    /** Maps a domain failure onto the public contract. */
    fun fromAppError(error: AppError): MizanError = when (error) {
        is AppError.Network -> of("NETWORK_UNREACHABLE")
        is AppError.Timeout ->
            if (error.retryable) of("NETWORK_UNREACHABLE") else of("NETWORK_TIMEOUT")
        is AppError.Authentication -> of("AUTH_SESSION_EXPIRED")
        is AppError.Authorization -> of("POLICY_DENIED")
        is AppError.Policy -> of("POLICY_DENIED")
        is AppError.Erp -> of("ERP_REJECTED")
        is AppError.Validation -> of("INPUT_MISSING_FIELD")
        is AppError.Conflict -> of("SYSTEM_DUPLICATE_REQUEST")
        is AppError.Ambiguous -> of("RECONCILIATION_REQUIRED")
        else -> find(error.code) ?: of(error.code).copy(
            severity = error.severity,
            recoverable = error.retryable,
        )
    }

    /** Every message a person can be shown, for the app's string checks. */
    fun messageKeys(): List<String> = catalog.map { it.messageKey }.distinct()
}
