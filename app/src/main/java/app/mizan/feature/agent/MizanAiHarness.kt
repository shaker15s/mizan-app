package app.mizan.feature.agent

import app.mizan.domain.agent.IntentInterpreter
import app.mizan.domain.agent.Interpretation
import app.mizan.domain.model.ConnectorCapabilities
import app.mizan.integration.ai.MizanAiIntegrationClient
import app.mizan.integration.ai.MizanAiPrompts

/**
 * High-performance, token-efficient AI harness for Wakeel ERP.
 * Integrates with the [MizanAiIntegrationClient] in the integration module.
 * Operates with lean, ERP-specific system prompts that strip out general-purpose
 * conversational overhead to drastically reduce token usage and improve inference speed.
 */
class MizanAiHarness(
    private val baseInterpreter: IntentInterpreter = IntentInterpreter(),
    private val aiClient: MizanAiIntegrationClient = MizanAiIntegrationClient(localInterpreter = baseInterpreter),
) {
    companion object {
        /**
         * Specialized lean system prompt tailored exclusively for Wakeel ERP actions.
         * Sourced directly from :integration module.
         */
        const val SYSTEM_PROMPT_CONCISE_ERP = MizanAiPrompts.LEAN_ERP_SYSTEM_PROMPT

        /**
         * Sovereign Governed ERP authority prompt with SoD and audit checks.
         */
        const val SYSTEM_PROMPT_GOVERNED = MizanAiPrompts.GOVERNED_ERP_SYSTEM_PROMPT
    }

    /**
     * Executes the AI interpretation pipeline with lean ERP prompt processing and enhanced
     * bilingual Arabic/English NLP. Zero tokens wasted on conversational chatter.
     */
    fun parse(
        input: String,
        capabilities: ConnectorCapabilities,
        customPrompt: String? = null,
        speedTier: String = "fast_tuned",
    ): Interpretation {
        return aiClient.parseErpAction(
            query = input,
            capabilities = capabilities,
            customPrompt = customPrompt,
        )
    }

    val lastInferenceLatencyMs: Long
        get() = aiClient.lastInferenceLatencyMs

    val lastTokensEstimated: Int
        get() = aiClient.lastTokensEstimated
}
