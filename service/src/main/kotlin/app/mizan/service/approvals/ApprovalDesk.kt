package app.mizan.service.approvals

import app.mizan.domain.approval.ApprovalDecision
import app.mizan.domain.approval.ApprovalPolicy
import app.mizan.domain.approval.ApprovalRequest
import app.mizan.domain.approval.ApprovalState
import app.mizan.domain.approval.ApprovalWindow
import app.mizan.domain.model.Actor
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.TenantId
import app.mizan.domain.policy.ApprovalRecord
import app.mizan.domain.policy.SeparationOfDuties
import app.mizan.domain.policy.SodCode
import app.mizan.domain.security.DeviceBindingService
import app.mizan.service.authority.ServiceAuthority
import app.mizan.service.protocol.ExecutionRequest
import app.mizan.service.security.ServiceUser
import app.mizan.service.store.ServiceStores
import java.security.SecureRandom

/**
 * The desk where a person approves something.
 *
 * An approval is an object, not a boolean on a request: it names the proposal
 * it is about, the fingerprint of that proposal, the policy version that was
 * in force, the level the service decided it needs, the person who asked, the
 * person who answered, and when it dies. This class is the only place those
 * objects are created and decided, and it exists so that the act of approving
 * is a route with a rule rather than a field on an execution.
 *
 * Three rules are enforced here, and they are the reason this is not a
 * twelve-line controller:
 *
 *  - the *service* decides the ladder. A client cannot ask for an approval on
 *    a tool, amount or role the policy would not have asked for;
 *  - the fingerprint is computed by the service from the request, so the thing
 *    approved is the thing that will execute, byte for byte;
 *  - the person who answers must be a different person from the one who asked,
 *    with the rank the level demands, and -- when the deployment requires it --
 *    with a device proof bound to exactly that fingerprint.
 */
