package app.mizan.integration.ai

import app.mizan.domain.agent.InjectionGuard
import app.mizan.domain.agent.Interpretation
import app.mizan.domain.agent.MissingField
import app.mizan.domain.ai.ModelPricing
import app.mizan.domain.ai.ModelProvider
import app.mizan.domain.ai.ProviderAnswer
import app.mizan.domain.model.CancelOrderArgs
import app.mizan.domain.model.ConnectorCapabilities
import app.mizan.domain.model.CreateDraftOrderArgs
import app.mizan.domain.model.CreateInvoiceArgs
import app.mizan.domain.model.CustomerSearchArgs
import app.mizan.domain.model.Money
import app.mizan.domain.model.RegisterPaymentArgs
import app.mizan.domain.model.SalesSummaryArgs
import app.mizan.domain.model.StockLookupArgs
import app.mizan.domain.model.ToolArgs
import app.mizan.domain.model.ToolName
import app.mizan.integration.api.JsonText

/**
 * One HTTP POST, and what came back.
 *
 * A seam, not an abstraction for its own sake: every provider is tested
 * against a scripted transport that returns the exact bytes a vendor returns,
 * which is the only way to prove the adapter reads the right field of the
 * right envelope without a network and without a key.
 */
interface AiTransport {
    data class Reply(val status: Int, val body: String)

    fun post(url: String, headers: Map<String, String>, body: String): Reply
}

/**
 * A model that answered, and what the answer cost.
 *
 * [tokensIn] and [tokensOut] come from the vendor's own usage block; when the
 * vendor does not report usage, zero is recorded rather than a guess, because
 * a guessed token count is a guessed price.
 */
data class ModelReply(
    val status: Int,
    val body: String,
)

/**
 * The vendors this deployment can talk to.
 *
 * A vendor is a wire format: where the key goes, what the envelope looks like,
 * which field carries the answer, and what a thousand tokens cost. Adding one
 * must not touch the harness, the pipeline or the policy.
 */
enum class AiVendor(
    val id: String,
    val defaultEndpoint: String,
    val pricing: ModelPricing,
) {
    OPENAI(
        id = "openai",
        defaultEndpoint = "https://api.openai.com/v1/chat/completions",
        pricing = ModelPricing(inputUsdPerMillion = 2.50, outputUsdPerMillion = 10.00),
    ),
    ANTHROPIC(
        id = "anthropic",
        defaultEndpoint = "https://api.anthropic.com/v1/messages",
        pricing = ModelPricing(inputUsdPerMillion = 3.00, outputUsdPerMillion = 15.00),
    ),
    GEMINI(
        id = "gemini",
        defaultEndpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
        pricing = ModelPricing(inputUsdPerMillion = 0.30, outputUsdPerMillion = 2.50),
    ),
}

/**
 * A provider for one vendor.
 *
 * The three rules that make this safe to put in front of a governed product:
 *
 *  - the injection guard runs before the model sees the text and after it
 *    answers, so neither a person nor a record can smuggle an instruction in;
 *  - the model's answer is parsed strictly. An unknown tool, an unreadable
 *    envelope or a missing argument becomes a clarification or a refusal,
 *    never a default -- a defaulted amount is an amount nobody chose;
 *  - the model returns an [Interpretation], and an interpretation still has to
 *    pass validation, entity resolution, policy and approval before anything
 *    is written. The model is asked; it does not decide.
 */
