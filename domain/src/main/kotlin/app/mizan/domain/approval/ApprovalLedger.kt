package app.mizan.domain.approval

import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.Proposal
import app.mizan.domain.model.TenantId
import app.mizan.domain.policy.ApprovalRecord
import java.time.Duration

/**
 * An approval is an object, not a boolean on a request.
 *
 * It names the proposal *revision* it approved, the fingerprint of that
 * proposal, and the policy snapshot that was in force. That is what lets the
 * system answer, months later, what a person actually authorised.
 */
enum class ApprovalState {
    PENDING,
    GRANTED,
    REJECTED,
    /** The proposal changed after this approval. It can never be reused. */
    INVALIDATED,
    EXPIRED,
    /** Used by exactly one execution. */
    CONSUMED,
}

data class ApprovalDecision(
    val approverId: ActorId,
    val approverLabel: String,
    val state: ApprovalState,
    val atEpochMillis: Long,
    val reasonCode: String? = null,
)

data class ApprovalRequest(
    val id: String,
    val proposalId: String,
    val tenantId: TenantId,
    val initiatorId: ActorId,
    /** The proposal revision this approval is about. */
    val proposalRevision: Int,
    /** [Proposal.fingerprint] at the moment of the request. */
    val proposalFingerprint: String,
    val requiredLevel: ApprovalLevel,
    val policyVersionId: String,
    val policyHash: String,
    val state: ApprovalState,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val approvals: List<ApprovalRecord>,
    val decisions: List<ApprovalDecision>,
)

enum class ApprovalVerdict {
    VALID,
    MISSING,
    /** The proposal changed. The approval is dead, not merely stale. */
    INVALIDATED_BY_CHANGE,
    EXPIRED,
    /** The policy moved while this approval waited. Re-evaluate, do not execute. */
    POLICY_CHANGED,
    ALREADY_CONSUMED,
    REJECTED,
    ROLE_INSUFFICIENT,
}

data class ApprovalValidation(
    val verdict: ApprovalVerdict,
    val reasonCode: String,
    val errorCode: String?,
) {
    val valid: Boolean get() = verdict == ApprovalVerdict.VALID
}

/**
 * Approval lifetimes. Short for money, longer for a draft. They are policy,
 * not constants: a workspace can move them.
 */
data class ApprovalWindow(
    val l1: Duration = Duration.ofHours(12),
    val l2: Duration = Duration.ofHours(4),
    val l3: Duration = Duration.ofHours(2),
    val sensitive: Duration = Duration.ofMinutes(30),
) {
    fun forLevel(level: ApprovalLevel): Duration = when (level) {
        ApprovalLevel.L0_NONE -> Duration.ZERO
        ApprovalLevel.L1_USER_CONFIRMATION -> l1
        ApprovalLevel.L2_PRIVILEGED -> l2
        ApprovalLevel.L3_MANAGER -> l3
        ApprovalLevel.L4_DUAL, ApprovalLevel.L5_MULTI_PARTY -> sensitive
    }
}

