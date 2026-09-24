package app.mizan.domain.execution

import app.mizan.domain.error.DispatchState
import java.time.Instant

data class RecoverableExecution(
    val phase: ExecutionPhase,
    val dispatch: DispatchState,
    val leaseExpiresAt: Instant?,
)

sealed interface RecoveryAction {
    data object None : RecoveryAction
    data class MoveTo(val phase: ExecutionPhase, val reasonCode: String) : RecoveryAction
}

/**
 * Process death must not leave a write "running" forever, and must not
 * retry it. Unknown dispatch is treated as possibly sent.
 */
class ExecutionRecovery {
    fun decide(record: RecoverableExecution, now: Instant): RecoveryAction {
        val leaseExpired = record.leaseExpiresAt == null || !record.leaseExpiresAt.isAfter(now)
        if (!leaseExpired) return RecoveryAction.None
        return when (record.phase) {
            ExecutionPhase.EXECUTING,
            ExecutionPhase.LEASE_ACQUIRED,
            ExecutionPhase.ERP_ACCEPTED,
            ExecutionPhase.VERIFICATION_PENDING,
            -> when (record.dispatch) {
                DispatchState.NOT_SENT ->
                    RecoveryAction.MoveTo(ExecutionPhase.ERP_FAILURE, "RECOVERY_NOT_SENT")
                DispatchState.SENT, DispatchState.UNKNOWN ->
                    RecoveryAction.MoveTo(ExecutionPhase.RECONCILIATION_REQUIRED, "RECOVERY_UNCERTAIN")
            }
            else -> RecoveryAction.None
        }
    }
}