class VendorModelProvider(
    private val vendor: AiVendor,
    private val transport: AiTransport,
    private val apiKeyProvider: () -> String?,
    private val model: String,
    private val prompt: AiPrompt,
    private val endpoint: String = vendor.defaultEndpoint,
    private val guard: InjectionGuard = InjectionGuard(),
    override val pricing: ModelPricing = vendor.pricing,
) : ModelProvider {

    override val id: String get() = "${vendor.id}:$model"

    override val promptVersion: String get() = prompt.version

    override fun interpret(text: String, capabilities: ConnectorCapabilities): ProviderAnswer {
        val trimmed = text.trim()
        if (trimmed.length < 2) {
            return ProviderAnswer(
                Interpretation.NeedsClarification(null, listOf(MissingField.QUERY), emptyList()),
            )
        }
        if (guard.suspect(trimmed)) {
            // Asked here rather than after the answer: a model that is
            // instructed persuasively must not be the thing standing between
            // an instruction and the ERP.
            return ProviderAnswer(Interpretation.Rejected("INJECTION_BLOCKED"))
        }
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) {
            return ProviderAnswer(Interpretation.Rejected("MODEL_NOT_CONFIGURED"))
        }
        val request = try {
            buildRequest(trimmed, key)
        } catch (error: IllegalArgumentException) {
            return ProviderAnswer(Interpretation.Rejected("MODEL_REQUEST_UNBUILDABLE"))
        }
        val reply = transport.post(request.url, request.headers, request.body)
        if (reply.status !in 200..299) {
            return ProviderAnswer(
                Interpretation.Rejected("MODEL_HTTP_${reply.status}"),
                raw = reply.body.take(200),
            )
        }
        val answer = extractAnswer(reply.body) ?: return ProviderAnswer(
            Interpretation.Rejected("MODEL_ANSWER_UNREADABLE"),
            raw = reply.body.take(200),
        )
        val usage = extractUsage(reply.body)
        val interpretation = StructuredOutput.parse(
            content = answer,
            capabilities = capabilities,
            guard = guard,
        )
        return ProviderAnswer(
            interpretation = interpretation,
            tokensIn = usage.first,
            tokensOut = usage.second,
            raw = answer.take(500),
        )
    }

    // ------------------------------------------------------------- the wire

    private data class WireRequest(val url: String, val headers: Map<String, String>, val body: String)

    private fun buildRequest(text: String, key: String): WireRequest = when (vendor) {
        AiVendor.OPENAI -> WireRequest(
            url = endpoint,
            headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $key"),
            body = """{"model":"$model","temperature":0,"response_format":{"type":"json_object"},""" +
                """"messages":[{"role":"system","content":${jsonString(prompt.text)}},""" +
                """{"role":"user","content":${jsonString(text)}}]}""",
        )
        AiVendor.ANTHROPIC -> WireRequest(
            url = endpoint,
            headers = mapOf(
                "Content-Type" to "application/json",
                "x-api-key" to key,
                "anthropic-version" to "2023-06-01",
            ),
            body = """{"model":"$model","max_tokens":512,"temperature":0,"system":${jsonString(prompt.text)},""" +
                """"messages":[{"role":"user","content":${jsonString(text)}}]}""",
        )
        AiVendor.GEMINI -> WireRequest(
            url = if (endpoint.contains("?")) "$endpoint&key=$key" else "$endpoint?key=$key",
            headers = mapOf("Content-Type" to "application/json"),
            body = """{"systemInstruction":{"parts":[{"text":${jsonString(prompt.text)}}]},""" +
                """"contents":[{"role":"user","parts":[{"text":${jsonString(text)}}]}],""" +
                """"generationConfig":{"temperature":0,"responseMimeType":"application/json"}}""",
        )
    }

    /** The model's text, out of the envelope its vendor wraps it in. */
    private fun extractAnswer(body: String): String? {
        val document = JsonText.parse(body) as? JsonText.Obj ?: return null
        val raw = when (vendor) {
            AiVendor.OPENAI -> document.array("choices").firstOrNull()
                .let { it as? JsonText.Obj }?.obj("message")?.text("content")
            AiVendor.ANTHROPIC -> document.array("content").firstOrNull()
                .let { it as? JsonText.Obj }?.text("text")
            AiVendor.GEMINI -> (document.array("candidates").firstOrNull() as? JsonText.Obj)
                ?.obj("content")?.array("parts")?.firstOrNull()
                .let { it as? JsonText.Obj }?.text("text")
        } ?: return null
        // A model that wrapped its JSON in prose or a code fence is common
        // enough that the answer is unwrapped here, once, instead of in every
        // caller.
        return MizanAiPrompts.stripConversationalOverhead(raw).takeIf { it.isNotBlank() }
    }

    /** Tokens as the vendor reported them, or zeros. Never estimated. */
    private fun extractUsage(body: String): Pair<Int, Int> {
        val document = JsonText.parse(body) as? JsonText.Obj ?: return 0 to 0
        return when (vendor) {
            AiVendor.OPENAI -> {
                val usage = document.obj("usage")
                (usage?.whole("prompt_tokens")?.toInt() ?: 0) to (usage?.whole("completion_tokens")?.toInt() ?: 0)
            }
            AiVendor.ANTHROPIC -> {
                val usage = document.obj("usage")
                (usage?.whole("input_tokens")?.toInt() ?: 0) to (usage?.whole("output_tokens")?.toInt() ?: 0)
            }
            AiVendor.GEMINI -> {
                val usage = document.obj("usageMetadata")
                (usage?.whole("promptTokenCount")?.toInt() ?: 0) to
                    (usage?.whole("candidatesTokenCount")?.toInt() ?: 0)
            }
        }
    }

    private fun jsonString(value: String): String {
        val escaped = buildString(value.length + 2) {
            append('"')
            for (char in value) {
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
                }
            }
            append('"')
        }
        return escaped
    }
}

