package app.mizan.domain.model

import app.mizan.domain.policy.PolicyDecision

/**
 * A fingerprint is a hash of the things that make an action *this* action.
 *
 * The rule the plan sets is blunt: an approval is bound to the proposal it
 * approved. If any of these inputs change -- the amount, the customer, the
 * arguments, the tool version, the policy band -- the fingerprint changes, and
 * the approval that pointed at the old one is invalid rather than reusable.
 */
object Fingerprints {

    /** Everything that decides what a proposal will do. */
    fun proposal(
        tenantId: TenantId,
        initiatorId: ActorId,
        tool: ToolName,
        args: ToolArgs,
        amount: Money?,
        policyRuleId: String,
        approval: ApprovalLevel,
    ): String = digest(
        listOf(
            "tenant" to CanonicalValue.Str(tenantId.value),
            "initiator" to CanonicalValue.Str(initiatorId.value),
            "tool" to CanonicalValue.Str(tool.wire),
            "toolVersion" to CanonicalValue.Str(tool.version),
            "arguments" to args.canonical(),
            "amountMinor" to CanonicalValue.Num((amount?.minorUnits ?: 0L).toString()),
            "currency" to CanonicalValue.Str(amount?.currency ?: "XXX"),
            "policyRule" to CanonicalValue.Str(policyRuleId),
            "approval" to CanonicalValue.Str(approval.name),
        ),
    )

    /** The policy a proposal was judged under, so a change can be detected. */
    fun policy(decision: PolicyDecision): String = digest(
        listOf(
            "version" to CanonicalValue.Str(decision.policyVersionId),
            "hash" to CanonicalValue.Str(decision.policyHash),
            "rule" to CanonicalValue.Str(decision.ruleId),
            "approval" to CanonicalValue.Str(decision.approval.name),
        ),
    )

    /**
     * The ERP entity a proposal names. A draft order that reaches a different
     * customer record than the one the approver saw is not the same action.
     */
    fun entity(tenantId: TenantId, recordId: String, canonicalName: String): String = digest(
        listOf(
            "tenant" to CanonicalValue.Str(tenantId.value),
            "recordId" to CanonicalValue.Str(recordId),
            "canonicalName" to CanonicalValue.Str(canonicalName),
        ),
    )

    /**
     * The exact bytes a device signs when it authorises an approval.
     *
     * The signed material is the canonical body, not a hash of it, so anyone
     * holding the signature can also read what was approved: the tenant, the
     * actor, the execution, the proposal fingerprint and the single-use nonce.
     */
    fun approvalChallengeBody(
        tenantId: TenantId,
        actorId: ActorId,
        executionId: ExecutionId,
        proposalFingerprint: String,
        nonce: String,
    ): String = CanonicalJson.write(
        CanonicalValue.Obj(
            listOf(
                "actor" to CanonicalValue.Str(actorId.value),
                "execution" to CanonicalValue.Str(executionId.value),
                "nonce" to CanonicalValue.Str(nonce),
                "proposal" to CanonicalValue.Str(proposalFingerprint),
                "tenant" to CanonicalValue.Str(tenantId.value),
            ),
        ),
    )

    /** The fingerprint of [approvalChallengeBody]: what a log line records. */
    fun approvalChallenge(
        tenantId: TenantId,
        actorId: ActorId,
        executionId: ExecutionId,
        proposalFingerprint: String,
        nonce: String,
    ): String = Digests.sha256(
        approvalChallengeBody(tenantId, actorId, executionId, proposalFingerprint, nonce),
    )

    /** The body of a server-signed receipt. */
    fun receipt(fields: List<Pair<String, CanonicalValue>>): String = digest(fields)

    private fun digest(fields: List<Pair<String, CanonicalValue>>): String =
        Digests.sha256(CanonicalJson.write(CanonicalValue.Obj(fields)))
}