class ApprovalPolicy(
    private val window: ApprovalWindow = ApprovalWindow(),
) {

    fun open(
        requestId: String,
        proposal: Proposal,
        nowMillis: Long,
    ): ApprovalRequest = ApprovalRequest(
        id = requestId,
        proposalId = proposal.id.value,
        tenantId = proposal.tenantId,
        initiatorId = proposal.initiator.id,
        proposalRevision = proposal.revision,
        proposalFingerprint = proposal.fingerprint,
        requiredLevel = proposal.policy.approval,
        policyVersionId = proposal.policy.policyVersionId,
        policyHash = proposal.policy.policyHash,
        state = ApprovalState.PENDING,
        createdAtMillis = nowMillis,
        expiresAtMillis = nowMillis + window.forLevel(proposal.policy.approval).toMillis(),
        approvals = emptyList(),
        decisions = emptyList(),
    )

    /**
     * The single gate every execution passes through. It answers with the
     * reason, so the app can say *why* a person must look again.
     */
    fun validate(
        request: ApprovalRequest?,
        currentFingerprint: String,
        currentRevision: Int,
        currentPolicyVersionId: String,
        currentPolicyHash: String,
        nowMillis: Long,
    ): ApprovalValidation {
        if (request == null) {
            return ApprovalValidation(
                ApprovalVerdict.MISSING,
                "APPROVAL_MISSING",
                "APPROVAL_REQUIRED",
            )
        }
        return when {
            request.state == ApprovalState.CONSUMED -> ApprovalValidation(
                ApprovalVerdict.ALREADY_CONSUMED,
                "APPROVAL_ALREADY_CONSUMED",
                "EXECUTION_ALREADY_RESOLVED",
            )
            request.state == ApprovalState.REJECTED -> ApprovalValidation(
                ApprovalVerdict.REJECTED,
                "APPROVAL_REJECTED",
                "APPROVAL_REQUIRED",
            )
            request.state == ApprovalState.INVALIDATED -> ApprovalValidation(
                ApprovalVerdict.INVALIDATED_BY_CHANGE,
                "APPROVAL_INVALIDATED",
                "APPROVAL_INVALIDATED",
            )
            // A change of either the content or the revision kills the
            // approval. Comparing the revision alone would miss an edit that
            // kept the revision and moved the amount.
            request.proposalFingerprint != currentFingerprint -> ApprovalValidation(
                ApprovalVerdict.INVALIDATED_BY_CHANGE,
                "PROPOSAL_CHANGED_SINCE_APPROVAL",
                "APPROVAL_INVALIDATED",
            )
            request.proposalRevision != currentRevision -> ApprovalValidation(
                ApprovalVerdict.INVALIDATED_BY_CHANGE,
                "PROPOSAL_REVISED_SINCE_APPROVAL",
                "APPROVAL_INVALIDATED",
            )
            request.policyVersionId != currentPolicyVersionId || request.policyHash != currentPolicyHash ->
                ApprovalValidation(
                    ApprovalVerdict.POLICY_CHANGED,
                    "POLICY_CHANGED_SINCE_APPROVAL",
                    "POLICY_VERSION_CHANGED",
                )
            nowMillis >= request.expiresAtMillis -> ApprovalValidation(
                ApprovalVerdict.EXPIRED,
                "APPROVAL_EXPIRED",
                "APPROVAL_EXPIRED",
            )
            request.state != ApprovalState.GRANTED -> ApprovalValidation(
                ApprovalVerdict.MISSING,
                "APPROVAL_NOT_GRANTED",
                "APPROVAL_REQUIRED",
            )
            else -> ApprovalValidation(ApprovalVerdict.VALID, "APPROVAL_VALID", null)
        }
    }

    fun grant(
        request: ApprovalRequest,
        approver: ApprovalRecord,
        nowMillis: Long,
    ): ApprovalRequest = request.copy(
        state = ApprovalState.GRANTED,
        approvals = request.approvals.filterNot { it.approver.id == approver.approver.id } + approver,
        decisions = request.decisions + ApprovalDecision(
            approverId = approver.approver.id,
            approverLabel = approver.approver.displayName,
            state = ApprovalState.GRANTED,
            atEpochMillis = nowMillis,
        ),
    )

    fun reject(request: ApprovalRequest, approverId: ActorId, label: String, nowMillis: Long): ApprovalRequest =
        request.copy(
            state = ApprovalState.REJECTED,
            decisions = request.decisions + ApprovalDecision(
                approverId = approverId,
                approverLabel = label,
                state = ApprovalState.REJECTED,
                atEpochMillis = nowMillis,
            ),
        )

    /**
     * Called when the proposal changes. The old approval is kept for the
     * record -- it is evidence that a person approved something -- but it is
     * marked invalid so it can never authorise the new revision.
     */
    fun invalidate(request: ApprovalRequest, reasonCode: String, nowMillis: Long): ApprovalRequest = request.copy(
        state = ApprovalState.INVALIDATED,
        decisions = request.decisions + ApprovalDecision(
            approverId = request.initiatorId,
            approverLabel = "",
            state = ApprovalState.INVALIDATED,
            atEpochMillis = nowMillis,
            reasonCode = reasonCode,
        ),
    )

    fun consume(request: ApprovalRequest, nowMillis: Long): ApprovalRequest = request.copy(
        state = ApprovalState.CONSUMED,
        decisions = request.decisions + ApprovalDecision(
            approverId = request.initiatorId,
            approverLabel = "",
            state = ApprovalState.CONSUMED,
            atEpochMillis = nowMillis,
            reasonCode = "EXECUTION_DISPATCHED",
        ),
    )
}

