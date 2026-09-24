package app.mizan.domain.model

/**
 * Versioned tool contracts. The version changes when the canonical argument
 * schema changes. It is not a decoration.
 */
enum class ToolName(
    val wire: String,
    val version: String,
    val readOnly: Boolean,
    val destructive: Boolean,
    val requiredPermission: String,
) {
    STOCK_AVAILABILITY("stock.availability", "1.0.0", true, false, "stock.read"),
    CUSTOMER_SEARCH("customer.search", "1.0.0", true, false, "customer.read"),
    CREATE_DRAFT_ORDER("sales.order.create_draft", "2.1.0", false, false, "sales.order.write"),
    CANCEL_ORDER("sales.order.cancel", "1.2.0", false, true, "sales.order.cancel"),
    CREATE_INVOICE("invoice.create_from_order", "1.0.0", false, false, "invoice.write"),
    REGISTER_PAYMENT("payment.register", "1.1.0", false, false, "payment.write"),
    SALES_SUMMARY("analytics.sales_summary", "1.0.0", true, false, "analytics.read"),
    /** Stored rows whose tool name is not a known contract. Not executable. */
    UNKNOWN("unknown", "0.0.0", true, false, "none"),
    ;

    companion object {
        fun fromWire(wire: String): ToolName? = entries.find { it.wire == wire }
    }
}

enum class ApprovalLevel(val rank: Int) {
    L0_NONE(0),
    L1_USER_CONFIRMATION(1),
    L2_PRIVILEGED(2),
    L3_MANAGER(3),
    L4_DUAL(4),
    L5_MULTI_PARTY(5),
}

enum class RiskTier {
    R0_READ,
    R1_LOW,
    R2_MEDIUM,
    R3_HIGH,
    R4_CRITICAL,
}

/**
 * Typed arguments. Critical business fields are not a string map.
 */
sealed interface ToolArgs {
    val tool: ToolName
    fun canonical(): CanonicalValue
}

data class StockLookupArgs(val sku: String) : ToolArgs {
    override val tool = ToolName.STOCK_AVAILABILITY
    override fun canonical() = obj("sku" to CanonicalValue.Str(sku.trim()))
}

data class CustomerSearchArgs(val query: String) : ToolArgs {
    override val tool = ToolName.CUSTOMER_SEARCH
    override fun canonical() = obj("query" to CanonicalValue.Str(query.trim()))
}

data class CreateDraftOrderArgs(
    val customerName: String,
    val amount: Money,
    val itemsSummary: String,
) : ToolArgs {
    override val tool = ToolName.CREATE_DRAFT_ORDER
    override fun canonical() = obj(
        "amountMinor" to CanonicalValue.Num(amount.minorUnits.toString()),
        "currency" to CanonicalValue.Str(amount.currency),
        "customerName" to CanonicalValue.Str(customerName.trim()),
        "itemsSummary" to CanonicalValue.Str(itemsSummary.trim()),
    )
}

data class CancelOrderArgs(val orderId: String, val reason: String) : ToolArgs {
    override val tool = ToolName.CANCEL_ORDER
    override fun canonical() = obj(
        "orderId" to CanonicalValue.Str(orderId.trim()),
        "reason" to CanonicalValue.Str(reason.trim()),
    )
}

data class CreateInvoiceArgs(val orderId: String) : ToolArgs {
    override val tool = ToolName.CREATE_INVOICE
    override fun canonical() = obj("orderId" to CanonicalValue.Str(orderId.trim()))
}

data class RegisterPaymentArgs(val invoiceId: String, val amount: Money) : ToolArgs {
    override val tool = ToolName.REGISTER_PAYMENT
    override fun canonical() = obj(
        "amountMinor" to CanonicalValue.Num(amount.minorUnits.toString()),
        "currency" to CanonicalValue.Str(amount.currency),
        "invoiceId" to CanonicalValue.Str(invoiceId.trim()),
    )
}

data class SalesSummaryArgs(val periodCode: String) : ToolArgs {
    override val tool = ToolName.SALES_SUMMARY
    override fun canonical() = obj("period" to CanonicalValue.Str(periodCode.trim()))
}

data class ConnectorCapabilities(
    val connectorId: String,
    val supportsDraftOrders: Boolean,
    val supportsOrderCancel: Boolean,
    val supportsInvoiceCreation: Boolean,
    val supportsPayment: Boolean,
    val supportsVerification: Boolean,
    val supportsBatchRead: Boolean,
    val supportsJson2: Boolean,
    val supportsLegacyRpc: Boolean,
) {
    fun supports(tool: ToolName): Boolean = when (tool) {
        ToolName.STOCK_AVAILABILITY, ToolName.CUSTOMER_SEARCH, ToolName.SALES_SUMMARY -> true
        ToolName.CREATE_DRAFT_ORDER -> supportsDraftOrders
        ToolName.CANCEL_ORDER -> supportsOrderCancel
        ToolName.CREATE_INVOICE -> supportsInvoiceCreation
        ToolName.REGISTER_PAYMENT -> supportsPayment
        ToolName.UNKNOWN -> false
    }

    companion object {
        val simulation = ConnectorCapabilities(
            connectorId = "simulation",
            supportsDraftOrders = true,
            supportsOrderCancel = true,
            supportsInvoiceCreation = true,
            supportsPayment = true,
            supportsVerification = true,
            supportsBatchRead = true,
            supportsJson2 = false,
            supportsLegacyRpc = false,
        )

        /**
         * Production preview. The service, not this object, decides whether
         * the ERP can perform the tool. Verification is not claimed here.
         */
        val servicePreview = ConnectorCapabilities(
            connectorId = "service-preview",
            supportsDraftOrders = true,
            supportsOrderCancel = true,
            supportsInvoiceCreation = true,
            supportsPayment = true,
            supportsVerification = false,
            supportsBatchRead = false,
            supportsJson2 = false,
            supportsLegacyRpc = false,
        )

        val none = ConnectorCapabilities(
            connectorId = "none",
            supportsDraftOrders = false,
            supportsOrderCancel = false,
            supportsInvoiceCreation = false,
            supportsPayment = false,
            supportsVerification = false,
            supportsBatchRead = false,
            supportsJson2 = false,
            supportsLegacyRpc = false,
        )
    }
}

private fun obj(vararg fields: Pair<String, CanonicalValue>): CanonicalValue.Obj =
    CanonicalValue.Obj(fields.toList().sortedBy { it.first })
