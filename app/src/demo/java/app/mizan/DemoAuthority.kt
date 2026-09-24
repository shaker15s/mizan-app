package app.mizan

import app.mizan.domain.audit.AuditAppend
import app.mizan.domain.authority.AuthorityMode
import app.mizan.domain.authority.AuthorityOutcome
import app.mizan.domain.authority.ExecuteCommand
import app.mizan.domain.authority.ExecutionAuthority
import app.mizan.domain.error.AppError
import app.mizan.domain.error.DispatchState
import app.mizan.domain.execution.ExecutionEvent
import app.mizan.domain.execution.ExecutionPhase
import app.mizan.domain.execution.IdempotencyClaim
import app.mizan.domain.execution.IdempotencyDecision
import app.mizan.domain.execution.Transition
import app.mizan.domain.model.Actor
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.CachedOrder
import app.mizan.domain.model.EvidenceOrigin
import app.mizan.domain.model.ExecutionRecord
import app.mizan.domain.model.Money
import app.mizan.domain.model.ReconciliationCase
import app.mizan.domain.model.ReconciliationStatus
import app.mizan.domain.model.Role
import app.mizan.domain.model.TenantContext
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.ToolName
import app.mizan.domain.model.TrustReceipt
import app.mizan.domain.model.VerificationKind
import app.mizan.domain.policy.ApprovalRecord
import app.mizan.domain.policy.SodCode
import app.mizan.domain.security.Freshness
import app.mizan.domain.model.CanonicalJson
import app.mizan.domain.model.ReceiptId
import app.mizan.graph.AuthorityDeps
import java.util.UUID

fun createAuthority(deps: AuthorityDeps): ExecutionAuthority = SimulatedExecutionAuthority(deps)

