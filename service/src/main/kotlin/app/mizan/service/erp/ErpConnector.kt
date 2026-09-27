package app.mizan.service.erp

import app.mizan.domain.model.ConnectorCapabilities
import app.mizan.domain.model.Money
import app.mizan.domain.tool.Capability
import app.mizan.domain.tool.asCapabilities

/**
 * The ERP boundary.
 *
 * Everything the authority does to a customer's system goes through this
 * interface, and nothing above it knows whether the other side is Odoo, a
 * different ERP, or the in-memory reference. Two properties are part of the
 * contract rather than of any implementation:
 *
 *  - a write is never retried at this layer. When the transport cannot prove
 *    whether the request arrived, the answer is [ErpResult.Unknown] and the
 *    caller opens reconciliation;
 *  - a read-back is a separate call. A write that cannot be read back is
 *    accepted, never verified.
 */
sealed interface ErpResult<out T> {

    data class Ok<T>(val value: T) : ErpResult<T>

    /** The ERP understood the request and declined it. Deterministic: not retried. */
    data class Refused(val reasonCode: String, val detail: String = "") : ErpResult<Nothing>

    /** The connector cannot do this at all. The UI hides the action instead. */
    data class NotSupported(val capability: Capability) : ErpResult<Nothing>

    /** Temporarily unreachable before dispatch. Safe to retry, and already backoff-shaped. */
    data class Unavailable(val reasonCode: String, val retryAfterMillis: Long? = null) : ErpResult<Nothing>

    /** The connector's answer did not match the schema it promised. */
    data class Malformed(val reasonCode: String, val detail: String = "") : ErpResult<Nothing>

    /**
     * The request was dispatched and no answer proved anything. The ERP may
     * hold the record. This result must never be retried and never read as a
     * failure with no consequences.
     */
    data class Unknown(val reasonCode: String, val detail: String = "") : ErpResult<Nothing>
}

fun <T> ErpResult<T>.valueOrNull(): T? = (this as? ErpResult.Ok)?.value

data class ErpRecord(
    val model: String,
    val recordId: String,
    val fields: Map<String, String>,
    val summary: String = "",
)

data class ErpCustomer(
    val recordId: String,
    val name: String,
    val creditMinor: Long?,
    val balanceMinor: Long?,
    val currency: String?,
    val status: String,
)

data class ErpStock(
    val sku: String,
    val name: String,
    val availableQty: Int,
    val reservedQty: Int,
    val unitPriceMinor: Long?,
    val currency: String?,
    val location: String,
)

data class ErpCapabilities(
    val connectorId: String,
    val capabilities: Set<Capability>,
    val models: Map<String, Boolean>,
    val discoveredAtMillis: Long,
    val notes: List<String> = emptyList(),
) {
    fun toConnectorCapabilities(): ConnectorCapabilities = ConnectorCapabilities(
        connectorId = connectorId,
        supportsDraftOrders = Capability.SALES_ORDER_CREATE in capabilities,
        supportsOrderCancel = Capability.SALES_ORDER_CANCEL in capabilities,
        supportsInvoiceCreation = Capability.INVOICE_CREATE in capabilities,
        supportsPayment = Capability.PAYMENT_CREATE in capabilities,
        supportsVerification = Capability.READ_BACK_VERIFICATION in capabilities,
        supportsBatchRead = Capability.BATCH_READ in capabilities,
        supportsJson2 = Capability.JSON2_TRANSPORT in capabilities,
        supportsLegacyRpc = Capability.LEGACY_RPC_TRANSPORT in capabilities,
    )
}

interface ErpConnector {

    /** Stable identity of the connector implementation, for logs and receipts. */
    val id: String

    /** What this connector can do right now, discovered rather than assumed. */
    fun capabilities(): ErpCapabilities

    fun findCustomer(tenantId: String, query: String): ErpResult<List<ErpCustomer>>

