package app.mizan

import app.mizan.domain.audit.AuditAppend
import app.mizan.domain.authority.AuthorityMode
import app.mizan.domain.authority.AuthorityOutcome
import app.mizan.domain.authority.ExecuteCommand
import app.mizan.domain.authority.ExecutionAuthority
import app.mizan.domain.authority.ReceiptEvidence
import app.mizan.domain.receipt.ReceiptTrust
import app.mizan.domain.error.AppError
import app.mizan.domain.error.DispatchState
import app.mizan.domain.execution.ExecutionEvent
import app.mizan.domain.execution.ExecutionPhase
import app.mizan.domain.execution.IdempotencyClaim
import app.mizan.domain.execution.IdempotencyDecision
import app.mizan.domain.execution.Transition
import app.mizan.domain.model.VerificationKind
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.CanonicalJson
import app.mizan.domain.model.EvidenceOrigin
import app.mizan.domain.model.ExecutionRecord
import app.mizan.domain.model.Proposal
import app.mizan.domain.model.ReconciliationCase
import app.mizan.domain.model.ReconciliationStatus
import app.mizan.domain.security.Freshness
import app.mizan.graph.AuthorityDeps
import app.mizan.integration.api.MizanApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Production and staging. The device does not call an ERP and does not
 * invent a result when the service is missing or the response is incomplete.
 */
