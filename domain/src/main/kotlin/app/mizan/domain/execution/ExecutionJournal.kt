package app.mizan.domain.execution

import app.mizan.domain.error.DispatchState
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.IdempotencyKey
import app.mizan.domain.model.ProposalId
import app.mizan.domain.model.RiskTier
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.ToolName
import java.time.Instant

/**
 * The durable shape of an execution.
 *
 * The plan asks for a journal that survives a process death and can be read
 * back by a person who was not there. Every column below exists for one of
 * those two reasons. Nothing here is derived at render time from a boolean.
 */
enum class JournalStage {
    DRAFT,
    UNDERSTANDING,
    CLARIFICATION_REQUIRED,
    PROPOSED,
    WAITING_APPROVAL,
    APPROVED,
    AUTHORIZED,
    DISPATCHING,
    ACCEPTED,
    VERIFYING,
    VERIFIED,

    REJECTED,
    FAILED,
    AMBIGUOUS,
    RECONCILIATION_REQUIRED,
    CANCELLED,
    EXPIRED,
    ;

    val terminal: Boolean
        get() = this == VERIFIED || this == REJECTED || this == FAILED || this == CANCELLED ||
            this == EXPIRED || this == RECONCILIATION_REQUIRED
}

enum class JournalEvent {
    UNDERSTOOD,
    NEEDS_CLARIFICATION,
    CLARIFIED,
    PROPOSED,
    APPROVAL_REQUESTED,
    APPROVED,
    AUTHORIZED,
    DISPATCH_STARTED,
    DISPATCH_ACCEPTED,
    VERIFICATION_STARTED,
    VERIFIED,
    REJECTED,
    FAILED,
    MARK_AMBIGUOUS,
    REQUIRE_RECONCILIATION,
    CANCEL,
    EXPIRE,
    REOPEN,
}

sealed interface JournalTransition {
    data class Allowed(val stage: JournalStage) : JournalTransition
    data class Illegal(val from: JournalStage, val event: JournalEvent) : JournalTransition
}

/**
 * The stage machine from the plan, written down once.
 *
 * Two properties are enforced here rather than by convention:
 *  - there is no path from an uncertain outcome back to a write, so a lost
 *    response cannot become a retry;
 *  - [JournalStage.VERIFIED] is reachable only through [JournalEvent.VERIFIED],
 *    which the caller must emit after a read-back, never after a dispatch.
 */
class JournalStateMachine {

    fun run(from: JournalStage, events: List<JournalEvent>): JournalTransition {
        var stage = from
        for (event in events) {
            when (val step = next(stage, event)) {
                is JournalTransition.Illegal -> return step
                is JournalTransition.Allowed -> stage = step.stage
            }
        }
        return JournalTransition.Allowed(stage)
    }

    fun next(from: JournalStage, event: JournalEvent): JournalTransition {
        if (from.terminal && event != JournalEvent.REOPEN && event != JournalEvent.REQUIRE_RECONCILIATION) {
            return JournalTransition.Illegal(from, event)
        }
        val stage = when (from) {
            JournalStage.DRAFT -> when (event) {
                JournalEvent.UNDERSTOOD -> JournalStage.UNDERSTANDING
                JournalEvent.CANCEL -> JournalStage.CANCELLED
                JournalEvent.EXPIRE -> JournalStage.EXPIRED
                else -> null
            }
            JournalStage.UNDERSTANDING -> when (event) {
                JournalEvent.NEEDS_CLARIFICATION -> JournalStage.CLARIFICATION_REQUIRED
                JournalEvent.PROPOSED -> JournalStage.PROPOSED
                JournalEvent.REJECTED -> JournalStage.REJECTED
                JournalEvent.CANCEL -> JournalStage.CANCELLED
                JournalEvent.EXPIRE -> JournalStage.EXPIRED
                else -> null
            }
            JournalStage.CLARIFICATION_REQUIRED -> when (event) {
                JournalEvent.CLARIFIED, JournalEvent.UNDERSTOOD -> JournalStage.UNDERSTANDING
                JournalEvent.CANCEL -> JournalStage.CANCELLED
                JournalEvent.EXPIRE -> JournalStage.EXPIRED
                else -> null
            }
            JournalStage.PROPOSED -> when (event) {
                JournalEvent.APPROVAL_REQUESTED -> JournalStage.WAITING_APPROVAL
                JournalEvent.AUTHORIZED -> JournalStage.AUTHORIZED
                JournalEvent.REJECTED -> JournalStage.REJECTED
                JournalEvent.CANCEL -> JournalStage.CANCELLED
                JournalEvent.EXPIRE -> JournalStage.EXPIRED
                else -> null
            }
            JournalStage.WAITING_APPROVAL -> when (event) {
                JournalEvent.APPROVED -> JournalStage.APPROVED
                JournalEvent.REJECTED -> JournalStage.REJECTED
                JournalEvent.EXPIRE -> JournalStage.EXPIRED
                JournalEvent.CANCEL -> JournalStage.CANCELLED
                else -> null
            }
            JournalStage.APPROVED -> when (event) {
                JournalEvent.AUTHORIZED -> JournalStage.AUTHORIZED
                JournalEvent.EXPIRE -> JournalStage.EXPIRED
                JournalEvent.CANCEL -> JournalStage.CANCELLED
                else -> null
            }
            JournalStage.AUTHORIZED -> when (event) {
                JournalEvent.DISPATCH_STARTED -> JournalStage.DISPATCHING
                JournalEvent.CANCEL -> JournalStage.CANCELLED
                JournalEvent.EXPIRE -> JournalStage.EXPIRED
                else -> null
            }
            JournalStage.DISPATCHING -> when (event) {
                JournalEvent.DISPATCH_ACCEPTED -> JournalStage.ACCEPTED
                JournalEvent.FAILED -> JournalStage.FAILED
                JournalEvent.MARK_AMBIGUOUS -> JournalStage.AMBIGUOUS
                JournalEvent.REQUIRE_RECONCILIATION -> JournalStage.RECONCILIATION_REQUIRED
                else -> null
            }
            JournalStage.ACCEPTED -> when (event) {
                JournalEvent.VERIFICATION_STARTED -> JournalStage.VERIFYING
                JournalEvent.MARK_AMBIGUOUS -> JournalStage.AMBIGUOUS
                JournalEvent.REQUIRE_RECONCILIATION -> JournalStage.RECONCILIATION_REQUIRED
                else -> null
            }
            JournalStage.VERIFYING -> when (event) {
                JournalEvent.VERIFIED -> JournalStage.VERIFIED
                JournalEvent.MARK_AMBIGUOUS -> JournalStage.AMBIGUOUS
                JournalEvent.REQUIRE_RECONCILIATION -> JournalStage.RECONCILIATION_REQUIRED
                JournalEvent.FAILED -> JournalStage.FAILED
                else -> null
            }
            JournalStage.AMBIGUOUS -> when (event) {
                JournalEvent.REQUIRE_RECONCILIATION -> JournalStage.RECONCILIATION_REQUIRED
                JournalEvent.VERIFIED -> JournalStage.VERIFIED
                else -> null
            }
            JournalStage.RECONCILIATION_REQUIRED -> when (event) {
                JournalEvent.VERIFIED -> JournalStage.VERIFIED
                JournalEvent.REJECTED -> JournalStage.REJECTED
                else -> null
            }
            JournalStage.FAILED, JournalStage.REJECTED, JournalStage.CANCELLED, JournalStage.EXPIRED -> when (event) {
                JournalEvent.REOPEN -> JournalStage.PROPOSED
                else -> null
            }
            JournalStage.VERIFIED -> when (event) {
                JournalEvent.REQUIRE_RECONCILIATION -> JournalStage.RECONCILIATION_REQUIRED
                else -> null
            }
        }
        return if (stage == null) {
            JournalTransition.Illegal(from, event)
        } else {
            JournalTransition.Allowed(stage)
        }
    }
}

