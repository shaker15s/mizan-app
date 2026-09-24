package app.mizan.domain.execution

import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.IdempotencyKey

enum class IdempotencyBlock {
    IN_FLIGHT,
    AMBIGUOUS,
    RECONCILIATION_OPEN,
    ALREADY_FAILED_UNSAFE,
    RESOLVED_UNVERIFIED,
}

sealed interface IdempotencyDecision {
    data object Proceed : IdempotencyDecision
    data class Replay(val executionId: ExecutionId, val phase: ExecutionPhase) : IdempotencyDecision
    data class Blocked(val reason: IdempotencyBlock, val executionId: ExecutionId) : IdempotencyDecision
}

data class IdempotencyClaim(
    val key: IdempotencyKey,
    val executionId: ExecutionId,
    val phase: ExecutionPhase,
    val dispatchSent: Boolean,
)

/**
 * Same canonical request must not create a second ERP record.
 * Ambiguous or in-flight claims are never automatic retries.
 */
class IdempotencyGuard {
    fun decide(existing: IdempotencyClaim?): IdempotencyDecision {
        if (existing == null) return IdempotencyDecision.Proceed
        return when (existing.phase) {
            ExecutionPhase.VERIFIED,
            ExecutionPhase.ERP_ACCEPTED,
            ExecutionPhase.VERIFICATION_PENDING,
            -> IdempotencyDecision.Replay(existing.executionId, existing.phase)

            ExecutionPhase.REJECTED,
            ExecutionPhase.CANCELLED,
            -> IdempotencyDecision.Proceed

            ExecutionPhase.ERP_FAILURE ->
                if (existing.dispatchSent) {
                    IdempotencyDecision.Blocked(IdempotencyBlock.ALREADY_FAILED_UNSAFE, existing.executionId)
                } else {
                    IdempotencyDecision.Proceed
                }

            ExecutionPhase.AMBIGUOUS,
            ExecutionPhase.TIMEOUT,
            -> IdempotencyDecision.Blocked(IdempotencyBlock.AMBIGUOUS, existing.executionId)

            ExecutionPhase.RECONCILIATION_REQUIRED ->
                IdempotencyDecision.Blocked(IdempotencyBlock.RECONCILIATION_OPEN, existing.executionId)

            ExecutionPhase.LINKED_UNVERIFIED,
            ExecutionPhase.CLOSED_UNVERIFIED,
            -> IdempotencyDecision.Blocked(IdempotencyBlock.RESOLVED_UNVERIFIED, existing.executionId)

            else -> IdempotencyDecision.Blocked(IdempotencyBlock.IN_FLIGHT, existing.executionId)
        }
    }
}
