package app.mizan.domain

import app.mizan.domain.error.DispatchState
import app.mizan.domain.execution.ExecutionEvent
import app.mizan.domain.execution.HumanMatch
import app.mizan.domain.execution.MatchDecision
import app.mizan.domain.execution.ReconciliationPolicy
import app.mizan.domain.execution.ExecutionPhase
import app.mizan.domain.execution.ExecutionRecovery
import app.mizan.domain.execution.ExecutionStateMachine
import app.mizan.domain.execution.IdempotencyClaim
import app.mizan.domain.execution.IdempotencyDecision
import app.mizan.domain.execution.IdempotencyGuard
import app.mizan.domain.execution.RecoverableExecution
import app.mizan.domain.execution.Transition
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.CreateDraftOrderArgs
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.Idempotency
import app.mizan.domain.model.IdempotencyKey
import app.mizan.domain.model.Money
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.TimeSource
import app.mizan.domain.model.ToolName
import app.mizan.domain.security.AuthMethod as SecurityMethod
import app.mizan.domain.security.AuthProof
import app.mizan.domain.security.Freshness
import app.mizan.domain.security.ReauthenticationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ExecutionInvariantsTest {
    private val machine = ExecutionStateMachine()
    private val guard = IdempotencyGuard()

    @Test
    fun happyPathReachesVerified() {
        var phase = ExecutionPhase.PROPOSED
        val events = listOf(
            ExecutionEvent.Validate,
            ExecutionEvent.RiskEvaluated,
            ExecutionEvent.RequestApproval,
            ExecutionEvent.Authorize,
            ExecutionEvent.AcquireLease,
            ExecutionEvent.StartExecution,
            ExecutionEvent.ErpAccepted,
            ExecutionEvent.StartVerification,
            ExecutionEvent.Verify,
        )
        events.forEach { event ->
            val next = machine.next(phase, event)
            assertTrue(next is Transition.Allowed)
            phase = (next as Transition.Allowed).phase
        }
        assertEquals(ExecutionPhase.VERIFIED, phase)
    }

    @Test
    fun timeoutAfterSendIsAmbiguousNotFailed() {
        val sent = machine.next(
            ExecutionPhase.EXECUTING,
            ExecutionEvent.TimedOut(DispatchState.SENT),
        ) as Transition.Allowed
        val notSent = machine.next(
            ExecutionPhase.EXECUTING,
            ExecutionEvent.TimedOut(DispatchState.NOT_SENT),
        ) as Transition.Allowed
        assertEquals(ExecutionPhase.AMBIGUOUS, sent.phase)
        assertEquals(ExecutionPhase.ERP_FAILURE, notSent.phase)
    }

    @Test
    fun verifiedCannotBeReexecutedByTransition() {
        val again = machine.next(ExecutionPhase.VERIFIED, ExecutionEvent.StartExecution)
        assertTrue(again is Transition.Illegal)
    }

    @Test
    fun ambiguousClaimBlocksRetry() {
        val claim = IdempotencyClaim(
            IdempotencyKey("k"),
            ExecutionId("EXE-1"),
            ExecutionPhase.AMBIGUOUS,
            dispatchSent = true,
        )
        val decision = guard.decide(claim)
        assertTrue(decision is IdempotencyDecision.Blocked)
    }

    @Test
    fun verifiedClaimReplays() {
        val decision = guard.decide(
            IdempotencyClaim(IdempotencyKey("k"), ExecutionId("EXE-1"), ExecutionPhase.VERIFIED, true),
        )
        assertTrue(decision is IdempotencyDecision.Replay)
    }

    @Test
    fun sameCanonicalArgsSameKeyDifferentTenantDifferentKey() {
        val args = CreateDraftOrderArgs("Cairo Tech", Money(1_500_000, "EGP"), "2 servers")
        val a = Idempotency.key(TenantId("a"), ToolName.CREATE_DRAFT_ORDER, args)
        val a2 = Idempotency.key(TenantId("a"), ToolName.CREATE_DRAFT_ORDER, args)
        val b = Idempotency.key(TenantId("b"), ToolName.CREATE_DRAFT_ORDER, args)
        assertEquals(a, a2)
        assertNotEquals(a, b)
    }

    @Test
    fun recoveryDoesNotRetryUnknownDispatch() {
        val recovery = ExecutionRecovery()
        val action = recovery.decide(
            RecoverableExecution(
                ExecutionPhase.EXECUTING,
                DispatchState.UNKNOWN,
                Instant.parse("2026-01-01T00:00:00Z"),
            ),
            Instant.parse("2026-01-01T00:01:00Z"),
        )
        assertTrue(action is app.mizan.domain.execution.RecoveryAction.MoveTo)
        assertEquals(
            ExecutionPhase.RECONCILIATION_REQUIRED,
            (action as app.mizan.domain.execution.RecoveryAction.MoveTo).phase,
        )
    }

    @Test
    fun proofMustMatchOperationAndExpire() {
        val now = Instant.parse("2026-09-23T12:00:00Z")
        val policy = ReauthenticationPolicy(TimeSource { now }, acceptSimulated = false)
        val actor = ActorId("MGR-01")
        val tenant = TenantId("tenant-a")
        val fresh = AuthProof("p", actor, tenant, "EXE-1", SecurityMethod.BIOMETRIC, now.minusSeconds(10))
        assertEquals(
            Freshness.FRESH,
            policy.check(fresh, "EXE-1", actor, tenant, ApprovalLevel.L3_MANAGER),
        )
        val stale = fresh.copy(authenticatedAt = now.minusSeconds(120))
        assertEquals(
            Freshness.EXPIRED,
            policy.check(stale, "EXE-1", actor, tenant, ApprovalLevel.L3_MANAGER),
        )
        assertEquals(
            Freshness.WRONG_OPERATION,
            policy.check(fresh, "EXE-2", actor, tenant, ApprovalLevel.L3_MANAGER),
        )
        val simulated = fresh.copy(method = SecurityMethod.SIMULATED)
        assertEquals(
            Freshness.SIMULATED_NOT_ACCEPTED,
            policy.check(simulated, "EXE-1", actor, tenant, ApprovalLevel.L3_MANAGER),
        )
    }
}