/**
 * The model's structured answer, read strictly.
 *
 * The contract is deliberately narrow, because everything the model is allowed
 * to say has to be something the pipeline can check:
 *
 *     {"tool":"stock.availability","args":{"sku":"SKU-DESK-01"}}
 *     {"clarify":"sales.order.create_draft","missing":["ITEMS"]}
 *     {"refusal":"INJECTION_BLOCKED"}
 *
 * A tool this product does not have is not "close enough": it is refused with
 * a code, so a model cannot summon a capability by naming one.
 */
object StructuredOutput {

    fun parse(
        content: String,
        capabilities: ConnectorCapabilities,
        guard: InjectionGuard = InjectionGuard(),
    ): Interpretation {
        // The answer is data too. A record that made it into the prompt and
        // out again must not arrive as an instruction.
        if (guard.suspect(content)) return Interpretation.Rejected("INJECTION_BLOCKED")
        val document = JsonText.parse(content) as? JsonText.Obj
            ?: return Interpretation.Rejected("MODEL_ANSWER_UNREADABLE")
        document.text("refusal")?.let { code ->
            return Interpretation.Rejected(code.takeIf { it.matches(ReasonCode) } ?: "MODEL_REFUSED")
        }
        document.text("tool")?.let { wire ->
            val tool = ToolName.entries.firstOrNull { it.wire == wire }
                ?: return Interpretation.Rejected("MODEL_PROPOSED_UNKNOWN_TOOL")
            if (tool == ToolName.UNKNOWN) return Interpretation.Rejected("MODEL_PROPOSED_UNKNOWN_TOOL")
            if (!capabilities.supports(tool)) return Interpretation.Unsupported(tool, "TOOL_NOT_SUPPORTED")
            val args = document.obj("args") ?: document.obj("arguments")
                ?: return Interpretation.NeedsClarification(tool, listOf(MissingField.QUERY), emptyList())
            val typed = typed(tool, args)
                ?: return Interpretation.NeedsClarification(
                    tool,
                    missingFor(tool, args),
                    listOf("MODEL_ARGS_INCOMPLETE"),
                )
            return Interpretation.Ready(tool, typed, extractedNames(tool, args))
        }
        document.text("clarify")?.let { wire ->
            val tool = ToolName.entries.firstOrNull { it.wire == wire }
            val missing = document.strings("missing").mapNotNull { name ->
                MissingField.entries.firstOrNull { it.name == name.uppercase() }
            }
            return Interpretation.NeedsClarification(
                tool,
                missing.ifEmpty { listOf(MissingField.QUERY) },
                emptyList(),
            )
        }
        document.strings("missing").takeIf { it.isNotEmpty() }?.let { names ->
            val missing = names.mapNotNull { name ->
                MissingField.entries.firstOrNull { it.name == name.uppercase() }
            }
            return Interpretation.NeedsClarification(null, missing.ifEmpty { listOf(MissingField.QUERY) }, emptyList())
        }
        return Interpretation.Rejected("MODEL_ANSWER_UNREADABLE")
    }

