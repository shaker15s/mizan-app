package app.mizan.domain.security

import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.TimeSource
import java.time.Duration
import java.time.Instant

enum class AuthMethod {
    BIOMETRIC,
    DEVICE_CREDENTIAL,
    /** Demo only. Production policy rejects this method. */
    SIMULATED,
}

data class AuthProof(
    val proofId: String,
    val actorId: ActorId,
    val tenantId: TenantId,
    val operationId: String,
    val method: AuthMethod,
    val authenticatedAt: Instant,
)

enum class Freshness {
    FRESH,
    MISSING,
    EXPIRED,
    WRONG_ACTOR,
    WRONG_TENANT,
    WRONG_OPERATION,
    SIMULATED_NOT_ACCEPTED,
}

class ReauthenticationPolicy(
    private val time: TimeSource,
    private val acceptSimulated: Boolean,
) {
    fun check(
        proof: AuthProof?,
        operationId: String,
        actorId: ActorId,
        tenantId: TenantId,
        approval: ApprovalLevel,
    ): Freshness {
        if (approval == ApprovalLevel.L0_NONE) return Freshness.FRESH
        if (proof == null) return Freshness.MISSING
        if (proof.method == AuthMethod.SIMULATED && !acceptSimulated) return Freshness.SIMULATED_NOT_ACCEPTED
        if (proof.actorId != actorId) return Freshness.WRONG_ACTOR
        if (proof.tenantId != tenantId) return Freshness.WRONG_TENANT
        if (proof.operationId != operationId) return Freshness.WRONG_OPERATION
        val age = Duration.between(proof.authenticatedAt, time.now())
        val window = window(approval)
        return if (age.isNegative || age <= window) Freshness.FRESH else Freshness.EXPIRED
    }

    fun window(approval: ApprovalLevel): Duration = when (approval) {
        ApprovalLevel.L0_NONE -> Duration.ofMinutes(15)
        ApprovalLevel.L1_USER_CONFIRMATION -> Duration.ofMinutes(15)
        ApprovalLevel.L2_PRIVILEGED -> Duration.ofMinutes(3)
        else -> Duration.ofSeconds(60)
    }
}
