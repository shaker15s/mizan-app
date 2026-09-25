package app.mizan.integration.ai

/**
 * Lean, token-efficient system prompts for MIZAN ERP actions.
 *
 * Tailored exclusively for enterprise ERP operations (Odoo / Simulation ledger).
 * All general-purpose conversational overhead, polite filler, and chatbot etiquette
 * have been stripped out to minimize token consumption and maximize inference speed.
 */
object MizanAiPrompts {

    /**
     * Highly specific, lean system prompt tailored strictly for MIZAN ERP actions.
     * Strips conversational boilerplate to achieve ultra-fast inference and low token cost.
     */
    const val LEAN_ERP_SYSTEM_PROMPT: String = """SYSTEM: MIZAN ERP Action Engine.
ROLE: Extract governed ERP tool invocations from user input into deterministic JSON. Zero conversational text.
TOOLS:
- stock.availability: {"sku": "string"}
- customer.search: {"query": "string"}
- sales.order.create_draft: {"customerName": "string", "amount": "<num>", "currency": "<USD|EGP|EUR|SAR|AED>", "items": "string"}
- sales.order.cancel: {"orderId": "string", "reason": "string"}
- invoice.create_from_order: {"orderId": "string"}
- payment.register: {"invoiceId": "string", "amount": "<num>", "currency": "<USD|EGP|EUR|SAR|AED>"}
- analytics.sales_summary: {"period": "current|month|year"}
RULES:
1. Output JSON only: {"tool": "<name>", "args": { ... }} OR {"clarify": "<tool_optional>", "missing": ["<field>"]}
2. Never invent amounts, currencies, or customers. If absent, set clarify with missing fields.
3. No greetings, no explanations, no conversational markdown."""

    /**
     * Governed system prompt with separation-of-duties and audit invariants.
     */
    const val GOVERNED_ERP_SYSTEM_PROMPT: String = """SYSTEM: MIZAN Sovereign ERP Authority.
ROLE: Governed ERP extraction with separation-of-duties (SoD) & cryptographic audit proof.
TOOLS: stock.availability, customer.search, sales.order.create_draft, sales.order.cancel, invoice.create_from_order, payment.register, analytics.sales_summary.
RULES: Zero conversational overhead. Output pure structured arguments only."""

    /**
     * Estimates token count based on typical subword tokenization (approx 3.5-4 chars per token).
     */
    fun estimateTokens(text: String): Int =
        (text.length / 3.8).toInt().coerceAtLeast(1)

    /**
     * Strips any conversational overhead, polite intros, or markdown blocks
     * that an LLM might prepend or append, ensuring only pure actionable JSON remains.
     */
    fun stripConversationalOverhead(raw: String): String {
        var text = raw.trim()

        // Strip conversational prefixes like "Certainly!", "Here is...", "Sure, I parsed this:"
        val conversationalPrefixes = listOf(
            Regex("""^(?:certainly|sure|here is|here's|of course|as an ai|i have parsed|parsed intent)[^:{}\n]*[:\n-]*""", RegexOption.IGNORE_CASE),
            Regex("""^(?:بالتأكيد|حاضر|إليك النتيجة|تم تحليل الطلب)[^:{}\n]*[:\n-]*""", RegexOption.IGNORE_CASE),
        )
        for (pattern in conversationalPrefixes) {
            text = text.replace(pattern, "").trim()
        }

        // Strip markdown code fences if present (```json ... ``` or ``` ... ```)
        if (text.startsWith("```")) {
            val startIdx = text.indexOf('\n')
            if (startIdx >= 0) {
                text = text.substring(startIdx + 1)
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length - 3)
            }
            text = text.trim()
        }

        // Extract JSON object if surrounded by remaining chatter
        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        if (firstBrace in 0..<lastBrace) {
            text = text.substring(firstBrace, lastBrace + 1)
        }

        return text.trim()
    }
}
