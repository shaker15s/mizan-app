package app.mizan.domain.tool

import app.mizan.domain.model.ConnectorCapabilities
import app.mizan.domain.model.ToolName

/**
 * A tool is a contract, not a string in a map.
 *
 * The catalogue in this file is the single description of what Mizan can ask
 * an ERP to do: the arguments it accepts, the risk class that decides how much
 * authority the action needs, whether a read-back proves it, and what a person
 * is about to cause when they press the button.
 *
 * The version of a definition changes when its argument schema or its side
 * effects change. A stored execution carries the version it ran under, so an
 * old receipt stays interpretable after the contract moves.
 */
enum class ToolRiskClass(val wire: String) {
    /** Cannot change anything. */
    READ("READ"),

    /** Reads that aggregate; still not a write, but not an operational read either. */
    ANALYTICS("ANALYTICS"),

    /** Creates a document that is not yet effective, such as a draft order. */
    DRAFT("DRAFT"),

    /** A committed change to ERP state. */
    MUTATION("MUTATION"),

    /** Removes or cancels something a person may already be relying on. */
    DESTRUCTIVE("DESTRUCTIVE"),

    /** Moves money. */
    FINANCIAL("FINANCIAL"),

    /** Changes who may do what. Never reachable from a phone. */
    ADMIN("ADMIN"),
    ;

    /** True when the call is expected to leave ERP state different. */
    val mutating: Boolean get() = this != READ && this != ANALYTICS
}

/**
 * What a connector can do, named the way the plan names it. The Android UI
 * reads this set and hides an action it cannot honestly offer, instead of
 * offering a button that fails.
 */
enum class Capability(val wire: String) {
    STOCK_READ("stock.read"),
    CUSTOMER_SEARCH("customer.search"),
    ANALYTICS_SALES("analytics.sales"),
    SALES_ORDER_CREATE("sales.order.create"),
    SALES_ORDER_CANCEL("sales.order.cancel"),
    INVOICE_CREATE("invoice.create"),
    PAYMENT_CREATE("payment.create"),
    BATCH_READ("batch.read"),
    TRANSACTIONAL_WORKFLOW("transactional_workflows"),
    READ_BACK_VERIFICATION("verification.read_back"),
    JSON2_TRANSPORT("transport.json2"),
    LEGACY_RPC_TRANSPORT("transport.xmlrpc"),
    ;

    companion object {
        fun fromWire(wire: String): Capability? = entries.find { it.wire == wire }
    }
}

fun ConnectorCapabilities.asCapabilities(): Set<Capability> = buildSet {
    if (supportsDraftOrders) add(Capability.SALES_ORDER_CREATE)
    if (supportsOrderCancel) add(Capability.SALES_ORDER_CANCEL)
    if (supportsInvoiceCreation) add(Capability.INVOICE_CREATE)
    if (supportsPayment) add(Capability.PAYMENT_CREATE)
    if (supportsVerification) add(Capability.READ_BACK_VERIFICATION)
    if (supportsBatchRead) add(Capability.BATCH_READ)
    if (supportsJson2) add(Capability.JSON2_TRANSPORT)
    if (supportsLegacyRpc) add(Capability.LEGACY_RPC_TRANSPORT)
    // Reads are the floor of every connector: anything that cannot be read
    // cannot be verified, so a connector without them is not usable at all.
    add(Capability.STOCK_READ)
    add(Capability.CUSTOMER_SEARCH)
    add(Capability.ANALYTICS_SALES)
}

/** How the service proves that a write happened. */
enum class VerificationStrategy {
    /** Nothing to verify: the tool did not change state. */
    NONE,

    /** A separate read of the same record, compared field by field. */
    READ_BACK,

    /** A read-back whose fields must match the request, not merely exist. */
    READ_BACK_FIELDS,

    /** The connector runs the whole operation inside one ERP transaction. */
    TRANSACTIONAL,

    /** Simulated connectors only. Never used to claim an ERP fact. */
    SIMULATED,
}

/** What happens when the same request arrives twice. */
enum class IdempotencyStrategy {
    NONE,

    /** (tenant, key) is unique. A verified replay returns the original result. */
    TENANT_KEY,
}

