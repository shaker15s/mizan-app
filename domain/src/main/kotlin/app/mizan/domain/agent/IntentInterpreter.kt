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
            ToolName.SALES_SUMMARY -> {
                val period = extractPeriod(lower)
                Interpretation.Ready(
                    ToolName.SALES_SUMMARY,
                    SalesSummaryArgs(period),
                    listOf("period=$period"),
                )
            }
            ToolName.CREATE_DRAFT_ORDER, null -> draft(trimmed, vague)
            ToolName.UNKNOWN -> Interpretation.Rejected("TOOL_NOT_SUPPORTED")
        }
    }

    private fun detectTool(lower: String): ToolName? = when {
        lower.contains("cancel") || lower.contains("إلغاء") || lower.contains("الغاء") ->
            ToolName.CANCEL_ORDER
        // "Is SKU-DESK-01 available?" names no tool keyword, but it does name
        // a SKU, and that is what the person wants looked up.
        lower.contains("stock") || lower.contains("مخزون") || lower.contains("بضاعة") ||
            lower.contains("available") || lower.contains("متاح") ||
            (SKU.containsMatchIn(lower) && !ORDER_WORDS.any { lower.contains(it) }) ->
            ToolName.STOCK_AVAILABILITY
        // "سجل سداد ... على الفاتورة" mentions the invoice and is still a
        // payment, so the payment words are tried before the invoice ones.
        lower.contains("payment") || lower.contains("سداد") || lower.contains("دفع") ||
            lower.contains("سدد") || lower.contains("ادفع") || lower.contains("pay") ->
            ToolName.REGISTER_PAYMENT
        lower.contains("invoice") || lower.contains("فاتورة") -> ToolName.CREATE_INVOICE
        lower.contains("customer") || lower.contains("عميل") && !lower.contains("أمر") && !lower.contains("طلب") ->
            ToolName.CUSTOMER_SEARCH
        lower.contains("summary") || lower.contains("ملخص") -> ToolName.SALES_SUMMARY
        lower.contains("order") || lower.contains("draft") || lower.contains("أمر") ||
            lower.contains("طلب") || lower.contains("بيع") -> ToolName.CREATE_DRAFT_ORDER
        else -> null
    }

    /**
     * A question the app can show. It is never empty: a vague request such as
     * "order something for some customer" has no single missing field, and an
     * empty list would render as a clarification that asks nothing.
     */
    private fun clarify(tool: ToolName?, missing: List<MissingField>): Interpretation =
        Interpretation.NeedsClarification(
            tool,
            missing.distinct().ifEmpty { listOf(MissingField.QUERY) },
            emptyList(),
        )

    private fun stock(text: String, vague: Boolean): Interpretation {
        val sku = SKU.find(text)?.value
        if (sku == null || vague) {
            return clarify(ToolName.STOCK_AVAILABILITY, listOf(MissingField.SKU))
        }
        return Interpretation.Ready(ToolName.STOCK_AVAILABILITY, StockLookupArgs(sku), listOf("sku=$sku"))
    }

    private fun customer(text: String, vague: Boolean): Interpretation {
        val query = extractAfter(text, listOf("customer", "عميل"))
        if (query == null || vague) {
            return clarify(ToolName.CUSTOMER_SEARCH, listOf(MissingField.QUERY))
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
            return clarify(ToolName.CANCEL_ORDER, missing)
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
            return clarify(ToolName.CREATE_INVOICE, listOf(MissingField.ORDER_ID))
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
            return clarify(ToolName.REGISTER_PAYMENT, missing)
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
        val items = extractAfter(
            text,
            listOf("items", "for items", "بنود", "أصناف", "اصناف", "منتجات", "قطع", "وحدات", "عبارة عن"),
        )
        val missing = mutableListOf<MissingField>()
        if (customer == null) missing += MissingField.CUSTOMER
        if (money == null) {
            missing += MissingField.AMOUNT
            if (!CURRENCY.containsMatchIn(text)) missing += MissingField.CURRENCY
        }
        if (items == null) missing += MissingField.ITEMS
        if (missing.isNotEmpty() || vague) {
            return clarify(ToolName.CREATE_DRAFT_ORDER, missing)
        }
        return Interpretation.Ready(
            ToolName.CREATE_DRAFT_ORDER,
            CreateDraftOrderArgs(customer!!, money!!, items!!),
            listOf("customer", "amount", "items"),
        )
    }

    /**
     * The amount is the number written next to the currency.
     *
     * Taking the first number in the sentence instead reads the year out of
     * the id: "سداد فاتورة INV-2026-9021 بمبلغ 850 دولار" would pay 2,026.
     */
    private fun extractMoney(text: String): Money? {
        val currencyMatch = CURRENCY.find(text) ?: return null
        val currency = symbolToCode(currencyMatch.value)
        val digits = normalizeDigits(text)
        val before = digits.substring(0, currencyMatch.range.first)
        val after = digits.substring(currencyMatch.range.last + 1)
        val number = NUMBER.findAll(before).lastOrNull()?.value
            ?: NUMBER.find(after)?.value
            ?: return null
        return Money.parseMajor(number, currency)
    }

    /** ١٢٠٠٠ and 12000 are the same amount. Egypt types both. */
    private fun normalizeDigits(text: String): String = buildString(text.length) {
        text.forEach { char ->
            when (char) {
                in '٠'..'٩' -> append((char.code - '٠'.code).digitToChar())
                '٫' -> append('.')
                '٬' -> append(',')
                else -> append(char)
            }
        }
    }

    /** The period the person actually asked about. */
    private fun extractPeriod(lower: String): String = when {
        lower.contains("اليوم") || lower.contains("today") -> "day"
        lower.contains("اسبوع") || lower.contains("أسبوع") || lower.contains("week") -> "week"
        lower.contains("ربع") || lower.contains("quarter") -> "quarter"
        lower.contains("شهر") || lower.contains("month") || lower.contains("شهري") -> "month"
        lower.contains("سنة") || lower.contains("سنه") || lower.contains("year") -> "year"
        else -> "current"
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
        // "for Acme Corp 1,250.50 USD": the regex stops at the comma and
        // leaves the head of the amount inside the name.
        val trimmed = name.trim().trimEnd('.', '،', ',')
        val withoutTrailingNumber = TRAILING_NUMBER.replace(trimmed, "").trim().trimEnd('.', '،', ',')
        return (if (withoutTrailingNumber.length >= 2) withoutTrailingNumber else trimmed)
            .takeIf { it.length >= 2 }
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
        val NUMBER = Regex("""\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?""")
        val CURRENCY = Regex("""USD|EGP|EUR|GBP|SAR|AED|\$|€|£|ج\.م|جنيه|دولار|ر\.س|د\.إ""", RegexOption.IGNORE_CASE)
        val CUSTOMER = Regex(
            """(?:for|customer|عميل|للعميل)\s+([^,\n]{2,80})""",
            RegexOption.IGNORE_CASE,
        )
        val ORDER_WORDS = listOf("order", "draft", "أمر", "طلب", "بيع", "فاتورة")
        /** The head of an amount left behind when the name regex stops at a comma. */
        val TRAILING_NUMBER = Regex("""[\s,.]*[\d][\s,.]*[\d,.]*$""")
        val CUSTOMER_STOP = listOf(
            " with ",
            " بقيمة",
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
