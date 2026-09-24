package app.mizan.domain.authority

import app.mizan.domain.error.AppError
import app.mizan.domain.model.Actor
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.Proposal
import app.mizan.domain.model.VerificationKind
import app.mizan.domain.security.AuthProof

enum class AuthorityMode {
    /** Decisions are made by the MIZAN service. The device previews only. */
    REMOTE,

    /** Decisions are local and must be labeled simulation. */
    SIMULATION,
}

data class ExecuteCommand(
    val proposal: Proposal,
    val approver: Actor,
    val proof: AuthProof?,
    val secondApprover: Actor? = null,
    val simulateAmbiguous: Boolean = false,
)

sealed interface AuthorityOutcome {
    data class Verified(
        val executionId: ExecutionId,
        val erpRecordId: String,
        val erpModel: String,
        val verification: VerificationKind,
    ) : AuthorityOutcome

    data class AcceptedUnverified(
        val executionId: ExecutionId,
        val messageCode: String,
    ) : AuthorityOutcome

    data class Refused(val error: AppError) : AuthorityOutcome

    data class Uncertain(
        val executionId: ExecutionId,
        val messageCode: String,
        val candidateRecordIds: List<String>,
    ) : AuthorityOutcome
}

/**
 * The only path that may cause an external side effect.
 * A local policy preview is not an implementation of this interface
 * in production.
 */
interface ExecutionAuthority {
    val mode: AuthorityMode

    suspend fun execute(command: ExecuteCommand): AuthorityOutcome
}
