package app.mizan.domain.policy

import app.mizan.domain.model.Actor
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.Money
import app.mizan.domain.model.RiskTier
import app.mizan.domain.model.Role
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.ToolName

/**
 * A threshold ladder is configuration, not a law of nature.
 * Amounts are minor units of [currency]. A missing ladder must not be
 * treated as USD.
 */
data class ThresholdLadder(
    val currency: String,
    val l1MaxMinor: Long,
    val l2MaxMinor: Long,
    val l3MaxMinor: Long,
    val source: String,
) {
    init {
        require(l1MaxMinor < l2MaxMinor && l2MaxMinor < l3MaxMinor) {
            "threshold ladder must be strictly increasing"
        }
    }
}

data class PolicyCatalog(
    val ladders: Map<String, ThresholdLadder>,
) {
    fun ladder(currency: String): ThresholdLadder? = ladders[currency.uppercase()]

    companion object {
        /**
         * Demo defaults. The UI must say they are simulation thresholds.
         * They are not a customer's legal policy.
         */
        val demo = PolicyCatalog(
            ladders = mapOf(
                "USD" to ThresholdLadder("USD", 100_000, 1_000_000, 2_500_000, "demo-default"),
                "EGP" to ThresholdLadder("EGP", 5_000_000, 50_000_000, 100_000_000, "demo-default"),
            ),
        )

        val empty = PolicyCatalog(emptyMap())
    }
}

data class PolicyRequest(
    val actor: Actor,
    val tool: ToolName,
    val amount: Money?,
    val destructive: Boolean,
    val customerBlocked: Boolean,
    val exceedsCredit: Boolean,
)

data class PolicyDecision(
    val allowed: Boolean,
    val approval: ApprovalLevel,
    val riskTier: RiskTier,
    val ruleId: String,
    val reasonCode: String,
    val requiresSeparationOfDuties: Boolean,
)

/**
 * Deterministic evaluation. Presentation lives in the app, not here.
 * This result is a preview unless the active authority says otherwise.
 */
class PolicyEvaluator(
    private val catalog: PolicyCatalog,
) {
    fun evaluate(request: PolicyRequest): PolicyDecision {
        val tool = request.tool
        if (request.actor.tenantId.value.isBlank()) {
            return deny("POL-TENANT", "TENANT_REQUIRED", ApprovalLevel.L5_MULTI_PARTY)
        }
        if (tool.readOnly && !request.destructive) {
            return PolicyDecision(
                allowed = true,
                approval = ApprovalLevel.L0_NONE,
                riskTier = RiskTier.R0_READ,
                ruleId = "POL-SAFE-READ",
                reasonCode = "SAFE_READ",
                requiresSeparationOfDuties = false,
            )
        }
        if (request.actor.role == Role.AUDITOR) {
            return deny("POL-AUDITOR-READONLY", "AUDITOR_READONLY", ApprovalLevel.L5_MULTI_PARTY)
        }
        if (request.customerBlocked) {
            return deny("POL-CUSTOMER-BLOCKED", "CUSTOMER_BLOCKED", ApprovalLevel.L5_MULTI_PARTY)
        }
        if (request.destructive || tool.destructive) {
            return PolicyDecision(
                allowed = true,
                approval = ApprovalLevel.L3_MANAGER,
                riskTier = RiskTier.R3_HIGH,
                ruleId = "POL-DESTRUCTIVE",
                reasonCode = "DESTRUCTIVE_REQUIRES_MANAGER",
                requiresSeparationOfDuties = true,
            )
        }
        val amount = request.amount
        if (amount == null || amount.minorUnits == 0L) {
            return PolicyDecision(
                allowed = true,
                approval = ApprovalLevel.L1_USER_CONFIRMATION,
                riskTier = RiskTier.R1_LOW,
                ruleId = "POL-NO-AMOUNT",
                reasonCode = "CONFIRM_NO_AMOUNT",
                requiresSeparationOfDuties = false,
            )
        }
        val ladder = catalog.ladder(amount.currency)
        if (ladder == null) {
            return PolicyDecision(
                allowed = true,
                approval = ApprovalLevel.L3_MANAGER,
                riskTier = RiskTier.R3_HIGH,
                ruleId = "POL-CURRENCY-UNCONFIGURED",
                reasonCode = "CURRENCY_LADDER_MISSING",
                requiresSeparationOfDuties = true,
            )
        }
        val creditBump = request.exceedsCredit
        val base = when {
            amount.minorUnits <= ladder.l1MaxMinor -> level(
                ApprovalLevel.L1_USER_CONFIRMATION,
                RiskTier.R1_LOW,
                "POL-THRESHOLD-L1",
                "THRESHOLD_L1",
                sod = false,
            )
            amount.minorUnits <= ladder.l2MaxMinor -> level(
                ApprovalLevel.L2_PRIVILEGED,
                RiskTier.R2_MEDIUM,
                "POL-THRESHOLD-L2",
                "THRESHOLD_L2",
                sod = false,
            )
            amount.minorUnits <= ladder.l3MaxMinor -> level(
                ApprovalLevel.L3_MANAGER,
                RiskTier.R3_HIGH,
                "POL-THRESHOLD-L3",
                "THRESHOLD_L3",
                sod = true,
            )
            else -> level(
                ApprovalLevel.L4_DUAL,
                RiskTier.R4_CRITICAL,
                "POL-THRESHOLD-L4",
                "THRESHOLD_L4",
                sod = true,
            )
        }
        if (!creditBump) return base
        val raised = if (base.approval.rank >= ApprovalLevel.L3_MANAGER.rank) {
            base.approval
        } else {
            ApprovalLevel.L3_MANAGER
        }
        val raisedRisk = if (base.riskTier.ordinal >= RiskTier.R3_HIGH.ordinal) base.riskTier else RiskTier.R3_HIGH
        return base.copy(
            approval = raised,
            riskTier = raisedRisk,
            ruleId = "POL-CREDIT-LIMIT",
            reasonCode = "CREDIT_LIMIT",
            requiresSeparationOfDuties = true,
        )
    }

    private fun level(
        approval: ApprovalLevel,
        risk: RiskTier,
        ruleId: String,
        reason: String,
        sod: Boolean,
    ) = PolicyDecision(true, approval, risk, ruleId, reason, sod)

    private fun deny(ruleId: String, reason: String, approval: ApprovalLevel) = PolicyDecision(
        allowed = false,
        approval = approval,
        riskTier = RiskTier.R4_CRITICAL,
        ruleId = ruleId,
        reasonCode = reason,
        requiresSeparationOfDuties = false,
    )
}

