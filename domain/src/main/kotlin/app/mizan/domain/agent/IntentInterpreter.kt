package app.mizan.domain.agent

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

enum class MissingField {
    CUSTOMER,
    AMOUNT,
    CURRENCY,
    ITEMS,
    ORDER_ID,
    INVOICE_ID,
    REASON,
    SKU,
    QUERY,
}

enum class InterpreterKind {
    /** Deterministic rules. Not a model, and not a confidence score. */
    LOCAL_RULES,
}

sealed interface Interpretation {
    val kind: InterpreterKind

    data class Ready(
        val tool: ToolName,
        val args: ToolArgs,
        val extracted: List<String>,
        override val kind: InterpreterKind = InterpreterKind.LOCAL_RULES,
    ) : Interpretation

    data class NeedsClarification(
        val tool: ToolName?,
        val missing: List<MissingField>,
        val partialNotes: List<String>,
        override val kind: InterpreterKind = InterpreterKind.LOCAL_RULES,
    ) : Interpretation

    data class Unsupported(
        val tool: ToolName,
        val reasonCode: String,
        override val kind: InterpreterKind = InterpreterKind.LOCAL_RULES,
    ) : Interpretation

    data class Rejected(
        val reasonCode: String,
        override val kind: InterpreterKind = InterpreterKind.LOCAL_RULES,
    ) : Interpretation
}

class InjectionGuard {
    private val patterns = listOf(
        "ignore previous",
        "bypass approval",
        "override policy",
        "grant admin",
        "drop table",
        "delete all",
        "system prompt",
        "leak credentials",
        "تجاهل التعليمات",
        "تجاوز الاعتماد",
        "تخطي السياسة",
        "اجعل الرصيد صفر",
        "صلاحية مسؤول",
        "امسح الكل",
        "سرقة المفتاح",
    )

    fun suspect(text: String): Boolean {
        val lower = text.lowercase()
        return patterns.any { lower.contains(it) }
    }
}

/**
 * Extracts only what the text actually contains. Missing fields become
 * questions. Nothing is invented: no default amount, no default order id.
 *
 * ERP record text must never be passed here as instructions.
 */
