package app.mizan.domain.model

import app.mizan.domain.audit.IntegrityClass
import app.mizan.domain.error.DispatchState
import app.mizan.domain.execution.ExecutionPhase
import app.mizan.domain.policy.PolicyDecision
import app.mizan.domain.policy.PolicySnapshot
import app.mizan.domain.risk.RiskAssessment
import java.time.Instant

enum class EvidenceOrigin {
    SIMULATION,
    SERVICE,
    LEGACY_LOCAL,
}

enum class VerificationKind {
    NOT_VERIFIED,
    READ_BACK,
    SIMULATED_READ_BACK,
}

data class ExecutionRecord(
    val id: ExecutionId,
    val traceId: TraceId,
    val proposalId: ProposalId,
    val tenantId: TenantId,
    val initiatorId: ActorId,
    val tool: ToolName,
    val toolVersion: String,
    val intent: String,
    val phase: ExecutionPhase,
    val idempotencyKey: IdempotencyKey,
    val canonicalArgs: String,
    val amount: Money?,
    val approval: ApprovalLevel,
    val riskTier: RiskTier,
    val policyRuleId: String,
    val approverIds: List<ActorId>,
    val erpRecordId: String?,
    val erpModel: String?,
    val dispatch: DispatchState,
    val leaseExpiresAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val errorCode: String?,
    val origin: EvidenceOrigin,
)

/**
 * The ERP entity a proposal names, hashed. Rechecked at dispatch time: if the
 * record moved, disappeared, or now means something else, the proposal is
 * revalidated instead of executed against a different entity.
 */
data class EntityRef(
    val recordId: String,
    val canonicalName: String,
    val fingerprint: String,
) {
    companion object {
        fun of(tenantId: TenantId, recordId: String, canonicalName: String): EntityRef = EntityRef(
            recordId = recordId,
            canonicalName = canonicalName,
            fingerprint = Fingerprints.entity(tenantId, recordId, canonicalName),
        )
    }
}

/**
 * A proposal is a first-class object, not a message in a chat log.
 *
 * It carries the arguments, the policy band that governs them, the entity it
 * names, and a fingerprint over all of it. An approval points at that
 * fingerprint, which is what makes "the amount changed after approval" a
 * refusal instead of a surprise.
 */
data class Proposal(
    val id: ProposalId,
    val traceId: TraceId,
    val executionId: ExecutionId,
    val tenantId: TenantId,
    val initiator: Actor,
    val intent: String,
    val args: ToolArgs,
    val amount: Money?,
    val policy: PolicyDecision,
    val risk: RiskAssessment,
    val idempotencyKey: IdempotencyKey,
    val createdAt: Instant,
    val policyIsPreview: Boolean,
    /** Revision of this proposal. A change moves it and invalidates approvals. */
    val revision: Int = 1,
    val policySnapshot: PolicySnapshot? = null,
    val entity: EntityRef? = null,
    val warnings: List<String> = emptyList(),
    val expiresAt: Instant? = null,
    val fingerprint: String = Fingerprints.proposal(
        tenantId = tenantId,
        initiatorId = initiator.id,
        tool = args.tool,
        args = args,
        amount = amount,
        policyRuleId = policy.ruleId,
        approval = policy.approval,
    ),
    val policyFingerprint: String = Fingerprints.policy(policy),
)

data class TrustReceipt(
    val id: ReceiptId,
    val traceId: TraceId,
    val executionId: ExecutionId,
    val tenantId: TenantId,
    val tenantLabel: String,
    val initiatorId: ActorId,
    val initiatorLabel: String,
    val approverIds: List<ActorId>,
    val approverLabels: List<String>,
    val tool: ToolName,
    val toolVersion: String,
    val policyRuleId: String,
    val approval: ApprovalLevel,
    val riskTier: RiskTier,
    val canonicalArgs: String,
    val idempotencyKey: IdempotencyKey,
    val erpRecordId: String?,
    val erpModel: String?,
    val verification: VerificationKind,
    val integrityClass: IntegrityClass,
    val origin: EvidenceOrigin,
    val createdAt: Instant,
    val auditChainIndex: Long?,
    /** The policy band this receipt was issued under. */
    val policyVersionId: String = "",
    /** Hash of the canonical arguments, so the receipt names the exact request. */
    val inputHash: String = "",
    /** Hash of the fields the read-back returned. */
    val verificationHash: String = "",
    /** The proposal fingerprint the approval was bound to. */
    val proposalFingerprint: String = "",
    /**
     * A server signature over the canonical receipt body. Empty means this
     * receipt was not signed by an authority, and the UI must not say it was.
     */
    val signature: String = "",
    val signatureKeyId: String = "",
    val signatureAlgorithm: String = "",
) {
    /** True only when an authority signed these claims. */
    val signedByAuthority: Boolean get() = signature.isNotEmpty() && signatureKeyId.isNotEmpty()
}