enum class ArgumentType {
    TEXT,
    MONEY_MINOR,
    CURRENCY,
    IDENTIFIER,
    ENUM,
}

data class ArgumentSpec(
    val name: String,
    val type: ArgumentType,
    val required: Boolean,
    val note: String = "",
    /** Longest accepted text, in characters. Null means unbounded. */
    val maxLength: Int? = null,
    /** Bounds for a monetary argument, in minor units. */
    val minMinor: Long? = null,
    val maxMinor: Long? = null,
    /** Bounds for a quantity argument. */
    val minQuantity: Long? = null,
    val maxQuantity: Long? = null,
)

data class SideEffect(
    val code: String,
    /** Message key, resolved in the app. No user-facing prose lives in Kotlin. */
    val descriptionKey: String,
)

/**
 * What a person is about to cause. Rendered before approval, in the person's
 * language, from keys -- not assembled from an English sentence.
 */
data class ActionPreview(
    val titleKey: String,
    val summaryKey: String,
    val willDoKeys: List<String>,
    val willNotDoKeys: List<String>,
    val reversalKey: String,
)

data class ToolDefinition(
    val tool: ToolName,
    val version: String,
    val riskClass: ToolRiskClass,
    val descriptionKey: String,
    val arguments: List<ArgumentSpec>,
    val requiredPermission: String,
    val requiresApproval: Boolean,
    /** A fresh proof of the person's presence is needed at dispatch time. */
    val requiresFreshProof: Boolean,
    val verification: VerificationStrategy,
    val idempotency: IdempotencyStrategy,
    val requiresCapabilities: Set<Capability>,
    val sideEffects: List<SideEffect>,
    val preview: ActionPreview,
) {
    val requiredArguments: List<ArgumentSpec> get() = arguments.filter { it.required }

    fun argument(name: String): ArgumentSpec? = arguments.firstOrNull { it.name == name }

    /** The schema version recorded in a journal entry. */
    val schemaVersion: String get() = "$version.s1"
}

/**
 * The catalogue itself. Everything that authorises, verifies or explains an
 * action reads from here, so a tool cannot behave one way in the app and
 * another in the service.
 */
object ToolCatalog {
    /** Identity of this catalogue, stored with every proposal and receipt. */
    const val VERSION: String = "tools-2.0.0"

