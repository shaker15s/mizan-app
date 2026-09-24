package app.mizan.domain.model

import app.mizan.domain.audit.IntegrityClass
import app.mizan.domain.error.DispatchState
import app.mizan.domain.execution.ExecutionPhase
import app.mizan.domain.policy.PolicyDecision
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
)

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
)

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
