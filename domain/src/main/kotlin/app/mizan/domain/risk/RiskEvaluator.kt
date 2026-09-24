package app.mizan.domain.risk

import app.mizan.domain.model.RiskTier
import app.mizan.domain.model.ToolName

/**
 * Qualitative classification. Factors are the explanation.
 * There is no probability and no single "objective" score.
 */
enum class FactorWeight { NONE, LOW, MEDIUM, HIGH, CRITICAL }

data class RiskFactor(
    val code: String,
    val weight: FactorWeight,
)

data class RiskAssessment(
    val tier: RiskTier,
    val factors: List<RiskFactor>,
    val classificationNoteCode: String = "RISK_IS_CLASSIFICATION",
)

data class RiskInput(
    val tool: ToolName,
    val destructive: Boolean,
    val amountTier: RiskTier,
    val ambiguous: Boolean,
    val injectionSuspected: Boolean,
    val customerNamed: Boolean,
    val externalUncertain: Boolean,
    val sensitiveData: Boolean,
)

class RiskEvaluator {
    fun assess(input: RiskInput): RiskAssessment {
        val factors = buildList {
            add(RiskFactor("FINANCIAL_EXPOSURE", input.amountTier.toWeight()))
            if (input.destructive) add(RiskFactor("DESTRUCTIVE", FactorWeight.HIGH))
            if (!input.customerNamed && !input.tool.readOnly) {
                add(RiskFactor("CUSTOMER_UNSPECIFIED", FactorWeight.MEDIUM))
            }
            if (input.ambiguous) add(RiskFactor("AMBIGUOUS_INTENT", FactorWeight.HIGH))
            if (input.injectionSuspected) add(RiskFactor("INJECTION_SUSPECTED", FactorWeight.CRITICAL))
            if (input.externalUncertain) add(RiskFactor("EXTERNAL_UNCERTAINTY", FactorWeight.HIGH))
            if (input.sensitiveData) add(RiskFactor("SENSITIVE_DATA", FactorWeight.MEDIUM))
            if (input.tool == ToolName.REGISTER_PAYMENT) {
                add(RiskFactor("PAYMENT_MUTATION", FactorWeight.HIGH))
            }
        }.filter { it.weight != FactorWeight.NONE }
        val tier = factors.maxOfOrNull { it.weight.toTier() } ?: RiskTier.R0_READ
        return RiskAssessment(tier = maxOf(tier, input.amountTier), factors = factors)
    }
}

private fun RiskTier.toWeight(): FactorWeight = when (this) {
    RiskTier.R0_READ -> FactorWeight.NONE
    RiskTier.R1_LOW -> FactorWeight.LOW
    RiskTier.R2_MEDIUM -> FactorWeight.MEDIUM
    RiskTier.R3_HIGH -> FactorWeight.HIGH
    RiskTier.R4_CRITICAL -> FactorWeight.CRITICAL
}

private fun FactorWeight.toTier(): RiskTier = when (this) {
    FactorWeight.NONE -> RiskTier.R0_READ
    FactorWeight.LOW -> RiskTier.R1_LOW
    FactorWeight.MEDIUM -> RiskTier.R2_MEDIUM
    FactorWeight.HIGH -> RiskTier.R3_HIGH
    FactorWeight.CRITICAL -> RiskTier.R4_CRITICAL
}
