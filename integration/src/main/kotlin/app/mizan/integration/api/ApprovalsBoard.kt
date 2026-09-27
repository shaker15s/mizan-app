package app.mizan.integration.api

import app.mizan.domain.approval.ApprovalState
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.Digests

/**
 * The approvals screen's state, as a value.
 *
 * The app has a governance screen that described the rules, and the service
 * has had an approval surface for a while: create, list, grant, refuse, each
 * with a fingerprint, an expiry and a device proof. Nothing joined them, so
 * `GovernanceApiClient.approvals()` was a method with no caller and the screen
 * a description with nothing live in it.
 *
 * This is the join. It is deliberately not Compose: it is a small, testable
 * mapping from what the service said to what a person is allowed to do about
 * it, and the screen renders the result. Everything a screen might otherwise
 * invent lives here instead:
 *
 *  - **whose turn it is.** A row is answerable by the viewer only when the
 *    service says it is pending, the viewer is one of the approvers it named,
 *    and the level is one this person can answer. Everything else is "waiting
 *    on someone else", and it says who.
 *  - **when it expires, as a fact.** The countdown is computed from the
 *    service's `expiresAtMillis` against a clock, so a screen cannot show a
 *    reassuring "24h" next to an approval that dies in ten minutes.
 *  - **what a refusal was.** A code and an HTTP status, never prose and never
 *    an empty list that reads as "nothing needs you".
 */
data class ApprovalRow(
    val approval: RemoteApproval,
    /** What the viewer may do with this row right now. */
    val standing: Standing,
    /** For a row that is pending: who the service named. */
    val awaiting: List<String>,
    /** Milliseconds until expiry, negative when it has already passed. */
    val millisecondsToExpiry: Long,
    /** True when a decision carries a device proof, so the screen asks first. */
    val needsDeviceProof: Boolean,
) {
    enum class Standing {
        /** Pending and answerable by this person, on this device. */
        ANSWERABLE,
        /** Pending, but the service named someone else, or this person may not. */
        AWAITING_OTHERS,
        /** Pending and past its expiry: the service will refuse a decision. */
        EXPIRED,
        /** Granted and not yet consumed: it is good for exactly one execution. */
        GRANTED,
        REJECTED,
        /** The proposal changed after the grant, so it can never be reused. */
        INVALIDATED,
        /** Already spent by an execution. */
        CONSUMED,
    }

    val id: String get() = approval.id

    /** A stable, human-sortable ordering: what needs you first, then by age. */
    fun rank(): Int = when (standing) {
        Standing.ANSWERABLE -> 0
        Standing.AWAITING_OTHERS -> 1
        Standing.EXPIRED -> 2
        Standing.GRANTED -> 3
        Standing.REJECTED -> 4
        Standing.INVALIDATED -> 5
        Standing.CONSUMED -> 6
    }
}

/** Everything the approvals surface can be in. There is no fifth state. */
sealed interface ApprovalsState {

    /** Nothing has been read yet. Not the same as "nothing needs you". */
    data object Unread : ApprovalsState

    data class Loaded(
        val rows: List<ApprovalRow>,
        /** The state filter that produced these rows, or null for all. */
        val filteredBy: ApprovalState? = null,
        val durable: Boolean? = null,
    ) : ApprovalsState {
        val answerable: List<ApprovalRow> get() = rows.filter { it.standing == ApprovalRow.Standing.ANSWERABLE }
    }

    /**
     * The service answered, and the answer was not something this client can
     * use. [code] is a message key: `SERVICE_UNREACHABLE`,
     * `SERVICE_BODY_UNREADABLE`, or the service's own refusal code.
     */
    data class Failed(val code: String, val httpStatus: Int?) : ApprovalsState

    /**
     * There is no service to ask: the build has no base URL, or no session.
     * This is a state of the product, not an error, and the screen must say so
     * rather than render an empty list that reads as good news.
     */
    data object Unconfigured : ApprovalsState
}

