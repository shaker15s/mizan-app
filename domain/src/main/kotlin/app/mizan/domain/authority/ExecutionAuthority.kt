package app.mizan.domain.authority

import app.mizan.domain.error.AppError
import app.mizan.domain.model.Actor
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.Proposal
import app.mizan.domain.model.VerificationKind
import app.mizan.domain.security.AuthProof

enum class AuthorityMode {
    /** Decisions are made by the Wakeel service. The device previews only. */
    REMOTE,

    /** Decisions are local and must be labeled simulation. */
    SIMULATION,
}

/**
 * The approval an execution is claiming, and the device proof that answers for
 * it.
 *
 * The service recomputes the fingerprint from the arguments it is about to
 * execute, so this is a claim the device makes and the service checks, not a
 * fact the device establishes. Without it the ladder for any privileged write
 * refuses, which is the whole point.
 */
data class ApprovalReference(
    val approvalId: String,
    val proposalFingerprint: String,
    val deviceChallengeId: String? = null,
    val deviceSignature: String? = null,
)

data class ExecuteCommand(
    val proposal: Proposal,
    val approver: Actor,
    val proof: AuthProof?,
    val secondApprover: Actor? = null,
    val simulateAmbiguous: Boolean = false,
    val approval: ApprovalReference? = null,
)

/**
 * The receipt as the device judged it.
 *
 * [trust] is the device's verdict and [reasonCode] is why, in the same
 * vocabulary the service uses, so a screen can show the reason without
 * inventing a sentence.
 */
data class ReceiptEvidence(
    val receiptId: String,
    val trust: app.mizan.domain.receipt.ReceiptTrust,
    val reasonCode: String,
) {
    val proven: Boolean get() = trust == app.mizan.domain.receipt.ReceiptTrust.VERIFIED
}

sealed interface AuthorityOutcome {
    data class Verified(
        val executionId: ExecutionId,
        val erpRecordId: String,
        val erpModel: String,
        val verification: VerificationKind,
        /**
         * What the device itself concluded about the authority's receipt.
         *
         * Null means no receipt was issued or none was asked for. Never null
         * and [ReceiptEvidence.proven] true unless a pinned key verified the
         * signature over claims that name this execution.
         */
        val receipt: ReceiptEvidence? = null,
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
