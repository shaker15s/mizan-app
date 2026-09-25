package app.mizan.domain.agent

import app.mizan.domain.model.Actor
import app.mizan.domain.model.CachedCustomer
import app.mizan.domain.model.ConnectorCapabilities
import app.mizan.domain.model.CreateDraftOrderArgs
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.Idempotency
import app.mizan.domain.model.Money
import app.mizan.domain.model.Proposal
import app.mizan.domain.model.ProposalId
import app.mizan.domain.model.RegisterPaymentArgs
import app.mizan.domain.model.TimeSource
import app.mizan.domain.model.ToolName
import app.mizan.domain.model.TraceId
import app.mizan.domain.policy.PolicyEvaluator
import app.mizan.domain.policy.PolicyRequest
import app.mizan.domain.risk.RiskEvaluator
import app.mizan.domain.risk.RiskInput
import java.util.UUID

sealed interface ProposalResult {
    data class Proposed(val proposal: Proposal) : ProposalResult
    data class Clarify(val interpretation: Interpretation.NeedsClarification) : ProposalResult
    data class Rejected(val reasonCode: String) : ProposalResult
    data class Unsupported(val tool: ToolName) : ProposalResult
}

class ProposalService(
    private val interpreter: IntentInterpreter,
    private val policy: PolicyEvaluator,
    private val risk: RiskEvaluator,
    private val time: TimeSource,
    private val policyIsPreview: Boolean,
) {
    fun propose(
        intent: String,
        actor: Actor,
        capabilities: ConnectorCapabilities,
        customers: List<CachedCustomer> = emptyList(),
    ): ProposalResult {
        val interpreted = interpreter.interpret(intent, capabilities)
        return propose(interpreted, intent, actor, customers)
    }

    fun propose(
        interpreted: Interpretation,
        intent: String,
        actor: Actor,
        customers: List<CachedCustomer> = emptyList(),
    ): ProposalResult {
        return when (interpreted) {
            is Interpretation.Rejected -> ProposalResult.Rejected(interpreted.reasonCode)
            is Interpretation.Unsupported -> ProposalResult.Unsupported(interpreted.tool)
            is Interpretation.NeedsClarification -> ProposalResult.Clarify(interpreted)
            is Interpretation.Ready -> evaluateReady(interpreted, intent, actor, customers)
        }
    }

    private fun evaluateReady(
        interpreted: Interpretation.Ready,
        intent: String,
        actor: Actor,
        customers: List<CachedCustomer>,
    ): ProposalResult {
        val amount = amountOf(interpreted.args)
        val customer = customers.firstOrNull { cached ->
            val name = (interpreted.args as? CreateDraftOrderArgs)?.customerName
            name != null && cached.name.equals(name, ignoreCase = true)
        }
        val blocked = customer?.status.equals("blocked", ignoreCase = true)
        val exceeds = customer?.let { credit ->
            val limit = credit.creditLimit
            val balance = credit.balance
            amount != null && limit != null && balance != null &&
                limit.currency == amount.currency && balance.currency == amount.currency &&
                (balance + amount) > limit
        } ?: false
        val decision = policy.evaluate(
            PolicyRequest(
                actor = actor,
                tool = interpreted.tool,
                amount = amount,
                destructive = interpreted.tool.destructive,
                customerBlocked = blocked,
                exceedsCredit = exceeds,
            ),
        )
        if (!decision.allowed) return ProposalResult.Rejected(decision.reasonCode)
        val assessment = risk.assess(
            RiskInput(
                tool = interpreted.tool,
                destructive = interpreted.tool.destructive,
                amountTier = decision.riskTier,
                ambiguous = false,
                injectionSuspected = false,
                customerNamed = customer != null || interpreted.args is CreateDraftOrderArgs,
                externalUncertain = policyIsPreview,
                sensitiveData = interpreted.tool == ToolName.REGISTER_PAYMENT,
            ),
        )
        val trace = TraceId("TRC-" + UUID.randomUUID().toString().take(8).uppercase())
        val executionId = ExecutionId("EXE-" + UUID.randomUUID().toString().take(8).uppercase())
        return ProposalResult.Proposed(
            Proposal(
                id = ProposalId("PRP-" + UUID.randomUUID().toString().take(8).uppercase()),
                traceId = trace,
                executionId = executionId,
                tenantId = actor.tenantId,
                initiator = actor,
                intent = intent,
                args = interpreted.args,
                amount = amount,
                policy = decision,
                risk = assessment,
                idempotencyKey = Idempotency.key(actor.tenantId, interpreted.tool, interpreted.args),
                createdAt = time.now(),
                policyIsPreview = policyIsPreview,
            ),
        )
    }

    private fun amountOf(args: app.mizan.domain.model.ToolArgs): Money? = when (args) {
        is CreateDraftOrderArgs -> args.amount
        is RegisterPaymentArgs -> args.amount
        else -> null
    }
}