enum class ReconciliationStatus {
    OPEN,
    LINKED,
    CLOSED_WITHOUT_LINK,
}

data class ReconciliationCase(
    val id: String,
    val executionId: ExecutionId,
    val traceId: TraceId,
    val tenantId: TenantId,
    val tool: ToolName,
    val intent: String,
    val idempotencyKey: IdempotencyKey,
    val candidateRecordIds: List<String>,
    val status: ReconciliationStatus,
    val notes: String?,
    val openedAt: Instant,
    /**
     * Why the case exists, in the error taxonomy's vocabulary. A case with no
     * reason is not resolvable by a person, because they cannot tell whether
     * the write is suspected to have happened or is known not to have.
     */
    val reasonCode: String? = null,
    /** The ERP record a person matched this case to. */
    val resolvedRecordId: String? = null,
    val resolvedByActorId: String? = null,
    val resolvedAtMillis: Long? = null,
    /** A message key, never prose: the device renders the sentence. */
    val resolutionLabelKey: String? = null,
    val updatedAt: Instant = openedAt,
) {
    val open: Boolean get() = status == ReconciliationStatus.OPEN

    /** How long a person has left this question unanswered. */
    fun ageMillis(nowMillis: Long): Long = (nowMillis - openedAt.toEpochMilli()).coerceAtLeast(0L)

    fun resolve(
        status: ReconciliationStatus,
        actorId: String,
        atMillis: Long,
        recordId: String? = null,
        labelKey: String? = null,
        note: String? = null,
    ): ReconciliationCase = copy(
        status = status,
        resolvedRecordId = recordId,
        resolvedByActorId = actorId,
        resolvedAtMillis = atMillis,
        resolutionLabelKey = labelKey,
        notes = note ?: notes,
        updatedAt = Instant.ofEpochMilli(atMillis),
    )
}

data class CachedOrder(
    val id: String,
    val tenantId: TenantId,
    val customerName: String,
    val amount: Money,
    val status: String,
    val summary: String,
    val origin: EvidenceOrigin,
    val updatedAt: Instant,
)

data class CachedCustomer(
    val id: String,
    val tenantId: TenantId,
    val name: String,
    val creditLimit: Money?,
    val balance: Money?,
    val status: String,
    val origin: EvidenceOrigin,
)

data class CachedStock(
    val sku: String,
    val tenantId: TenantId,
    val name: String,
    val availableQty: Int,
    val reservedQty: Int,
    val unitPrice: Money?,
    val location: String,
    val origin: EvidenceOrigin,
)

enum class HealthStatus { HEALTHY, DEGRADED, UNAVAILABLE, UNKNOWN }

data class SystemHealth(
    val network: HealthStatus,
    val backend: HealthStatus,
    val authentication: HealthStatus,
    val erp: HealthStatus,
    val synchronization: HealthStatus,
    val policy: HealthStatus,
    val execution: HealthStatus,
    val checkedAt: Instant?,
) {
    companion object {
        val unknown = SystemHealth(
            network = HealthStatus.UNKNOWN,
            backend = HealthStatus.UNKNOWN,
            authentication = HealthStatus.UNKNOWN,
            erp = HealthStatus.UNKNOWN,
            synchronization = HealthStatus.UNKNOWN,
            policy = HealthStatus.UNKNOWN,
            execution = HealthStatus.UNKNOWN,
            checkedAt = null,
        )
    }
}

enum class SyncState { NEVER, IDLE, REFRESHING, OFFLINE, FAILED, STALE }

data class SyncSnapshot(
    val tenantId: TenantId,
    val lastSuccessfulSync: Instant?,
    val lastAttempt: Instant?,
    val state: SyncState,
    val pendingChanges: Int,
    val failedChanges: Int,
    val conflicts: Int,
    val serverCursor: String?,
)