    val definitions: List<ToolDefinition> = listOf(
        ToolDefinition(
            tool = ToolName.STOCK_AVAILABILITY,
            version = "1.0.0",
            riskClass = ToolRiskClass.READ,
            descriptionKey = "tool_stock_availability_title",
            arguments = listOf(ArgumentSpec("sku", ArgumentType.IDENTIFIER, true)),
            requiredPermission = "stock.read",
            requiresApproval = false,
            requiresFreshProof = false,
            verification = VerificationStrategy.NONE,
            idempotency = IdempotencyStrategy.NONE,
            requiresCapabilities = setOf(Capability.STOCK_READ),
            sideEffects = emptyList(),
            preview = ActionPreview(
                titleKey = "preview_stock_title",
                summaryKey = "preview_stock_summary",
                willDoKeys = listOf("preview_stock_will_read"),
                willNotDoKeys = listOf("preview_readonly_no_change"),
                reversalKey = "preview_readonly_reversal",
            ),
        ),
        ToolDefinition(
            tool = ToolName.CUSTOMER_SEARCH,
            version = "1.0.0",
            riskClass = ToolRiskClass.READ,
            descriptionKey = "tool_customer_search_title",
            arguments = listOf(ArgumentSpec("query", ArgumentType.TEXT, true)),
            requiredPermission = "customer.read",
            requiresApproval = false,
            requiresFreshProof = false,
            verification = VerificationStrategy.NONE,
            idempotency = IdempotencyStrategy.NONE,
            requiresCapabilities = setOf(Capability.CUSTOMER_SEARCH),
            sideEffects = emptyList(),
            preview = ActionPreview(
                titleKey = "preview_customer_search_title",
                summaryKey = "preview_customer_search_summary",
                willDoKeys = listOf("preview_customer_search_will_read"),
                willNotDoKeys = listOf("preview_readonly_no_change"),
                reversalKey = "preview_readonly_reversal",
            ),
        ),
        ToolDefinition(
            tool = ToolName.SALES_SUMMARY,
            version = "1.0.0",
            riskClass = ToolRiskClass.ANALYTICS,
            descriptionKey = "tool_sales_summary_title",
            arguments = listOf(ArgumentSpec("period", ArgumentType.ENUM, false, "defaults to current month")),
            requiredPermission = "analytics.read",
            requiresApproval = false,
            requiresFreshProof = false,
            verification = VerificationStrategy.NONE,
            idempotency = IdempotencyStrategy.NONE,
            requiresCapabilities = setOf(Capability.ANALYTICS_SALES),
            sideEffects = emptyList(),
            preview = ActionPreview(
                titleKey = "preview_summary_title",
                summaryKey = "preview_summary_summary",
                willDoKeys = listOf("preview_summary_will_read"),
                willNotDoKeys = listOf("preview_readonly_no_change"),
                reversalKey = "preview_readonly_reversal",
            ),
        ),
        ToolDefinition(
            tool = ToolName.CREATE_DRAFT_ORDER,
            version = "2.1.0",
            riskClass = ToolRiskClass.DRAFT,
            descriptionKey = "tool_create_draft_order_title",
            arguments = listOf(
                ArgumentSpec("customerName", ArgumentType.TEXT, true),
                ArgumentSpec("amountMinor", ArgumentType.MONEY_MINOR, true),
                ArgumentSpec("currency", ArgumentType.CURRENCY, true),
                ArgumentSpec("itemsSummary", ArgumentType.TEXT, true),
            ),
            requiredPermission = "sales.order.write",
            requiresApproval = true,
            requiresFreshProof = true,
            verification = VerificationStrategy.READ_BACK_FIELDS,
            idempotency = IdempotencyStrategy.TENANT_KEY,
            requiresCapabilities = setOf(Capability.SALES_ORDER_CREATE, Capability.READ_BACK_VERIFICATION),
            sideEffects = listOf(
                SideEffect("DRAFT_ORDER_CREATED", "side_effect_draft_order_created"),
                SideEffect("CUSTOMER_ATTACHED", "side_effect_customer_attached"),
            ),
            preview = ActionPreview(
                titleKey = "preview_draft_order_title",
                summaryKey = "preview_draft_order_summary",
                willDoKeys = listOf(
                    "preview_draft_order_will_create",
                    "preview_draft_order_will_attach_customer",
                    "preview_draft_order_will_record_amount",
                ),
                willNotDoKeys = listOf(
                    "preview_draft_order_will_not_confirm",
                    "preview_draft_order_will_not_invoice",
                    "preview_draft_order_will_not_pay",
                ),
                reversalKey = "preview_draft_order_reversal",
            ),
        ),
        ToolDefinition(
            tool = ToolName.CANCEL_ORDER,
            version = "1.2.0",
            riskClass = ToolRiskClass.DESTRUCTIVE,
            descriptionKey = "tool_cancel_order_title",
            arguments = listOf(
                ArgumentSpec("orderId", ArgumentType.IDENTIFIER, true),
                ArgumentSpec("reason", ArgumentType.TEXT, true),
            ),
            requiredPermission = "sales.order.cancel",
            requiresApproval = true,
            requiresFreshProof = true,
            verification = VerificationStrategy.READ_BACK_FIELDS,
            idempotency = IdempotencyStrategy.TENANT_KEY,
            requiresCapabilities = setOf(Capability.SALES_ORDER_CANCEL, Capability.READ_BACK_VERIFICATION),
            sideEffects = listOf(
                SideEffect("ORDER_CANCELLED", "side_effect_order_cancelled"),
                SideEffect("FUTURE_INVOICING_STOPPED", "side_effect_future_invoicing_stopped"),
                SideEffect("AUDIT_PRESERVED", "side_effect_audit_preserved"),
            ),
            preview = ActionPreview(
                titleKey = "preview_cancel_order_title",
                summaryKey = "preview_cancel_order_summary",
                willDoKeys = listOf(
                    "preview_cancel_order_will_cancel",
                    "preview_cancel_order_will_stop_invoicing",
                    "preview_cancel_order_will_keep_history",
                ),
                willNotDoKeys = listOf("preview_cancel_order_will_not_delete"),
                reversalKey = "preview_cancel_order_reversal",
            ),
        ),
        ToolDefinition(
            tool = ToolName.CREATE_INVOICE,
            version = "1.0.0",
            riskClass = ToolRiskClass.FINANCIAL,
            descriptionKey = "tool_create_invoice_title",
            arguments = listOf(ArgumentSpec("orderId", ArgumentType.IDENTIFIER, true)),
            requiredPermission = "invoice.write",
            requiresApproval = true,
            requiresFreshProof = true,
            verification = VerificationStrategy.READ_BACK_FIELDS,
            idempotency = IdempotencyStrategy.TENANT_KEY,
            requiresCapabilities = setOf(Capability.INVOICE_CREATE, Capability.READ_BACK_VERIFICATION),
            sideEffects = listOf(
                SideEffect("INVOICE_CREATED", "side_effect_invoice_created"),
                SideEffect("RECEIVABLE_OPENED", "side_effect_receivable_opened"),
            ),
            preview = ActionPreview(
                titleKey = "preview_invoice_title",
                summaryKey = "preview_invoice_summary",
                willDoKeys = listOf(
                    "preview_invoice_will_create",
                    "preview_invoice_will_inherit_amount",
                ),
                willNotDoKeys = listOf(
                    "preview_invoice_will_not_send",
                    "preview_invoice_will_not_pay",
                ),
                reversalKey = "preview_invoice_reversal",
            ),
        ),
        ToolDefinition(
            tool = ToolName.REGISTER_PAYMENT,
            version = "1.1.0",
            riskClass = ToolRiskClass.FINANCIAL,
            descriptionKey = "tool_register_payment_title",
            arguments = listOf(
                ArgumentSpec("invoiceId", ArgumentType.IDENTIFIER, true),
                ArgumentSpec("amountMinor", ArgumentType.MONEY_MINOR, true),
                ArgumentSpec("currency", ArgumentType.CURRENCY, true),
            ),
            requiredPermission = "payment.write",
            requiresApproval = true,
            requiresFreshProof = true,
            verification = VerificationStrategy.READ_BACK_FIELDS,
            idempotency = IdempotencyStrategy.TENANT_KEY,
            requiresCapabilities = setOf(Capability.PAYMENT_CREATE, Capability.READ_BACK_VERIFICATION),
            sideEffects = listOf(
                SideEffect("PAYMENT_REGISTERED", "side_effect_payment_registered"),
                SideEffect("BALANCE_REDUCED", "side_effect_balance_reduced"),
            ),
            preview = ActionPreview(
                titleKey = "preview_payment_title",
                summaryKey = "preview_payment_summary",
                willDoKeys = listOf(
                    "preview_payment_will_register",
                    "preview_payment_will_reduce_balance",
                ),
                willNotDoKeys = listOf(
                    "preview_payment_will_not_move_funds",
                    "preview_payment_will_not_reconcile_bank",
                ),
                reversalKey = "preview_payment_reversal",
            ),
        ),
    )

    private val byTool: Map<ToolName, ToolDefinition> = definitions.associateBy { it.tool }

    fun find(tool: ToolName): ToolDefinition? = byTool[tool]

    fun require(tool: ToolName): ToolDefinition =
        byTool[tool] ?: throw IllegalArgumentException("$tool has no contract and must not execute")

    fun byWire(wire: String, version: String? = null): ToolDefinition? {
        val tool = ToolName.fromWire(wire) ?: return null
        val definition = byTool[tool] ?: return null
        if (version != null && version.isNotBlank() && version != definition.version) return null
        return definition
    }

    /**
     * Capability-driven availability. A tool whose connector cannot run it is
     * reported as unavailable, not offered and then failed.
     */
    fun isAvailable(tool: ToolName, capabilities: Set<Capability>): Boolean =
        byTool[tool]?.requiresCapabilities?.all { it in capabilities } ?: false

    fun unavailableCapabilities(tool: ToolName, capabilities: Set<Capability>): Set<Capability> =
        byTool[tool]?.requiresCapabilities?.filterNot { it in capabilities }?.toSet() ?: emptySet()
}