/** One field that moved between two revisions of a proposal. */
data class ProposalChange(
    val field: String,
    val before: String,
    val after: String,
)

data class ProposalDiff(
    val changes: List<ProposalChange>,
) {
    val changed: Boolean get() = changes.isNotEmpty()
    val fingerprintChanged: Boolean get() = changes.any { it.field in MATERIAL_FIELDS }

    companion object {
        /**
         * The fields that decide what the action does. A changed note is not a
         * reason to invalidate an approval; a changed amount is.
         */
        val MATERIAL_FIELDS = setOf("amountMinor", "currency", "customerName", "itemsSummary", "arguments")

        /**
         * "What changed?" -- the answer the approver needs, as a diff rather
         * than a paragraph.
         */
        fun between(before: Proposal, after: Proposal): ProposalDiff {
            val changes = mutableListOf<ProposalChange>()
            if (before.amount?.minorUnits != after.amount?.minorUnits) {
                changes += ProposalChange(
                    "amountMinor",
                    before.amount?.minorUnits?.toString() ?: "",
                    after.amount?.minorUnits?.toString() ?: "",
                )
            }
            if (before.amount?.currency != after.amount?.currency) {
                changes += ProposalChange(
                    "currency",
                    before.amount?.currency ?: "",
                    after.amount?.currency ?: "",
                )
            }
            val beforeArgs = before.args.canonical().let { app.mizan.domain.model.CanonicalJson.write(it) }
            val afterArgs = after.args.canonical().let { app.mizan.domain.model.CanonicalJson.write(it) }
            if (beforeArgs != afterArgs) {
                for ((field, from, to) in fieldDiff(beforeArgs, afterArgs)) {
                    changes += ProposalChange(field, from, to)
                }
                changes += ProposalChange("arguments", beforeArgs, afterArgs)
            }
            if (before.policy.approval != after.policy.approval) {
                changes += ProposalChange(
                    "approval",
                    before.policy.approval.name,
                    after.policy.approval.name,
                )
            }
            if (before.entity?.fingerprint != after.entity?.fingerprint) {
                changes += ProposalChange(
                    "customerName",
                    before.entity?.canonicalName ?: "",
                    after.entity?.canonicalName ?: "",
                )
            }
            return ProposalDiff(changes.distinctBy { it.field })
        }

        private fun fieldDiff(before: String, after: String): List<Triple<String, String, String>> {
            val changes = mutableListOf<Triple<String, String, String>>()
            for (field in listOf("customerName", "amountMinor", "currency", "itemsSummary", "orderId", "invoiceId")) {
                val from = valueOf(before, field)
                val to = valueOf(after, field)
                if (from != to) changes += Triple(field, from, to)
            }
            return changes
        }

        private fun valueOf(json: String, field: String): String {
            val marker = "\"$field\":"
            val start = json.indexOf(marker)
            if (start < 0) return ""
            val rest = json.substring(start + marker.length)
            if (rest.startsWith("\"")) {
                val end = rest.indexOf('"', 1)
                return if (end < 0) rest else rest.substring(1, end)
            }
            val end = rest.indexOfAny(charArrayOf(',', '}'))
            return if (end < 0) rest else rest.substring(0, end)
        }
    }
}