    private val ReasonCode = Regex("[A-Z][A-Z0-9_]{2,40}")

    /**
     * The typed arguments, or null when the model did not supply what the tool
     * needs. Null is not an error to be papered over: a missing amount is not
     * zero, and a missing currency is not USD.
     */
    private fun typed(tool: ToolName, args: JsonText.Obj): ToolArgs? = when (tool) {
        ToolName.STOCK_AVAILABILITY ->
            args.text("sku")?.let { StockLookupArgs(it) }

        ToolName.CUSTOMER_SEARCH ->
            args.text("query")?.let { CustomerSearchArgs(it) }

        ToolName.SALES_SUMMARY ->
            SalesSummaryArgs(args.text("period") ?: "current")

        ToolName.CREATE_DRAFT_ORDER -> {
            val customer = args.text("customerName")
            val items = args.text("itemsSummary") ?: args.text("items")
            val money = moneyOf(args)
            if (customer == null || items == null || money == null) null else CreateDraftOrderArgs(customer, money, items)
        }

        ToolName.CANCEL_ORDER -> {
            val orderId = args.text("orderId")
            val reason = args.text("reason")
            if (orderId == null || reason == null) null else CancelOrderArgs(orderId, reason)
        }

        ToolName.CREATE_INVOICE ->
            args.text("orderId")?.let { CreateInvoiceArgs(it) }

        ToolName.REGISTER_PAYMENT -> {
            val invoiceId = args.text("invoiceId")
            val money = moneyOf(args)
            if (invoiceId == null || money == null) null else RegisterPaymentArgs(invoiceId, money)
        }

        ToolName.UNKNOWN -> null
    }

    private fun moneyOf(args: JsonText.Obj): Money? {
        val currency = args.text("currency") ?: return null
        // A model writes 2500 as a number, as a string, or as 250000 minor
        // units. All three are read; a missing one is not read as zero.
        val minor = args.whole("amountMinor")
            ?: decimal(args, "amount")?.let { Math.round(it * 100.0) }
        if (minor == null) return null
        return runCatching { Money(minor, currency) }.getOrNull()
    }

    private fun decimal(args: JsonText.Obj, name: String): Double? = when (val value = args.fields[name]) {
        is JsonText.Str -> value.value.toDoubleOrNull()
        is JsonText.Num -> value.raw.toDoubleOrNull()
        else -> null
    }

    private fun missingFor(tool: ToolName, args: JsonText.Obj): List<MissingField> {
        val missing = mutableListOf<MissingField>()
        fun need(field: String, marker: MissingField) {
            if (args.text(field).isNullOrBlank() && args.whole(field) == null) missing += marker
        }
        when (tool) {
            ToolName.STOCK_AVAILABILITY -> need("sku", MissingField.SKU)
            ToolName.CUSTOMER_SEARCH -> need("query", MissingField.QUERY)
            ToolName.CANCEL_ORDER -> {
                need("orderId", MissingField.ORDER_ID)
                need("reason", MissingField.REASON)
            }
            ToolName.CREATE_INVOICE -> need("orderId", MissingField.ORDER_ID)
            ToolName.REGISTER_PAYMENT -> {
                need("invoiceId", MissingField.INVOICE_ID)
                if (args.text("amount").isNullOrBlank() && args.whole("amountMinor") == null) {
                    missing += MissingField.AMOUNT
                }
            }
            ToolName.CREATE_DRAFT_ORDER -> {
                need("customerName", MissingField.CUSTOMER)
                need("itemsSummary", MissingField.ITEMS)
                if (args.text("amount").isNullOrBlank() && args.whole("amountMinor") == null) {
                    missing += MissingField.AMOUNT
                }
                if (args.text("currency").isNullOrBlank()) missing += MissingField.CURRENCY
            }
            ToolName.SALES_SUMMARY, ToolName.UNKNOWN -> Unit
        }
        return missing.ifEmpty { listOf(MissingField.QUERY) }
    }

    private fun extractedNames(tool: ToolName, args: JsonText.Obj): List<String> =
        args.fields.keys.sorted().map { "${tool.wire}.$it" }
}