class SimulatedExecutionAuthority(
    private val deps: AuthorityDeps,
) : ExecutionAuthority {
    override val mode = AuthorityMode.SIMULATION

    override suspend fun execute(command: ExecuteCommand): AuthorityOutcome {
        val proposal = command.proposal
        val existing = deps.executions.findByIdempotency(proposal.tenantId, proposal.idempotencyKey)
        when (
            val decision = deps.idempotency.decide(
                existing?.let {
                    IdempotencyClaim(it.idempotencyKey, it.id, it.phase, it.dispatch != DispatchState.NOT_SENT)
                },
            )
        ) {
            is IdempotencyDecision.Blocked ->
                return AuthorityOutcome.Refused(AppError.Conflict("IDEMPOTENCY", decision.reason.name))
            is IdempotencyDecision.Replay -> {
                val record = existing
                return if (record?.erpRecordId != null && record.phase == ExecutionPhase.VERIFIED) {
                    AuthorityOutcome.Verified(
                        record.id,
                        record.erpRecordId,
                        record.erpModel ?: "simulation",
                        VerificationKind.SIMULATED_READ_BACK,
                    )
                } else {
                    AuthorityOutcome.Refused(AppError.Conflict("IDEMPOTENCY", "replay"))
                }
            }
            IdempotencyDecision.Proceed -> Unit
        }
        val approvals = listOfNotNull(
            ApprovalRecord(command.approver, deps.time.now().toEpochMilli()),
            command.secondApprover?.let { ApprovalRecord(it, deps.time.now().toEpochMilli()) },
        )
        val sod = deps.sod.check(proposal.initiator.id, proposal.tenantId, proposal.policy.approval, approvals)
        if (sod != SodCode.SATISFIED && sod != SodCode.NOT_REQUIRED) {
            return AuthorityOutcome.Refused(AppError.Authorization("SOD_${sod.name}", sod.name))
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
                return AuthorityOutcome.Refused(AppError.Security("REAUTH", freshness.name))
            }
        }
        val terminal = deps.machine.run(path(proposal.policy.approval, command.simulateAmbiguous))
        if (terminal is Transition.Illegal) {
            return AuthorityOutcome.Refused(AppError.Conflict("ILLEGAL_TRANSITION", terminal.event))
        }
        if (command.simulateAmbiguous) {
            val case = ReconciliationCase(
                id = "REC-" + UUID.randomUUID().toString().take(8).uppercase(),
                executionId = proposal.executionId,
                traceId = proposal.traceId,
                tenantId = proposal.tenantId,
                tool = proposal.args.tool,
                intent = proposal.intent,
                idempotencyKey = proposal.idempotencyKey,
                candidateRecordIds = emptyList(),
                status = ReconciliationStatus.OPEN,
                notes = null,
                openedAt = deps.time.now(),
            )
            deps.cases.upsert(case)
            persist(proposal, command.approver, ExecutionPhase.RECONCILIATION_REQUIRED, null, DispatchState.SENT, "SIMULATED_UNCERTAIN", command.secondApprover)
            audit(proposal, command.approver.id.value, "SIMULATED_UNCERTAIN", ExecutionPhase.RECONCILIATION_REQUIRED.name)
            return AuthorityOutcome.Uncertain(proposal.executionId, "SIMULATED_UNCERTAIN", emptyList())
        }
        val recordId = "SIM-" + UUID.randomUUID().toString().take(8).uppercase()
        val amount = proposal.amount ?: Money(0, "XXX")
        val customer = (proposal.args as? app.mizan.domain.model.CreateDraftOrderArgs)?.customerName
            ?: proposal.intent.take(80)
        deps.readModels.upsertOrder(
            CachedOrder(
                id = recordId,
                tenantId = proposal.tenantId,
                customerName = customer,
                amount = amount,
                status = "simulated",
                summary = proposal.args.tool.wire,
                origin = EvidenceOrigin.SIMULATION,
                updatedAt = deps.time.now(),
            ),
        )
        val readBack = deps.readModels.order(proposal.tenantId, recordId)
        val matched = readBack != null &&
            readBack.customerName == customer &&
            readBack.amount == amount &&
            readBack.status == "simulated" &&
            readBack.origin == EvidenceOrigin.SIMULATION
        if (!matched) {
            val case = ReconciliationCase(
                id = "REC-" + UUID.randomUUID().toString().take(8).uppercase(),
                executionId = proposal.executionId,
                traceId = proposal.traceId,
                tenantId = proposal.tenantId,
                tool = proposal.args.tool,
                intent = proposal.intent,
                idempotencyKey = proposal.idempotencyKey,
                candidateRecordIds = listOfNotNull(readBack?.id),
                status = ReconciliationStatus.OPEN,
                notes = null,
                openedAt = deps.time.now(),
            )
            deps.cases.upsert(case)
            persist(proposal, command.approver, ExecutionPhase.RECONCILIATION_REQUIRED, recordId, DispatchState.SENT, "READ_BACK_MISMATCH", command.secondApprover)
            return AuthorityOutcome.Uncertain(proposal.executionId, "READ_BACK_MISMATCH", listOfNotNull(readBack?.id))
        }
        val receipt = TrustReceipt(
            id = ReceiptId("RCP-" + UUID.randomUUID().toString().take(8).uppercase()),
            traceId = proposal.traceId,
            executionId = proposal.executionId,
            tenantId = proposal.tenantId,
            tenantLabel = proposal.tenantId.value,
            initiatorId = proposal.initiator.id,
            initiatorLabel = proposal.initiator.displayName,
            approverIds = approvals.map { it.approver.id },
            approverLabels = approvals.map { it.approver.displayName },
            tool = proposal.args.tool,
            toolVersion = proposal.args.tool.version,
            policyRuleId = proposal.policy.ruleId,
            approval = proposal.policy.approval,
            riskTier = proposal.risk.tier,
            canonicalArgs = CanonicalJson.write(proposal.args.canonical()),
            idempotencyKey = proposal.idempotencyKey,
            erpRecordId = recordId,
            erpModel = "simulation",
            verification = VerificationKind.SIMULATED_READ_BACK,
            integrityClass = app.mizan.domain.audit.IntegrityClass.LOCAL_ONLY,
            origin = EvidenceOrigin.SIMULATION,
            createdAt = deps.time.now(),
            auditChainIndex = null,
        )
        deps.receipts.insert(receipt)
        persist(proposal, command.approver, ExecutionPhase.VERIFIED, recordId, DispatchState.SENT, null, command.secondApprover)
        audit(proposal, command.approver.id.value, "SIMULATED_CHECKED", recordId)
        return AuthorityOutcome.Verified(
            proposal.executionId,
            recordId,
            "simulation",
            VerificationKind.SIMULATED_READ_BACK,
        )
    }

    private fun path(approval: ApprovalLevel, ambiguous: Boolean): List<ExecutionEvent> = buildList {
        add(ExecutionEvent.Validate)
        add(ExecutionEvent.RiskEvaluated)
        if (approval == ApprovalLevel.L0_NONE) {
            add(ExecutionEvent.Authorize)
        } else {
            add(ExecutionEvent.RequestApproval)
            add(ExecutionEvent.Authorize)
        }
        add(ExecutionEvent.AcquireLease)
        add(ExecutionEvent.StartExecution)
        if (ambiguous) {
            add(ExecutionEvent.MarkAmbiguous)
            add(ExecutionEvent.RequireReconciliation)
        } else {
            add(ExecutionEvent.ErpAccepted)
            add(ExecutionEvent.StartVerification)
            add(ExecutionEvent.Verify)
        }
    }

    private suspend fun persist(
        proposal: app.mizan.domain.model.Proposal,
        approver: Actor,
        phase: ExecutionPhase,
        erpId: String?,
        dispatch: DispatchState,
        error: String?,
        secondApprover: Actor? = null,
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
                approverIds = listOfNotNull(approver.id, secondApprover?.id),
                erpRecordId = erpId,
                erpModel = if (erpId == null) null else "simulation",
                dispatch = dispatch,
                leaseExpiresAt = now.plusSeconds(30),
                createdAt = proposal.createdAt,
                updatedAt = now,
                errorCode = error,
                origin = EvidenceOrigin.SIMULATION,
            ),
        )
    }

    private suspend fun audit(
        proposal: app.mizan.domain.model.Proposal,
        actorId: String,
        action: String,
        detail: String,
    ) {
        deps.audit.append(
            AuditAppend(
                traceId = proposal.traceId.value,
                tenantId = proposal.tenantId,
                actorId = actorId,
                action = action,
                stateBefore = ExecutionPhase.EXECUTING.name,
                stateAfter = action,
                details = detail,
                timestampMillis = deps.time.now().toEpochMilli(),
            ),
        )
    }
}

object DemoDirectory {
    val alamal = TenantContext(TenantId("sim-alamal"), "Al-Amal Trading", "Simulation", "demo")
    val nile = TenantContext(TenantId("sim-nile"), "Nile Industrial", "Simulation", "demo")

    fun actors(tenant: TenantId): List<Actor> = listOf(
        Actor(ActorId("sim-rep"), "Amr Kamel", Role.SALES_REP, tenant),
        Actor(ActorId("sim-mgr"), "Tarek El-Sayed", Role.SALES_MANAGER, tenant),
        Actor(ActorId("sim-fin"), "Noha Mansour", Role.FINANCE_APPROVER, tenant),
        Actor(ActorId("sim-aud"), "Hisham Zaki", Role.AUDITOR, tenant),
    )

    val toolsForSeed = ToolName.entries
}
