package app.mizan.domain.model

/**
 * Stable identifiers. Values are opaque. They are not authorization.
 */
@JvmInline
value class TenantId(val value: String) {
    init {
        require(value.isNotBlank()) { "tenant id is required" }
    }
}

@JvmInline
value class ActorId(val value: String) {
    init {
        require(value.isNotBlank()) { "actor id is required" }
    }
}

@JvmInline
value class TraceId(val value: String) {
    init {
        require(value.isNotBlank()) { "trace id is required" }
    }
}

@JvmInline
value class ExecutionId(val value: String) {
    init {
        require(value.isNotBlank()) { "execution id is required" }
    }
}

@JvmInline
value class ProposalId(val value: String) {
    init {
        require(value.isNotBlank()) { "proposal id is required" }
    }
}

@JvmInline
value class ReceiptId(val value: String) {
    init {
        require(value.isNotBlank()) { "receipt id is required" }
    }
}

@JvmInline
value class IdempotencyKey(val value: String) {
    init {
        require(value.isNotBlank()) { "idempotency key is required" }
    }
}

enum class Role {
    OPERATOR,
    SALES_REP,
    SALES_MANAGER,
    FINANCE_APPROVER,
    AUDITOR,
}

data class Actor(
    val id: ActorId,
    val displayName: String,
    val role: Role,
    val tenantId: TenantId,
)

/**
 * What the device knows about a workspace. Not a proof of ERP health.
 */
data class TenantContext(
    val id: TenantId,
    val displayName: String,
    val erpLabel: String,
    val environmentLabel: String,
)

enum class SessionMode {
    /** Local simulator. Every result must be labeled as simulation. */
    SIMULATION,

    /** Short-lived session against the Wakeel service. */
    REMOTE,
}