/**
 * The four calls the board makes, as an interface.
 *
 * It exists so the board's rules -- who may answer what, in which order, with
 * which refusal -- can be exercised without a socket. The HTTP shape of each
 * call is tested where it belongs, against the client itself.
 */
interface ApprovalsSource {

    fun list(state: ApprovalState?): RemoteResult<List<RemoteApproval>>

    fun challenge(approvalId: String, deviceId: String): RemoteResult<RemoteChallenge>

    fun grant(approvalId: String, challengeId: String?, signature: String?): RemoteResult<RemoteApproval>

    fun refuse(approvalId: String, reasonCode: String): RemoteResult<RemoteApproval>
}

/** The source the app uses: the governance client, unchanged. */
class GovernanceApiSource(private val client: GovernanceApiClient) : ApprovalsSource {

    override fun list(state: ApprovalState?): RemoteResult<List<RemoteApproval>> = client.approvals(state)

    override fun challenge(approvalId: String, deviceId: String): RemoteResult<RemoteChallenge> =
        client.challengeForApproval(approvalId, deviceId)

    override fun grant(approvalId: String, challengeId: String?, signature: String?): RemoteResult<RemoteApproval> =
        client.grantApproval(approvalId, challengeId, signature)

    override fun refuse(approvalId: String, reasonCode: String): RemoteResult<RemoteApproval> =
        client.refuseApproval(approvalId, reasonCode)
}

/**
 * Reads and answers approvals for one signed-in person.
 *
 * The board never decides anything about money: it asks the service what it
 * wants, and it sends an answer only for a row the service itself described as
 * pending. What it adds over the raw client is that every failure becomes a
 * state a screen can render honestly, and that the decision path is one call
 * so a screen cannot skip the challenge.
 *
 * [signChallenge] is the device's own key, handed in rather than reached for:
 * the board cannot sign anything, and a test can prove that a grant without a
 * signature is refused before it reaches the wire on a deployment that
 * requires device proof.
 */