class IntentInterpreter(
    private val injectionGuard: InjectionGuard = InjectionGuard(),
) {
    fun interpret(text: String, capabilities: ConnectorCapabilities): Interpretation {
        val trimmed = text.trim()
        if (trimmed.length < 2) {
            return Interpretation.NeedsClarification(null, listOf(MissingField.QUERY), emptyList())
        }
        if (injectionGuard.suspect(trimmed)) {
            return Interpretation.Rejected("INJECTION_BLOCKED")
        }
        val lower = trimmed.lowercase()
        val tool = detectTool(lower)
        if (tool != null && !capabilities.supports(tool)) {
            return Interpretation.Unsupported(tool, "TOOL_NOT_SUPPORTED")
        }
        val vague = VAGUE.any { lower.contains(it) }
        return when (tool) {
            ToolName.STOCK_AVAILABILITY -> stock(trimmed, vague)
            ToolName.CUSTOMER_SEARCH -> customer(trimmed, vague)
            ToolName.CANCEL_ORDER -> cancel(trimmed, vague)
            ToolName.CREATE_INVOICE -> invoice(trimmed, vague)
            ToolName.REGISTER_PAYMENT -> payment(trimmed, vague)
            ToolName.SALES_SUMMARY -> Interpretation.Ready(
                ToolName.SALES_SUMMARY,
                SalesSummaryArgs("current"),
                listOf("period=current"),
            )
            ToolName.CREATE_DRAFT_ORDER, null -> draft(trimmed, vague)
            ToolName.UNKNOWN -> Interpretation.Rejected("TOOL_NOT_SUPPORTED")
        }
    }

    private fun detectTool(lower: String): ToolName? = when {
        lower.contains("cancel") || lower.contains("إلغاء") || lower.contains("الغاء") ->
            ToolName.CANCEL_ORDER
        lower.contains("stock") || lower.contains("مخزون") || lower.contains("بضاعة") ->
            ToolName.STOCK_AVAILABILITY
        lower.contains("invoice") || lower.contains("فاتورة") -> ToolName.CREATE_INVOICE
        lower.contains("payment") || lower.contains("سداد") || lower.contains("دفع") ->
            ToolName.REGISTER_PAYMENT
        lower.contains("customer") || lower.contains("عميل") && !lower.contains("أمر") && !lower.contains("طلب") ->
            ToolName.CUSTOMER_SEARCH
        lower.contains("summary") || lower.contains("ملخص") -> ToolName.SALES_SUMMARY
        lower.contains("order") || lower.contains("draft") || lower.contains("أمر") ||
            lower.contains("طلب") || lower.contains("بيع") -> ToolName.CREATE_DRAFT_ORDER
        else -> null
    }

    private fun stock(text: String, vague: Boolean): Interpretation {
        val sku = SKU.find(text)?.value
        if (sku == null || vague) {
            return Interpretation.NeedsClarification(
                ToolName.STOCK_AVAILABILITY,
                listOf(MissingField.SKU),
                emptyList(),
            )
        }
        return Interpretation.Ready(ToolName.STOCK_AVAILABILITY, StockLookupArgs(sku), listOf("sku=$sku"))
    }

    private fun customer(text: String, vague: Boolean): Interpretation {
        val query = extractAfter(text, listOf("customer", "عميل"))
        if (query == null || vague) {
            return Interpretation.NeedsClarification(
                ToolName.CUSTOMER_SEARCH,
                listOf(MissingField.QUERY),
                emptyList(),
            )
        }
        return Interpretation.Ready(ToolName.CUSTOMER_SEARCH, CustomerSearchArgs(query), listOf("query"))
    }

    private fun cancel(text: String, vague: Boolean): Interpretation {
        val missing = mutableListOf<MissingField>()
        val orderId = ORDER_ID.find(text)?.value
        val reason = extractAfter(text, listOf("because", "reason", "لأن", "بسبب"))
        if (orderId == null) missing += MissingField.ORDER_ID
        if (reason == null) missing += MissingField.REASON
        if (missing.isNotEmpty() || vague) {
            return Interpretation.NeedsClarification(ToolName.CANCEL_ORDER, missing.ifEmpty { listOf(MissingField.REASON) }, emptyList())
        }
        return Interpretation.Ready(
            ToolName.CANCEL_ORDER,
            CancelOrderArgs(orderId!!, reason!!),
            listOf("orderId", "reason"),
        )
    }

    private fun invoice(text: String, vague: Boolean): Interpretation {
        val orderId = ORDER_ID.find(text)?.value
        if (orderId == null || vague) {
            return Interpretation.NeedsClarification(ToolName.CREATE_INVOICE, listOf(MissingField.ORDER_ID), emptyList())
        }
        return Interpretation.Ready(ToolName.CREATE_INVOICE, CreateInvoiceArgs(orderId), listOf("orderId"))
    }

    private fun payment(text: String, vague: Boolean): Interpretation {
        val invoiceId = INVOICE_ID.find(text)?.value
        val money = extractMoney(text)
        val missing = mutableListOf<MissingField>()
        if (invoiceId == null) missing += MissingField.INVOICE_ID
        if (money == null) missing += MissingField.AMOUNT
        if (money == null && CURRENCY.containsMatchIn(text)) missing += MissingField.AMOUNT
        if (money == null && NUMBER.containsMatchIn(text) && !CURRENCY.containsMatchIn(text)) {
            missing += MissingField.CURRENCY
        }
        if (missing.isNotEmpty() || vague) {
            return Interpretation.NeedsClarification(ToolName.REGISTER_PAYMENT, missing.distinct(), emptyList())
        }
        return Interpretation.Ready(
            ToolName.REGISTER_PAYMENT,
            RegisterPaymentArgs(invoiceId!!, money!!),
            listOf("invoiceId", "amount"),
        )
    }

    private fun draft(text: String, vague: Boolean): Interpretation {
        val customer = extractCustomer(text)
        val money = extractMoney(text)
        val items = extractAfter(text, listOf("items", "for items", "بنود", "أصناف", "عبارة عن"))
        val missing = mutableListOf<MissingField>()
        if (customer == null) missing += MissingField.CUSTOMER
        if (money == null) {
            missing += MissingField.AMOUNT
            if (!CURRENCY.containsMatchIn(text)) missing += MissingField.CURRENCY
        }
        if (items == null) missing += MissingField.ITEMS
        if (missing.isNotEmpty() || vague) {
            return Interpretation.NeedsClarification(
                ToolName.CREATE_DRAFT_ORDER,
                missing.distinct(),
                emptyList(),
            )
        }
        return Interpretation.Ready(
            ToolName.CREATE_DRAFT_ORDER,
            CreateDraftOrderArgs(customer!!, money!!, items!!),
            listOf("customer", "amount", "items"),
        )
    }

    private fun extractMoney(text: String): Money? {
        val currency = CURRENCY.find(text)?.value?.let { symbolToCode(it) } ?: return null
        val number = NUMBER.find(text)?.value ?: return null
        return Money.parseMajor(number, currency)
    }

    private fun symbolToCode(token: String): String = when (token.lowercase()) {
        "$", "usd", "دولار" -> "USD"
        "egp", "ج.م", "جنيه", "جنيها", "جنيهًا" -> "EGP"
        "eur", "€" -> "EUR"
        "gbp", "£" -> "GBP"
        "sar", "ر.س" -> "SAR"
        "aed", "د.إ" -> "AED"
        else -> token.uppercase()
    }

    private fun extractCustomer(text: String): String? {
        val match = CUSTOMER.find(text) ?: return null
        var name = match.groupValues[1]
        val lower = name.lowercase()
        val cut = CUSTOMER_STOP.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (cut != null) name = name.substring(0, cut)
        return name.trim().trimEnd('.', '،', ',').takeIf { it.length >= 2 }
    }

    private fun extractAfter(text: String, markers: List<String>): String? {
        val lower = text.lowercase()
        val index = markers.map { it.lowercase() to lower.indexOf(it.lowercase()) }
            .filter { it.second >= 0 }
            .minByOrNull { it.second }
            ?: return null
        val start = index.second + index.first.length
        val rest = text.substring(start).trim().trimStart(':', '-', '—').trim()
        if (rest.length < 2) return null
        return rest.take(120)
    }

    private companion object {
        val VAGUE = listOf("some", "maybe", "approx", "approximately", "بعض", "تقريبا", "تقريبًا", "حوالي", "أي كمية")
        val SKU = Regex("""SKU-[A-Z0-9-]+""", RegexOption.IGNORE_CASE)
        val ORDER_ID = Regex("""(?:SO|SAL-ORD|ORDER)-[A-Z0-9-]+""", RegexOption.IGNORE_CASE)
        val INVOICE_ID = Regex("""INV-[A-Z0-9-]+""", RegexOption.IGNORE_CASE)
        val NUMBER = Regex("""\d{1,3}(?:[,\s]\d{3})*(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?""")
        val CURRENCY = Regex("""USD|EGP|EUR|GBP|SAR|AED|\$|€|£|ج\.م|جنيه|دولار|ر\.س|د\.إ""", RegexOption.IGNORE_CASE)
        val CUSTOMER = Regex(
            """(?:for|customer|عميل|للعميل)\s+([^,\n]{2,80})""",
            RegexOption.IGNORE_CASE,
        )
        val CUSTOMER_STOP = listOf(
            " بمبلغ",
            " مبلغ",
            " amount",
            " جنيه",
            " egp",
            " usd",
            " بنود",
            " items",
            " for items",
        )
    }
}
