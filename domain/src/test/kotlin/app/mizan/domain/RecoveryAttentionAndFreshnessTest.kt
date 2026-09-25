package app.mizan.domain

import app.mizan.domain.attention.AttentionKind
import app.mizan.domain.attention.AttentionPlanner
import app.mizan.domain.error.DispatchState
import app.mizan.domain.execution.ExecutionRecovery
import app.mizan.domain.execution.ExecutionPhase
import app.mizan.domain.execution.RecoverableExecution
import app.mizan.domain.execution.RecoveryAction
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.ExecutionRecord
import app.mizan.domain.model.HealthStatus
import app.mizan.domain.model.IdempotencyKey
import app.mizan.domain.model.ProposalId
import app.mizan.domain.model.ReconciliationCase
import app.mizan.domain.model.ReconciliationStatus
import app.mizan.domain.model.RiskTier
import app.mizan.domain.model.SyncSnapshot
import app.mizan.domain.model.SyncState
import app.mizan.domain.model.SystemHealth
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.TimeSource
import app.mizan.domain.model.ToolName
import app.mizan.domain.model.TraceId
import app.mizan.domain.risk.RiskEvaluator
import app.mizan.domain.risk.RiskInput
import app.mizan.domain.security.AuthMethod
import app.mizan.domain.security.AuthProof
import app.mizan.domain.security.Freshness
import app.mizan.domain.security.ReauthenticationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Process death, the attention list, and proof freshness are the three places
 * where a wrong default silently creates risk.
 */
class RecoveryAttentionAndFreshnessTest {

    private val now = Instant.parse("2026-09-25T12:00:00Z")
    private val recovery = ExecutionRecovery()
    private val planner = AttentionPlanner()

    private fun record(phase: ExecutionPhase, id: String = "EXE-1"): ExecutionRecord = ExecutionRecord(
        id = ExecutionId(id),
        traceId = TraceId("TRC-1"),
        proposalId = ProposalId("PRP-1"),
        tenantId = TenantId("tenant-a"),
        initiatorId = ActorId("USR-1"),
        tool = ToolName.CREATE_DRAFT_ORDER,
        toolVersion = "2.1.0",
        intent = "intent",
        phase = phase,
        idempotencyKey = IdempotencyKey("key-1"),
        canonicalArgs = "{}",
        amount = null,
        approval = app.mizan.domain.model.ApprovalLevel.L2_PRIVILEGED,
        riskTier = RiskTier.R2_MEDIUM,
        policyRuleId = "POL-1",
        approverIds = emptyList(),
        erpRecordId = null,
        erpModel = null,
        dispatch = DispatchState.SENT,
        leaseExpiresAt = null,
        createdAt = now,
        updatedAt = now,
        errorCode = null,
        origin = app.mizan.domain.model.EvidenceOrigin.SERVICE,
    )

    private fun openCase(id: String) = ReconciliationCase(
        id = id,
        executionId = ExecutionId("EXE-1"),
        traceId = TraceId("TRC-1"),
        tenantId = TenantId("tenant-a"),
        tool = ToolName.CREATE_DRAFT_ORDER,
        intent = "intent",
        idempotencyKey = IdempotencyKey("key-1"),
        candidateRecordIds = emptyList(),
        status = ReconciliationStatus.OPEN,
        notes = null,
        openedAt = now,
    )

    @Test
    fun anExpiredLeaseNeverResendsAWrite() {
        val sent = recovery.decide(
            RecoverableExecution(ExecutionPhase.EXECUTING, DispatchState.SENT, leaseExpiresAt = now.minusSeconds(1)),
            now,
        )
        assertEquals(
            RecoveryAction.MoveTo(ExecutionPhase.RECONCILIATION_REQUIRED, "RECOVERY_UNCERTAIN"),
            sent,
        )
        val notSent = recovery.decide(
            RecoverableExecution(ExecutionPhase.EXECUTING, DispatchState.NOT_SENT, leaseExpiresAt = now.minusSeconds(1)),
            now,
        )
        assertEquals(
            RecoveryAction.MoveTo(ExecutionPhase.ERP_FAILURE, "RECOVERY_NOT_SENT"),
            notSent,
        )
    }