class RemoteExecutionAuthority(
    private val deps: AuthorityDeps,
) : ExecutionAuthority {
    override val mode = AuthorityMode.REMOTE

    override suspend fun execute(command: ExecuteCommand): AuthorityOutcome {
        val base = deps.apiBaseUrl
        if (base.isBlank()) {
            return AuthorityOutcome.Refused(AppError.Configuration("API_NOT_CONFIGURED", "no service url"))
        }
        if (!base.startsWith("https://")) {
            return AuthorityOutcome.Refused(AppError.Configuration("API_URL_NOT_HTTPS", "rejected url"))
        }
        if (deps.token().isNullOrBlank()) {
            return AuthorityOutcome.Refused(AppError.Authentication("SESSION_MISSING", "no session token"))
        }
        val proposal = command.proposal
        priorOutcome(proposal)?.let { return it }
        if (proposal.policy.approval == ApprovalLevel.L4_DUAL && command.secondApprover == null) {
            return AuthorityOutcome.Refused(
                AppError.Authorization("SOD_NEED_SECOND_APPROVER", "client cannot appoint a second approver"),
            )
        }
        if (proposal.policy.approval != ApprovalLevel.L0_NONE) {
            val freshness = deps.reauth.check(
                command.proof,
                proposal.executionId.value,
                command.approver.id,
                command.approver.tenantId,
                proposal.policy.approval,
            )
            if (freshness != Freshness.FRESH) {
                return AuthorityOutcome.Refused(
                    AppError.Security("BIOMETRIC_REAUTH_REQUIRED", "Biometric authentication required: ${freshness.name}"),
                )
            }
        }
        val walked = deps.machine.run(pathToExecuting(proposal.policy.approval))
        if (walked is Transition.Illegal) {
            return AuthorityOutcome.Refused(AppError.Conflict("ILLEGAL_TRANSITION", walked.event))
        }
        persist(proposal, command, ExecutionPhase.EXECUTING, DispatchState.SENT, null)
        val client = MizanApiClient(base, deps.token)
        val outcome = withContext(Dispatchers.IO) {
            // The approval the caller obtained, and the device proof that
            // answers for it, travel with the write. The service recomputes
            // the fingerprint and refuses a claim it cannot verify, so this is
            // a claim and not a permission.
            client.execute(proposal, command.approver.id.value, command.approval)
        }
        val checked = withContext(Dispatchers.IO) { verifyReceipt(base, outcome, proposal) }
        when (checked) {
            is AuthorityOutcome.Verified -> persist(
                proposal,
                command,
                ExecutionPhase.VERIFIED,
                DispatchState.SENT,
                checked.receipt?.let { "${it.reasonCode}:${it.receiptId}" },
                checked.erpRecordId,
                checked.erpModel,
            )
            is AuthorityOutcome.AcceptedUnverified -> persist(
                proposal,
                command,
                ExecutionPhase.ERP_ACCEPTED,
                DispatchState.SENT,
                checked.messageCode,
            )
            is AuthorityOutcome.Uncertain -> {
                openCase(proposal, checked.candidateRecordIds, checked.messageCode)
                persist(
                    proposal,
                    command,
                    ExecutionPhase.RECONCILIATION_REQUIRED,
                    DispatchState.SENT,
                    checked.messageCode,
                )
            }
            is AuthorityOutcome.Refused -> persist(
                proposal,
                command,
                ExecutionPhase.ERP_FAILURE,
                DispatchState.SENT,
                checked.error.code,
            )
        }
        return checked
    }

    /**
     * Asks the authority for the receipt of a verified write and checks it here.
     *
     * The device is the last link of the chain that starts in the ERP, and this
     * is the only link it can check for itself. When this build pinned no key
     * the verdict is that it did not check, which is a different statement from
     * "the receipt is good" and is rendered as one.
     */
    private suspend fun verifyReceipt(
        base: String,
        outcome: AuthorityOutcome,
        proposal: Proposal,
    ): AuthorityOutcome {
        val verified = outcome as? AuthorityOutcome.Verified ?: return outcome
        val receiptId = verified.receipt?.receiptId ?: return outcome
        val keys = PinnedReceiptKeys.of()
        if (keys.isEmpty()) return outcome
        val client = app.mizan.integration.api.GovernanceApiClient(base, deps.token)
        val inspection = client.verifyReceipt(
            receiptId = receiptId,
            pinnedKeys = keys,
            expected = app.mizan.domain.receipt.ReceiptExpectation(
                tenantId = proposal.tenantId.value,
                executionId = proposal.executionId.value,
                // The proposal's own fingerprint, computed on the device from
                // the same fields the service hashes. If the receipt is about
                // a different proposal, that is not a verification.
                proposalFingerprint = proposal.fingerprint,
                erpRecordId = verified.erpRecordId,
            ),
        )
        val evidence = when (inspection) {
            is app.mizan.integration.api.RemoteResult.Refused ->
                // The receipt could not be fetched: the device has not checked
                // it, which is not the same as checking it and finding it bad.
                ReceiptEvidence(receiptId, ReceiptTrust.NOT_CHECKED, inspection.code)
            is app.mizan.integration.api.RemoteResult.Ok ->
                ReceiptEvidence(receiptId, inspection.value.trust, inspection.value.reasonCode)
        }
        return verified.copy(receipt = evidence)
    }

    private suspend fun priorOutcome(proposal: Proposal): AuthorityOutcome? {
        val existing = deps.executions.findByIdempotency(proposal.tenantId, proposal.idempotencyKey)
        val decision = deps.idempotency.decide(
            existing?.let {
                IdempotencyClaim(it.idempotencyKey, it.id, it.phase, it.dispatch != DispatchState.NOT_SENT)
            },
        )
        return when (decision) {
            IdempotencyDecision.Proceed -> null
            is IdempotencyDecision.Blocked ->
                AuthorityOutcome.Refused(AppError.Conflict("IDEMPOTENCY", decision.reason.name))
            is IdempotencyDecision.Replay -> {
                val record = existing ?: return AuthorityOutcome.Refused(AppError.Conflict("IDEMPOTENCY", "replay"))
                val recordId = record.erpRecordId
                val model = record.erpModel
                when {
                    record.phase == ExecutionPhase.VERIFIED && !recordId.isNullOrBlank() && !model.isNullOrBlank() ->
                        AuthorityOutcome.Verified(record.id, recordId, model, VerificationKind.READ_BACK)
                    record.phase == ExecutionPhase.ERP_ACCEPTED ||
                        record.phase == ExecutionPhase.VERIFICATION_PENDING ->
                        AuthorityOutcome.AcceptedUnverified(record.id, "ACCEPTED_NOT_VERIFIED")
                    else -> AuthorityOutcome.Refused(AppError.Conflict("IDEMPOTENCY", "replay"))
                }
            }
        }
    }

    private fun pathToExecuting(approval: ApprovalLevel): List<ExecutionEvent> = buildList {
        add(ExecutionEvent.Validate)
        add(ExecutionEvent.RiskEvaluated)
        if (approval == ApprovalLevel.L0_NONE) add(ExecutionEvent.Authorize) else {
            add(ExecutionEvent.RequestApproval)
            add(ExecutionEvent.Authorize)
        }
        add(ExecutionEvent.AcquireLease)
        add(ExecutionEvent.StartExecution)
    }

    private suspend fun openCase(proposal: Proposal, candidates: List<String>, note: String) {
        deps.cases.upsert(
            ReconciliationCase(
                id = "REC-" + UUID.randomUUID().toString().take(8).uppercase(),
                executionId = proposal.executionId,
                traceId = proposal.traceId,
                tenantId = proposal.tenantId,
                tool = proposal.args.tool,
                intent = proposal.intent,
                idempotencyKey = proposal.idempotencyKey,
                candidateRecordIds = candidates,
                status = ReconciliationStatus.OPEN,
                notes = note,
                openedAt = deps.time.now(),
            ),
        )
    }

    private suspend fun persist(
        proposal: Proposal,
        command: ExecuteCommand,
        phase: ExecutionPhase,
        dispatch: DispatchState,
        error: String?,
        erpId: String? = null,
        erpModel: String? = null,
    ) {
        val now = deps.time.now()
        deps.executions.upsert(
            ExecutionRecord(
                id = proposal.executionId,
                traceId = proposal.traceId,
                proposalId = proposal.id,
                tenantId = proposal.tenantId,
                initiatorId = proposal.initiator.id,
                tool = proposal.args.tool,
                toolVersion = proposal.args.tool.version,
                intent = proposal.intent,
                phase = phase,
                idempotencyKey = proposal.idempotencyKey,
                canonicalArgs = CanonicalJson.write(proposal.args.canonical()),
                amount = proposal.amount,
                approval = proposal.policy.approval,
                riskTier = proposal.risk.tier,
                policyRuleId = proposal.policy.ruleId,
                approverIds = listOfNotNull(command.approver.id, command.secondApprover?.id),
                erpRecordId = erpId,
                erpModel = erpModel,
                dispatch = dispatch,
                leaseExpiresAt = now.plusSeconds(45),
                createdAt = proposal.createdAt,
                updatedAt = now,
                errorCode = error,
                origin = EvidenceOrigin.SERVICE,
            ),
        )
        deps.audit.append(
            AuditAppend(
                traceId = proposal.traceId.value,
                tenantId = proposal.tenantId,
                actorId = command.approver.id.value,
                action = phase.name,
                stateBefore = ExecutionPhase.EXECUTING.name,
                stateAfter = phase.name,
                details = error ?: erpId.orEmpty(),
                timestampMillis = now.toEpochMilli(),
            ),
        )
    }
}
