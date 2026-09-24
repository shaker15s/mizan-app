package app.mizan.domain.execution

import app.mizan.domain.error.DispatchState

/**
 * One phase is the source of truth. UI must not reassemble this from booleans.
 */
enum class ExecutionPhase {
    PROPOSED,
    VALIDATED,
    RISK_EVALUATED,
    AWAITING_APPROVAL,
    AUTHORIZED,
    LEASE_ACQUIRED,
    EXECUTING,
    ERP_ACCEPTED,
    VERIFICATION_PENDING,
    VERIFIED,
    REJECTED,
    CANCELLED,
    TIMEOUT,
    ERP_FAILURE,
    AMBIGUOUS,
    RECONCILIATION_REQUIRED,
    /** A person linked a candidate. The ERP record was not read back. */
    LINKED_UNVERIFIED,
    /** Closed with no confirmed record. Not a failure, and not a success. */
    CLOSED_UNVERIFIED,
    ;

    val terminal: Boolean
        get() = this == VERIFIED || this == REJECTED || this == CANCELLED ||
            this == ERP_FAILURE || this == RECONCILIATION_REQUIRED ||
            this == LINKED_UNVERIFIED || this == CLOSED_UNVERIFIED
}

sealed interface ExecutionEvent {
    data object Validate : ExecutionEvent
    data object RiskEvaluated : ExecutionEvent
    data object RequestApproval : ExecutionEvent
    data object Authorize : ExecutionEvent
    data object AcquireLease : ExecutionEvent
    data object StartExecution : ExecutionEvent
    data object ErpAccepted : ExecutionEvent
    data object StartVerification : ExecutionEvent
    data object Verify : ExecutionEvent
    data class Reject(val reasonCode: String) : ExecutionEvent
    data object Cancel : ExecutionEvent
    data class TimedOut(val dispatch: DispatchState) : ExecutionEvent
    data class ErpFailed(val reasonCode: String) : ExecutionEvent
    data object MarkAmbiguous : ExecutionEvent
    data object RequireReconciliation : ExecutionEvent
    data object LinkUnverified : ExecutionEvent
    data object CloseUnverified : ExecutionEvent
}

sealed interface Transition {
    data class Allowed(val phase: ExecutionPhase) : Transition
    data class Illegal(val from: ExecutionPhase, val event: String) : Transition
}

class ExecutionStateMachine {
    /** Walks from [ExecutionPhase.PROPOSED]. Stops at the first illegal step. */
    fun run(events: List<ExecutionEvent>): Transition {
        var phase = ExecutionPhase.PROPOSED
        for (event in events) {
            when (val step = next(phase, event)) {
                is Transition.Illegal -> return step
                is Transition.Allowed -> phase = step.phase
            }
        }
        return Transition.Allowed(phase)
    }

    fun next(from: ExecutionPhase, event: ExecutionEvent): Transition {
        val phase = when (from) {
            ExecutionPhase.PROPOSED -> when (event) {
                ExecutionEvent.Validate -> ExecutionPhase.VALIDATED
                is ExecutionEvent.Reject -> ExecutionPhase.REJECTED
                ExecutionEvent.Cancel -> ExecutionPhase.CANCELLED
                else -> null
            }
            ExecutionPhase.VALIDATED -> when (event) {
                ExecutionEvent.RiskEvaluated -> ExecutionPhase.RISK_EVALUATED
                is ExecutionEvent.Reject -> ExecutionPhase.REJECTED
                ExecutionEvent.Cancel -> ExecutionPhase.CANCELLED
                else -> null
            }
            ExecutionPhase.RISK_EVALUATED -> when (event) {
                ExecutionEvent.RequestApproval -> ExecutionPhase.AWAITING_APPROVAL
                ExecutionEvent.Authorize -> ExecutionPhase.AUTHORIZED
                is ExecutionEvent.Reject -> ExecutionPhase.REJECTED
                ExecutionEvent.Cancel -> ExecutionPhase.CANCELLED
                else -> null
            }
            ExecutionPhase.AWAITING_APPROVAL -> when (event) {
                ExecutionEvent.Authorize -> ExecutionPhase.AUTHORIZED
                is ExecutionEvent.Reject -> ExecutionPhase.REJECTED
                ExecutionEvent.Cancel -> ExecutionPhase.CANCELLED
                else -> null
            }
            ExecutionPhase.AUTHORIZED -> when (event) {
                ExecutionEvent.AcquireLease -> ExecutionPhase.LEASE_ACQUIRED
                is ExecutionEvent.Reject -> ExecutionPhase.REJECTED
                ExecutionEvent.Cancel -> ExecutionPhase.CANCELLED
                else -> null
            }
            ExecutionPhase.LEASE_ACQUIRED -> when (event) {
                ExecutionEvent.StartExecution -> ExecutionPhase.EXECUTING
                is ExecutionEvent.TimedOut -> timeoutPhase(event.dispatch)
                ExecutionEvent.Cancel -> ExecutionPhase.CANCELLED
                else -> null
            }
            ExecutionPhase.EXECUTING -> when (event) {
                ExecutionEvent.ErpAccepted -> ExecutionPhase.ERP_ACCEPTED
                is ExecutionEvent.ErpFailed -> ExecutionPhase.ERP_FAILURE
                is ExecutionEvent.TimedOut -> timeoutPhase(event.dispatch)
                ExecutionEvent.MarkAmbiguous -> ExecutionPhase.AMBIGUOUS
                else -> null
            }
            ExecutionPhase.ERP_ACCEPTED -> when (event) {
                ExecutionEvent.StartVerification -> ExecutionPhase.VERIFICATION_PENDING
                ExecutionEvent.MarkAmbiguous -> ExecutionPhase.AMBIGUOUS
                else -> null
            }
            ExecutionPhase.VERIFICATION_PENDING -> when (event) {
                ExecutionEvent.Verify -> ExecutionPhase.VERIFIED
                ExecutionEvent.MarkAmbiguous -> ExecutionPhase.AMBIGUOUS
                is ExecutionEvent.ErpFailed -> ExecutionPhase.ERP_FAILURE
                else -> null
            }
            ExecutionPhase.AMBIGUOUS, ExecutionPhase.TIMEOUT -> when (event) {
                ExecutionEvent.RequireReconciliation -> ExecutionPhase.RECONCILIATION_REQUIRED
                else -> null
            }
            ExecutionPhase.RECONCILIATION_REQUIRED -> when (event) {
                ExecutionEvent.LinkUnverified -> ExecutionPhase.LINKED_UNVERIFIED
                ExecutionEvent.CloseUnverified -> ExecutionPhase.CLOSED_UNVERIFIED
                else -> null
            }
            else -> null
        }
        return if (phase == null) {
            Transition.Illegal(from, event::class.simpleName ?: "event")
        } else {
            Transition.Allowed(phase)
        }
    }

    private fun timeoutPhase(dispatch: DispatchState): ExecutionPhase = when (dispatch) {
        DispatchState.NOT_SENT -> ExecutionPhase.ERP_FAILURE
        DispatchState.SENT, DispatchState.UNKNOWN -> ExecutionPhase.AMBIGUOUS
    }
}