enum class SodCode {
    NOT_REQUIRED,
    SATISFIED,
    SAME_ACTOR,
    ROLE_INSUFFICIENT,
    TENANT_MISMATCH,
    NEED_SECOND_APPROVER,
}

data class ApprovalRecord(
    val approver: Actor,
    val atEpochMillis: Long,
)

class SeparationOfDuties {
    fun check(
        initiatorId: ActorId,
        initiatorTenant: TenantId,
        approval: ApprovalLevel,
        approvals: List<ApprovalRecord>,
    ): SodCode {
        if (approval == ApprovalLevel.L0_NONE) return SodCode.NOT_REQUIRED
        if (approvals.isEmpty()) return SodCode.NEED_SECOND_APPROVER
        if (approvals.any { it.approver.tenantId != initiatorTenant }) return SodCode.TENANT_MISMATCH
        if (approval == ApprovalLevel.L1_USER_CONFIRMATION) {
            val only = approvals.singleOrNull() ?: return SodCode.ROLE_INSUFFICIENT
            return if (only.approver.id == initiatorId) SodCode.SATISFIED else SodCode.ROLE_INSUFFICIENT
        }
        if (approvals.any { it.approver.id == initiatorId }) return SodCode.SAME_ACTOR
        val distinct = approvals.distinctBy { it.approver.id }
        return when (approval) {
            ApprovalLevel.L0_NONE -> SodCode.NOT_REQUIRED
            ApprovalLevel.L1_USER_CONFIRMATION -> SodCode.SATISFIED
            ApprovalLevel.L2_PRIVILEGED ->
                if (distinct.any { it.approver.role.canPrivileged() }) SodCode.SATISFIED
                else SodCode.ROLE_INSUFFICIENT
            ApprovalLevel.L3_MANAGER ->
                if (distinct.any { it.approver.role == Role.SALES_MANAGER }) SodCode.SATISFIED
                else SodCode.ROLE_INSUFFICIENT
            ApprovalLevel.L4_DUAL, ApprovalLevel.L5_MULTI_PARTY -> {
                val eligible = distinct.filter { it.approver.role.canPrivileged() }
                if (eligible.size >= 2) SodCode.SATISFIED else SodCode.NEED_SECOND_APPROVER
            }
        }
    }
}

private fun Role.canPrivileged(): Boolean =
    this == Role.SALES_MANAGER || this == Role.FINANCE_APPROVER