/**
 * One row of the journal. Immutable: a transition produces a new revision
 * through [JournalStore], and the store is what makes it durable.
 */
data class ExecutionJournal(
    val executionId: ExecutionId,
    val tenantId: TenantId,
    val actorId: ActorId,
    val proposalId: ProposalId?,
    val proposalFingerprint: String,
    val tool: ToolName,
    val toolVersion: String,
    val schemaVersion: String,
    val catalogVersion: String,
    val canonicalInputHash: String,
    val idempotencyKey: IdempotencyKey,
    val policyVersionId: String,
    val policyHash: String,
    val approvalId: String?,
    val approvalFingerprint: String?,
    val proofReference: String?,
    val stage: JournalStage,
    val riskTier: RiskTier,
    val approvalLevel: ApprovalLevel,
    val dispatch: DispatchState,
    val dispatchStartedAt: Instant?,
    val dispatchFinishedAt: Instant?,
    val responseReceivedAt: Instant?,
    val verificationStartedAt: Instant?,
    val verificationFinishedAt: Instant?,
    val erpModel: String?,
    val erpRecordId: String?,
    val candidateIds: List<String>,
    val errorCode: String?,
    val traceId: String,
    val revision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val leaseExpiresAt: Instant? = null,
) {
    /** The journal entry after a legal transition, or an illegal-step report. */
    fun advance(event: JournalEvent, at: Instant, machine: JournalStateMachine = JournalStateMachine()):
        JournalAdvance = when (val step = machine.next(stage, event)) {
        is JournalTransition.Illegal -> JournalAdvance.Illegal(step)
        is JournalTransition.Allowed -> JournalAdvance.Moved(
            copy(
                stage = step.stage,
                revision = revision + 1,
                updatedAt = at,
                dispatch = dispatchAfter(event),
                dispatchStartedAt = dispatchStartedAt ?: if (event == JournalEvent.DISPATCH_STARTED) at else null,
                dispatchFinishedAt = dispatchFinishedAt ?: if (event == JournalEvent.DISPATCH_ACCEPTED) at else null,
                responseReceivedAt = responseReceivedAt ?: if (event == JournalEvent.DISPATCH_ACCEPTED) at else null,
                verificationStartedAt = verificationStartedAt
                    ?: if (event == JournalEvent.VERIFICATION_STARTED) at else null,
                verificationFinishedAt = verificationFinishedAt
                    ?: if (event == JournalEvent.VERIFIED) at else null,
                errorCode = if (event == JournalEvent.FAILED) errorCode ?: "EXECUTION_FAILED" else errorCode,
            ),
        )
    }

    private fun dispatchAfter(event: JournalEvent): DispatchState = when (event) {
        JournalEvent.DISPATCH_STARTED -> DispatchState.SENT
        JournalEvent.MARK_AMBIGUOUS -> DispatchState.UNKNOWN
        JournalEvent.REQUIRE_RECONCILIATION -> DispatchState.UNKNOWN
        else -> dispatch
    }
}

sealed interface JournalAdvance {
    data class Moved(val journal: ExecutionJournal) : JournalAdvance
    data class Illegal(val transition: JournalTransition.Illegal) : JournalAdvance
}