class ApprovalsBoard(
    private val source: ApprovalsSource,
    /**
     * Who is looking at this queue. A provider rather than a value: the board
     * outlives a session, and a signed-out board must not answer as the last
     * person who signed in.
     */
    private val actorId: () -> String,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** The levels this person may answer. The service re-checks; so does this. */
    private val answerableLevels: Set<ApprovalLevel> = ApprovalLevel.entries.toSet(),
    /**
     * Signs a challenge with the device key, returning the base64 signature.
     * Null means this device cannot sign, and the board refuses rather than
     * sending a grant the service would have to reject.
     */
    private val signChallenge: ((messageToSign: String) -> String?)? = null,
    /**
     * The enrolled device that answers for this person. Null when the build
     * has no device key: the board then asks the service for a challenge only
     * if one is required, and refuses with `DEVICE_KEY_NOT_ENROLLED` rather
     * than sending an unsigned grant.
     */
    private val deviceId: String? = null,
) {

    private var lastState: ApprovalsState = ApprovalsState.Unread

    /** The last state the board produced, for a screen that recomposes. */
    fun state(): ApprovalsState = lastState

    fun refresh(state: ApprovalState? = null): ApprovalsState {
        val result = source.list(state)
        lastState = when (result) {
            is RemoteResult.Ok -> ApprovalsState.Loaded(
                rows = result.value.map(::rowOf).sortedWith(compareBy({ it.rank() }, { -it.approval.createdAtMillis })),
                filteredBy = state,
            )
            is RemoteResult.Refused -> ApprovalsState.Failed(result.code, result.httpStatus)
        }
        return lastState
    }

    /**
     * Answers a pending approval, with a device proof when the service asks
     * for one.
     *
     * The order is fixed and that is the point: read the row, ask the service
     * for a challenge bound to this approval, sign it with the device key,
     * then grant. A screen cannot call grant directly with a signature it made
     * up, because it does not build the request.
     */
    fun grant(approvalId: String, requireDeviceProof: Boolean = true): ApprovalAction {
        val rows = (refresh(ApprovalState.PENDING) as? ApprovalsState.Loaded)?.rows
            ?: return ApprovalAction.Refused(codeOf(lastState), statusOf(lastState))
        val row = rows.firstOrNull { it.id == approvalId }
            ?: return ApprovalAction.Refused("APPROVAL_NOT_FOUND", null)
        if (row.standing == ApprovalRow.Standing.EXPIRED) {
            // Sending it anyway would come back 409 APPROVAL_EXPIRED; refusing
            // here keeps the reason the same in both places.
            return ApprovalAction.Refused("APPROVAL_EXPIRED", null)
        }
        if (row.standing != ApprovalRow.Standing.ANSWERABLE) {
            return ApprovalAction.Refused("APPROVAL_NOT_YOURS", null)
        }
        // A level that needs no proof is answered without one, whatever the
        // caller asked for: asking a device to sign for L0 would be a proof
        // nobody required, and the service would refuse the extra field.
        val challenge = if (requireDeviceProof && row.needsDeviceProof) {
            val enrolled = deviceId
                ?: return ApprovalAction.Refused("DEVICE_KEY_NOT_ENROLLED", null)
            when (val requested = source.challenge(approvalId, enrolled)) {
                is RemoteResult.Ok -> requested.value
                is RemoteResult.Refused -> return ApprovalAction.Refused(requested.code, requested.httpStatus)
            }
        } else {
            null
        }
        val signature = challenge?.let { issued ->
            val signer = signChallenge
                ?: return ApprovalAction.Refused("DEVICE_KEY_NOT_ENROLLED", null)
            signer(issued.messageToSign) ?: return ApprovalAction.Refused("DEVICE_SIGNATURE_FAILED", null)
        }
        val result = source.grant(approvalId, challenge?.challengeId, signature)
        return when (result) {
            is RemoteResult.Ok -> ApprovalAction.Answered(result.value)
            is RemoteResult.Refused -> ApprovalAction.Refused(result.code, result.httpStatus)
        }
    }

    /**
     * Records a refusal.
     *
     * It reads the queue first for the same reason a grant does: refusing is
     * an answer about one approval, by one person, while it is still open. A
     * client that could refuse an id it never read would be able to write into
     * a trail it cannot see.
     */
    fun refuse(approvalId: String, reasonCode: String): ApprovalAction {
        val rows = (refresh(ApprovalState.PENDING) as? ApprovalsState.Loaded)?.rows
            ?: return ApprovalAction.Refused(codeOf(lastState), statusOf(lastState))
        val row = rows.firstOrNull { it.id == approvalId }
            ?: return ApprovalAction.Refused("APPROVAL_NOT_FOUND", null)
        if (row.standing == ApprovalRow.Standing.EXPIRED) {
            return ApprovalAction.Refused("APPROVAL_EXPIRED", null)
        }
        if (row.standing != ApprovalRow.Standing.ANSWERABLE) {
            return ApprovalAction.Refused("APPROVAL_NOT_YOURS", null)
        }
        val result = source.refuse(approvalId, reasonCode)
        return when (result) {
            is RemoteResult.Ok -> ApprovalAction.Answered(result.value)
            is RemoteResult.Refused -> ApprovalAction.Refused(result.code, result.httpStatus)
        }
    }

    /** What the caller must show before a decision: a proof, and whose it is. */
    fun proofRequirement(row: ApprovalRow): String? = when {
        row.standing != ApprovalRow.Standing.ANSWERABLE -> null
        row.needsDeviceProof && signChallenge == null -> "DEVICE_KEY_NOT_ENROLLED"
        else -> null
    }

    /** A fingerprint short enough for a screen, derived from the real one. */
    fun shortFingerprint(row: ApprovalRow): String =
        row.approval.proposalFingerprint.take(12) + "\u2026(" + row.approval.proposalFingerprint.length + ")"

    private fun rowOf(approval: RemoteApproval): ApprovalRow {
        val expiry = approval.expiresAtMillis - now()
        val standing = when {
            approval.state == ApprovalState.GRANTED -> ApprovalRow.Standing.GRANTED
            approval.state == ApprovalState.REJECTED -> ApprovalRow.Standing.REJECTED
            approval.state == ApprovalState.INVALIDATED -> ApprovalRow.Standing.INVALIDATED
            approval.state == ApprovalState.CONSUMED -> ApprovalRow.Standing.CONSUMED
            approval.state == ApprovalState.EXPIRED -> ApprovalRow.Standing.EXPIRED
            approval.state == ApprovalState.PENDING && expiry <= 0L -> ApprovalRow.Standing.EXPIRED
            !isMine(approval) -> ApprovalRow.Standing.AWAITING_OTHERS
            approval.requiredLevel !in answerableLevels -> ApprovalRow.Standing.AWAITING_OTHERS
            else -> ApprovalRow.Standing.ANSWERABLE
        }
        return ApprovalRow(
            approval = approval,
            standing = standing,
            awaiting = if (standing == ApprovalRow.Standing.AWAITING_OTHERS) approval.approverIds else emptyList(),
            millisecondsToExpiry = expiry,
            needsDeviceProof = approval.requiredLevel != ApprovalLevel.L0_NONE,
        )
    }

    /**
     * Whether the service named this viewer.
     *
     * An approval with no named approvers is answerable by anyone the service
     * allows to answer it -- that is what a queue is -- and an approval with
     * names is not answerable by anyone else. The service enforces both; this
     * only decides what to render.
     */
    private fun isMine(approval: RemoteApproval): Boolean {
        val viewer = actorId()
        return approval.approverIds.isEmpty() || approval.approverIds.contains(viewer)
    }

    private fun codeOf(state: ApprovalsState): String = when (state) {
        is ApprovalsState.Failed -> state.code
        ApprovalsState.Unconfigured -> "SERVICE_NOT_CONFIGURED"
        else -> "APPROVALS_UNREADABLE"
    }

    private fun statusOf(state: ApprovalsState): Int? = (state as? ApprovalsState.Failed)?.httpStatus

    /**
     * A short, stable id for a challenge body.
     *
     * The full message is never shown: a screen that renders it invites a
     * screenshot of the exact bytes a device signs. This is what a person can
     * compare between two devices when something does not line up.
     */
    fun fingerprintOf(messageToSign: String): String = Digests.sha256(messageToSign).take(16)

    companion object {
        /**
         * A board for a build with no service configured.
         *
         * `Unconfigured` is a real state and the reason this exists: a demo or
         * an unconfigured staging build must be able to render "there is no
         * service to ask" instead of an empty list, which reads as an answer.
         */
        fun unconfigured(): ApprovalsBoard = ApprovalsBoard(
            source = GovernanceApiSource(GovernanceApiClient("", { null })),
            actorId = { "" },
        )

        /** A board for one named person, for a test or a single-shot screen. */
        fun forActor(
            source: ApprovalsSource,
            actorId: String,
            now: () -> Long = { System.currentTimeMillis() },
            signChallenge: ((String) -> String?)? = null,
            deviceId: String? = null,
        ): ApprovalsBoard = ApprovalsBoard(
            source = source,
            actorId = { actorId },
            now = now,
            signChallenge = signChallenge,
            deviceId = deviceId,
        )
    }
}

/** What came back from answering an approval. */
sealed interface ApprovalAction {

    data class Answered(val approval: RemoteApproval) : ApprovalAction

    /** [httpStatus] is null when the refusal never reached the service. */
    data class Refused(val code: String, val httpStatus: Int?) : ApprovalAction
}
