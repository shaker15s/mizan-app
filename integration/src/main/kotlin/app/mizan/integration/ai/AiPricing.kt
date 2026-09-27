package app.mizan.integration.ai

import app.mizan.domain.ai.ModelPricing

/**
 * What each vendor's model costs, side by side.
 *
 * The harness reports accuracy per model, and this reports the price of that
 * accuracy in the same units. A comparison that only names the winner and not
 * the bill is the comparison that quietly buys the most expensive model.
 */
data class AiVendorPricingReport(val entries: List<Entry>) {

    data class Entry(val modelId: String, val pricing: ModelPricing) {
        /** Dollars for a thousand conversations of a thousand tokens each. */
        fun usdPerThousandCalls(tokensIn: Int = 1_000, tokensOut: Int = 250): Double =
            pricing.costUsd(tokensIn, tokensOut) * 1_000
    }

    val cheapestId: String get() = entries.minByOrNull { it.usdPerThousandCalls() }?.modelId.orEmpty()

    val dearestId: String get() = entries.maxByOrNull { it.usdPerThousandCalls() }?.modelId.orEmpty()

    fun table(): String = buildString {
        appendLine("model                 usdPerMillionIn  usdPerMillionOut  usdPer1000Calls")
        for (entry in entries.sortedBy { it.usdPerThousandCalls() }) {
            appendLine(
                "%-21s %15.2f %17.2f %17.4f".format(
                    entry.modelId,
                    entry.pricing.inputUsdPerMillion,
                    entry.pricing.outputUsdPerMillion,
                    entry.usdPerThousandCalls(),
                ),
            )
        }
    }.trimEnd()

    companion object {
        fun of(prices: List<Pair<String, ModelPricing>>): AiVendorPricingReport =
            AiVendorPricingReport(prices.map { Entry(it.first, it.second) })
    }
}