class ApprovalDesk(
    private val authority: ServiceAuthority,
    private val stores: ServiceStores?,
    private val devices: DeviceBindingService?,
    private val requireDeviceProof: Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val window: ApprovalWindow = ApprovalWindow(),
) {

    sealed interface Result {

        data class Created(val approval: ApprovalRequest) : Result

        /** The decision was recorded. [complete] is false when a second approver is still due. */
        data class Decided(val approval: ApprovalRequest, val complete: Boolean) : Result

        /** Refused. The code is a message key the app can render, never a sentence. */
        data class Refused(val code: String, val httpStatus: Int) : Result
    }

    private val policy = ApprovalPolicy(window)

    private val sod = SeparationOfDuties()

    /**
     * Opens an approval for a request the service has judged.
     *
     * The body carries the same tool, version and arguments an execution
     * would, so the ladder and the fingerprint are the service's own
     * computation. Asking for an approval on something the policy would not
     * have asked about is refused rather than registered.
     */
    fun create(
        request: ExecutionRequest,
        user: ServiceUser,
        proposalId: String,
        proposalRevision: Int,
    ): Result {
        val store = stores?.approvals ?: return Result.Refused("APPROVAL_STORE_UNAVAILABLE", 503)
        val ladder = when (val computed = authority.ladderFor(request, user)) {
            is ServiceAuthority.Ladder.Refused -> return Result.Refused(computed.code, computed.httpStatus)
            is ServiceAuthority.Ladder.Requires -> computed
        }
        val level = ladder.decision.approval
        if (level == ApprovalLevel.L0_NONE) return Result.Refused("APPROVAL_NOT_REQUIRED", 422)

        // Asking twice for the same thing is not two approvals: if one is
        // already waiting or granted for this exact fingerprint, it is the
        // answer, and the client gets it back instead of a duplicate.
        store.forProposal(proposalId)?.let { existing ->
            val live = existing.state == ApprovalState.PENDING || existing.state == ApprovalState.GRANTED
            if (live && existing.proposalFingerprint == ladder.fingerprint) {
                return Result.Created(existing)
            }
        }

        val now = clock()
        val approval = ApprovalRequest(
            id = newId(),
            proposalId = proposalId,
            tenantId = TenantId(request.tenantId),
            initiatorId = ActorId(user.actorId),
            proposalRevision = proposalRevision,
            proposalFingerprint = ladder.fingerprint,
            requiredLevel = level,
            policyVersionId = ladder.decision.policyVersionId,
            policyHash = ladder.decision.policyHash,
            state = ApprovalState.PENDING,
            createdAtMillis = now,
            expiresAtMillis = now + window.forLevel(level).toMillis(),
            approvals = emptyList(),
            decisions = emptyList(),
        )
        store.save(approval)
        return Result.Created(approval)
    }

    /**
     * Records one person's answer: granted, or a refusal in code.
     *
     * A level that needs two people records the first answer and stays
     * pending, because a dual approval that became granted on the first yes
     * would not be a dual approval.
     */
    fun decide(
        approvalId: String,
        user: ServiceUser,
        granted: Boolean,
        reasonCode: String?,
        deviceChallengeId: String?,
        deviceSignature: String?,
    ): Result {
        val store = stores?.approvals ?: return Result.Refused("APPROVAL_STORE_UNAVAILABLE", 503)
        val approval = store.get(approvalId) ?: return Result.Refused("APPROVAL_UNKNOWN", 404)
        if (approval.tenantId.value != user.tenantId) return Result.Refused("TENANT_MISMATCH", 403)
        if (!user.role.canAnswer()) return Result.Refused("SOD_ROLE_INSUFFICIENT", 422)

        val now = clock()
        when (approval.state) {
            ApprovalState.CONSUMED -> return Result.Refused("EXECUTION_ALREADY_RESOLVED", 409)
            ApprovalState.REJECTED -> return Result.Refused("APPROVAL_REJECTED", 422)
            ApprovalState.INVALIDATED -> return Result.Refused("APPROVAL_INVALIDATED", 409)
            ApprovalState.EXPIRED -> return Result.Refused("APPROVAL_EXPIRED", 409)
            ApprovalState.PENDING, ApprovalState.GRANTED -> Unit
        }
        if (now >= approval.expiresAtMillis) {
            store.save(approval.copy(state = ApprovalState.EXPIRED))
            return Result.Refused("APPROVAL_EXPIRED", 409)
        }

        if (!granted) {
            val code = reasonCode?.takeIf { CODE.matches(it) } ?: "APPROVAL_REFUSED_BY_APPROVER"
            val refused = approval.copy(
                state = ApprovalState.REJECTED,
                decisions = approval.decisions + ApprovalDecision(
                    approverId = ActorId(user.actorId),
                    approverLabel = user.displayName,
                    state = ApprovalState.REJECTED,
                    atEpochMillis = now,
                    reasonCode = code,
                ),
            )
            store.save(refused)
            return Result.Decided(refused, complete = true)
        }

        val proofFailure = proofFailure(approval, deviceChallengeId, deviceSignature)
        if (proofFailure != null) return proofFailure

        val actor = Actor(ActorId(user.actorId), user.displayName, user.role, TenantId(user.tenantId))
        val record = ApprovalRecord(actor, now)
        val verdict = sod.check(
            initiatorId = approval.initiatorId,
            initiatorTenant = approval.tenantId,
            approval = approval.requiredLevel,
            approvals = approval.approvals + record,
        )
        return when (verdict) {
            SodCode.SATISFIED -> {
                val updated = policy.grant(approval, record, now)
                store.save(updated)
                Result.Decided(updated, complete = true)
            }
            // A second approver is still due. The first answer is recorded so
            // the second person can see who answered before them.
            SodCode.NEED_SECOND_APPROVER -> {
                val updated = approval.copy(
                    approvals = approval.approvals + record,
                    decisions = approval.decisions + ApprovalDecision(
                        approverId = actor.id,
                        approverLabel = actor.displayName,
                        state = ApprovalState.PENDING,
                        atEpochMillis = now,
                        reasonCode = "APPROVAL_PARTIAL",
                    ),
                )
                store.save(updated)
                Result.Decided(updated, complete = false)
            }
            SodCode.SAME_ACTOR -> Result.Refused("SOD_SAME_ACTOR", 422)
            SodCode.TENANT_MISMATCH -> Result.Refused("TENANT_MISMATCH", 403)
            SodCode.ROLE_INSUFFICIENT -> Result.Refused("SOD_ROLE_INSUFFICIENT", 422)
            SodCode.NOT_REQUIRED, SodCode.NEED_SECOND_APPROVER ->
                // Unreachable: L0 approvals are never created, and the second
                // approver case is handled above.
                Result.Refused("APPROVAL_NOT_REQUIRED", 422)
        }
    }

    fun get(approvalId: String, user: ServiceUser): ApprovalRequest? =
        stores?.approvals?.get(approvalId)?.takeIf { it.tenantId.value == user.tenantId }

    /** The tenant's approvals, pending first: the list a manager actually needs. */
    fun list(user: ServiceUser, state: ApprovalState?): List<ApprovalRequest> =
        stores?.approvals?.forTenant(user.tenantId)
            ?.filter { state == null || it.state == state }
            ?.sortedByDescending { it.createdAtMillis }
            .orEmpty()

    /** The store, for the routes that only read it. */
    fun store(): ServiceStores? = stores

    private fun proofFailure(
        approval: ApprovalRequest,
        challengeId: String?,
        signature: String?,
    ): Result.Refused? {
        // Only the levels that move money or change a customer's system need a
        // device proof. An L1 self-confirmation is the person confirming their
        // own action, and asking for a signature there trains people to sign
        // without reading.
        if (approval.requiredLevel == ApprovalLevel.L1_USER_CONFIRMATION) return null
        val binding = devices ?: return null
        if (!requireDeviceProof) return null
        if (challengeId.isNullOrBlank() || signature.isNullOrBlank()) {
            return Result.Refused("AUTH_PROOF_MISSING", 401)
        }
        val verification = binding.verify(challengeId, signature, approval.proposalFingerprint)
        if (verification.valid) return null
        val status = if (verification.errorCode?.startsWith("AUTH") == true) 401 else 403
        return Result.Refused(verification.errorCode ?: "SECURITY_SIGNATURE_INVALID", status)
    }

    private fun newId(): String {
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        return "APR-" + bytes.joinToString("") { "%02X".format(it) }
    }

    private companion object {
        /** A reason is a code the app can render, never a sentence. */
        val CODE = Regex("[A-Z][A-Z0-9_]{2,40}")
    }
}

/**
 * Whether a role can answer an approval at all.
 *
 * An operator runs the machines and an auditor reads the record; neither
 * decides what the business does with its money. The level's own rank check
 * (in the separation-of-duties rules) still applies on top of this.
 */
private fun app.mizan.domain.model.Role.canAnswer(): Boolean =
    this != app.mizan.domain.model.Role.OPERATOR && this != app.mizan.domain.model.Role.AUDITOR