    @Test
    fun unknownDispatchIsTreatedAsPossiblySent() {
        val unknown = recovery.decide(
            RecoverableExecution(ExecutionPhase.VERIFICATION_PENDING, DispatchState.UNKNOWN, leaseExpiresAt = null),
            now,
        )
        assertEquals(
            RecoveryAction.MoveTo(ExecutionPhase.RECONCILIATION_REQUIRED, "RECOVERY_UNCERTAIN"),
            unknown,
        )
    }

    @Test
    fun aLiveLeaseAndATerminalExecutionAreLeftAlone() {
        val live = recovery.decide(
            RecoverableExecution(ExecutionPhase.EXECUTING, DispatchState.SENT, leaseExpiresAt = now.plusSeconds(30)),
            now,
        )
        assertEquals(RecoveryAction.None, live)
        val verified = recovery.decide(
            RecoverableExecution(ExecutionPhase.VERIFIED, DispatchState.SENT, leaseExpiresAt = now.minusSeconds(30)),
            now,
        )
        assertEquals(RecoveryAction.None, verified)
    }

    @Test
    fun attentionPutsOpenReconciliationFirstAndCapsFailures() {
        val executions = listOf(
            record(ExecutionPhase.AWAITING_APPROVAL, "EXE-A"),
            record(ExecutionPhase.ERP_FAILURE, "EXE-F1"),
            record(ExecutionPhase.ERP_FAILURE, "EXE-F2"),
            record(ExecutionPhase.ERP_FAILURE, "EXE-F3"),
            record(ExecutionPhase.ERP_FAILURE, "EXE-F4"),
        )
        val plan = planner.plan(
            executions = executions,
            cases = listOf(openCase("CASE-1")),
            health = SystemHealth.unknown.copy(erp = HealthStatus.UNAVAILABLE),
            sync = SyncSnapshot(
                tenantId = TenantId("tenant-a"),
                lastSuccessfulSync = null,
                lastAttempt = null,
                state = SyncState.STALE,
                pendingChanges = 0,
                failedChanges = 0,
                conflicts = 0,
                serverCursor = null,
            ),
        )
        assertEquals(AttentionKind.RECONCILIATION, plan.first().kind)
        assertEquals(AttentionKind.APPROVAL, plan[1].kind)
        assertEquals(3, plan.count { it.kind == AttentionKind.FAILURE })
        assertTrue(plan.any { it.kind == AttentionKind.ERP_UNAVAILABLE })
        assertTrue(plan.any { it.kind == AttentionKind.SYNC_STALE })
        // The backend is unknown and the session is not proven healthy.
        assertTrue(plan.any { it.kind == AttentionKind.BACKEND_UNKNOWN })
        val ranks = plan.map { it.rank }
        assertEquals(ranks.sorted(), ranks)
    }

    @Test
    fun aHealthyBackendDoesNotAskForAttention() {
        val plan = planner.plan(
            executions = emptyList(),
            cases = emptyList(),
            health = SystemHealth.unknown.copy(backend = HealthStatus.HEALTHY, authentication = HealthStatus.HEALTHY),
            sync = null,
        )
        assertTrue(plan.none { it.kind == AttentionKind.BACKEND_UNKNOWN })
    }