    fun checkStock(tenantId: String, sku: String): ErpResult<ErpStock>

    fun salesSummary(tenantId: String, period: String): ErpResult<String>

    fun createDraftOrder(
        tenantId: String,
        customerName: String,
        amount: Money,
        itemsSummary: String,
    ): ErpResult<ErpRecord>

    fun cancelOrder(tenantId: String, orderId: String, reason: String): ErpResult<ErpRecord>

    fun createInvoice(tenantId: String, orderId: String): ErpResult<ErpRecord>

    fun registerPayment(tenantId: String, invoiceId: String, amount: Money): ErpResult<ErpRecord>

    /** The verification step. Never folded into a write. */
    fun readBack(tenantId: String, model: String, recordId: String): ErpResult<ErpRecord>

    /** The amount an order carries, read from the ERP rather than the request body. */
    fun orderAmount(tenantId: String, orderId: String): ErpResult<Money>

    /** Candidate record ids offered to a person when the outcome is uncertain. */
    fun candidates(tenantId: String, model: String, limit: Int = 5): List<String>

    /** Cached or freshly read customer state, for the blocked/credit checks. */
    fun customerState(tenantId: String, customerName: String): ErpCustomer?

    /** Candidate records in the reconciliation window, with the fields a comparison needs. */
    fun recentRecords(tenantId: String, model: String, limit: Int): List<ErpRecord>

    /** Whether the connector currently answers. Used by health, never by policy. */
    fun reachable(): Boolean
}

/**
 * The reference connector. It adapts the in-memory ERP to the same contract a
 * real connector implements, so the authority cannot depend on which one is
 * underneath it.
 */