    @Test
    fun proofFreshnessShrinksAsApprovalRises() {
        val policy = ReauthenticationPolicy(TimeSource { now }, acceptSimulated = true)
        assertEquals(Duration.ofMinutes(15), policy.window(app.mizan.domain.model.ApprovalLevel.L1_USER_CONFIRMATION))
        assertEquals(Duration.ofMinutes(3), policy.window(app.mizan.domain.model.ApprovalLevel.L2_PRIVILEGED))
        assertEquals(Duration.ofSeconds(60), policy.window(app.mizan.domain.model.ApprovalLevel.L4_DUAL))

        val proof = AuthProof(
            proofId = "PRF-1",
            actorId = ActorId("USR-1"),
            tenantId = TenantId("tenant-a"),
            operationId = "OP-1",
            method = AuthMethod.BIOMETRIC,
            authenticatedAt = now.minusSeconds(30),
        )
        assertEquals(
            Freshness.FRESH,
            policy.check(proof, "OP-1", ActorId("USR-1"), TenantId("tenant-a"), app.mizan.domain.model.ApprovalLevel.L4_DUAL),
        )
        assertEquals(Freshness.WRONG_OPERATION, policy.check(proof, "OP-2", ActorId("USR-1"), TenantId("tenant-a"), app.mizan.domain.model.ApprovalLevel.L4_DUAL))
        assertEquals(Freshness.WRONG_ACTOR, policy.check(proof, "OP-1", ActorId("USR-9"), TenantId("tenant-a"), app.mizan.domain.model.ApprovalLevel.L4_DUAL))
        assertEquals(Freshness.WRONG_TENANT, policy.check(proof, "OP-1", ActorId("USR-1"), TenantId("tenant-b"), app.mizan.domain.model.ApprovalLevel.L4_DUAL))
        assertEquals(Freshness.MISSING, policy.check(null, "OP-1", ActorId("USR-1"), TenantId("tenant-a"), app.mizan.domain.model.ApprovalLevel.L4_DUAL))
        // A read needs no proof, and a simulated proof is refused outside demo.
        assertEquals(Freshness.FRESH, policy.check(null, "OP-1", ActorId("USR-1"), TenantId("tenant-a"), app.mizan.domain.model.ApprovalLevel.L0_NONE))
        val strict = ReauthenticationPolicy(TimeSource { now }, acceptSimulated = false)
        assertEquals(
            Freshness.SIMULATED_NOT_ACCEPTED,
            strict.check(proof.copy(method = AuthMethod.SIMULATED), "OP-1", ActorId("USR-1"), TenantId("tenant-a"), app.mizan.domain.model.ApprovalLevel.L2_PRIVILEGED),
        )
    }

    @Test
    fun anExpiredProofIsNotFresh() {
        val policy = ReauthenticationPolicy(TimeSource { now }, acceptSimulated = true)
        val stale = AuthProof(
            proofId = "PRF-2",
            actorId = ActorId("USR-1"),
            tenantId = TenantId("tenant-a"),
            operationId = "OP-1",
            method = AuthMethod.BIOMETRIC,
            authenticatedAt = now.minusSeconds(61),
        )
        assertEquals(
            Freshness.EXPIRED,
            policy.check(stale, "OP-1", ActorId("USR-1"), TenantId("tenant-a"), app.mizan.domain.model.ApprovalLevel.L3_MANAGER),
        )
    }

    @Test
    fun riskIsAClassificationAndInjectionIsCritical() {
        val evaluator = RiskEvaluator()
        val clean = evaluator.assess(
            RiskInput(
                tool = ToolName.STOCK_AVAILABILITY,
                destructive = false,
                amountTier = RiskTier.R0_READ,
                ambiguous = false,
                injectionSuspected = false,
                customerNamed = true,
                externalUncertain = false,
                sensitiveData = false,
            ),
        )
        assertEquals(RiskTier.R0_READ, clean.tier)
        assertTrue(clean.factors.isEmpty())

        val injected = evaluator.assess(
            RiskInput(
                tool = ToolName.CREATE_DRAFT_ORDER,
                destructive = false,
                amountTier = RiskTier.R1_LOW,
                ambiguous = true,
                injectionSuspected = true,
                customerNamed = false,
                externalUncertain = true,
                sensitiveData = false,
            ),
        )
        assertEquals(RiskTier.R4_CRITICAL, injected.tier)
        assertTrue(injected.factors.any { it.code == "INJECTION_SUSPECTED" })
        assertTrue(injected.factors.any { it.code == "CUSTOMER_UNSPECIFIED" })

        val payment = evaluator.assess(
            RiskInput(
                tool = ToolName.REGISTER_PAYMENT,
                destructive = false,
                amountTier = RiskTier.R1_LOW,
                ambiguous = false,
                injectionSuspected = false,
                customerNamed = true,
                externalUncertain = false,
                sensitiveData = true,
            ),
        )
        assertEquals(RiskTier.R3_HIGH, payment.tier)
    }
}