class InMemoryErpConnector(
    private val erp: InMemoryErp,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ErpConnector {

    override val id: String = "in-memory-reference"

    override fun capabilities(): ErpCapabilities = ErpCapabilities(
        connectorId = id,
        capabilities = ConnectorCapabilities.simulation.asCapabilities() - Capability.JSON2_TRANSPORT -
            Capability.LEGACY_RPC_TRANSPORT,
        models = mapOf(
            "sale.order" to true,
            "account.move" to true,
            "account.payment" to true,
            "stock.quant" to true,
            "res.partner" to true,
        ),
        discoveredAtMillis = clock(),
        notes = listOf("reference adapter: not an ERP, and not a proof about one"),
    )

    override fun findCustomer(tenantId: String, query: String): ErpResult<List<ErpCustomer>> {
        val matches = erp.customerMatches(tenantId, query).map { row ->
            ErpCustomer(
                recordId = row.id,
                name = row.name,
                creditMinor = row.creditMinor,
                balanceMinor = row.balanceMinor,
                currency = row.currency,
                status = row.status,
            )
        }
        return ErpResult.Ok(matches)
    }

    override fun checkStock(tenantId: String, sku: String): ErpResult<ErpStock> {
        erp.stockAvailability(tenantId, sku) ?: return ErpResult.Refused("STOCK_NOT_FOUND")
        val row = erp.stockRow(sku) ?: return ErpResult.Refused("STOCK_NOT_FOUND")
        return ErpResult.Ok(
            ErpStock(
                sku = row.sku,
                name = row.name,
                availableQty = row.availableQty - row.reservedQty,
                reservedQty = row.reservedQty,
                unitPriceMinor = row.priceMinor,
                currency = row.currency,
                location = row.location,
            ),
        )
    }

    override fun salesSummary(tenantId: String, period: String): ErpResult<String> =
        ErpResult.Ok(erp.salesSummary(tenantId, period).summary)

    override fun createDraftOrder(
        tenantId: String,
        customerName: String,
        amount: Money,
        itemsSummary: String,
    ): ErpResult<ErpRecord> {
        val write = erp.createDraftOrder(tenantId, customerName, amount, itemsSummary)
        return ErpResult.Ok(
            ErpRecord(
                model = write.model,
                recordId = write.recordId,
                fields = mapOf(
                    "customerName" to customerName,
                    "amountMinor" to amount.minorUnits.toString(),
                    "currency" to amount.currency,
                    "state" to "draft",
                ),
            ),
        )
    }

    override fun cancelOrder(tenantId: String, orderId: String, reason: String): ErpResult<ErpRecord> {
        val write = erp.cancelOrder(tenantId, orderId, reason)
            ?: return ErpResult.Refused("ORDER_NOT_FOUND")
        return ErpResult.Ok(
            ErpRecord(
                model = write.model,
                recordId = write.recordId,
                fields = mapOf("state" to "cancelled", "reason" to reason),
            ),
        )
    }

    override fun createInvoice(tenantId: String, orderId: String): ErpResult<ErpRecord> {
        val write = erp.createInvoice(tenantId, orderId)
            ?: return ErpResult.Refused("ORDER_NOT_FOUND_OR_CANCELLED")
        val amount = erp.orderAmount(tenantId, orderId)
        return ErpResult.Ok(
            ErpRecord(
                model = write.model,
                recordId = write.recordId,
                fields = mapOf(
                    "orderId" to orderId,
                    "amountMinor" to (amount?.minorUnits?.toString() ?: ""),
                    "currency" to (amount?.currency ?: ""),
                ),
            ),
        )
    }

    override fun registerPayment(tenantId: String, invoiceId: String, amount: Money): ErpResult<ErpRecord> {
        // A payment is refused either because the invoice is not there or
        // because it is in another currency. Both are the same answer to the
        // person: nothing was written and the invoice did not match.
        val write = erp.registerPayment(tenantId, invoiceId, amount)
            ?: return ErpResult.Refused("INVOICE_NOT_FOUND_OR_CURRENCY_MISMATCH")
        return ErpResult.Ok(
            ErpRecord(
                model = write.model,
                recordId = write.recordId,
                fields = mapOf(
                    "invoiceId" to invoiceId,
                    "amountMinor" to amount.minorUnits.toString(),
                    "currency" to amount.currency,
                ),
            ),
        )
    }

    override fun readBack(tenantId: String, model: String, recordId: String): ErpResult<ErpRecord> {
        val read = erp.readBack(tenantId, model, recordId)
            ?: return ErpResult.Refused("RECORD_NOT_FOUND")
        return ErpResult.Ok(
            ErpRecord(
                model = read.model,
                recordId = read.recordId,
                // Named fields when the ERP supplied them; the prose summary
                // is only parsed as a fallback, never as the primary source.
                fields = read.fields.ifEmpty { parseSummary(read.summary) },
                summary = read.summary,
            ),
        )
    }

    override fun orderAmount(tenantId: String, orderId: String): ErpResult<Money> =
        erp.orderAmount(tenantId, orderId)?.let { ErpResult.Ok(it) } ?: ErpResult.Refused("ORDER_NOT_FOUND")

    override fun candidates(tenantId: String, model: String, limit: Int): List<String> =
        erp.candidates(tenantId, model, limit)

    override fun customerState(tenantId: String, customerName: String): ErpCustomer? {
        val row = erp.customerState(customerName) ?: return null
        return ErpCustomer(
            recordId = row.id,
            name = row.name,
            creditMinor = row.creditMinor,
            balanceMinor = row.balanceMinor,
            currency = row.currency,
            status = row.status,
        )
    }

    override fun recentRecords(tenantId: String, model: String, limit: Int): List<ErpRecord> =
        candidates(tenantId, model, limit).mapNotNull { recordId ->
            when (val read = readBack(tenantId, model, recordId)) {
                is ErpResult.Ok -> read.value
                else -> null
            }
        }

    override fun reachable(): Boolean = true

    private fun parseSummary(summary: String): Map<String, String> =
        summary.split(' ')
            .mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size == 2) pieces[0] to pieces[1] else null
            }
            .toMap()
}
