package app.mizan.service.erp

import app.mizan.domain.model.Money
import app.mizan.service.protocol.MizanContract

/**
 * The ERP side of the reference service.
 *
 * This is an in-memory ledger with the shape of an ERP: it assigns record
 * ids, holds state, and answers read-back queries. It is NOT Odoo, it does
 * not call Odoo, and it proves nothing about a customer's ERP. It exists so
 * the read-back path has something real to read back.
 *
 * Every mutation returns a record id. Verification is a separate read: a
 * write that cannot be read back is reported as accepted, not verified.
 */
class InMemoryErp {

    private val orders = LinkedHashMap<String, OrderRow>()
    private val invoices = LinkedHashMap<String, InvoiceRow>()
    private val payments = LinkedHashMap<String, PaymentRow>()
    private val customers = LinkedHashMap<String, CustomerRow>()
    private val stock = LinkedHashMap<String, StockRow>()

    private var orderSequence = 1000L
    private var invoiceSequence = 5000L
    private var paymentSequence = 7000L

    init {
        customers["acme corp"] = CustomerRow("CUS-1", "Acme Corp", 500_000L, 120_000L, "USD", "active")
        customers["al-amal trading"] = CustomerRow("CUS-2", "Al-Amal Trading", 40_000_000L, 3_500_000L, "EGP", "active")
        customers["nile industrial"] = CustomerRow("CUS-3", "Nile Industrial", 900_000L, 880_000L, "EGP", "blocked")
        stock["SKU-DESK-01"] = StockRow("SKU-DESK-01", "Standing desk", 42, 4, 125_000L, "USD", "WH-1")
        stock["SKU-CHAIR-99"] = StockRow("SKU-CHAIR-99", "Task chair", 17, 2, 45_000L, "USD", "WH-1")
    }

    @Synchronized
    fun createDraftOrder(
        tenantId: String,
        customerName: String,
        amount: Money,
        itemsSummary: String,
    ): ErpWrite {
        val id = "SO-${++orderSequence}"
        orders[id] = OrderRow(
            id = id,
            tenantId = tenantId,
            customerName = customerName,
            amountMinor = amount.minorUnits,
            currency = amount.currency,
            itemsSummary = itemsSummary,
            state = "draft",
        )
        return ErpWrite(id, MizanContract.ErpModel.SALE_ORDER)
    }

    @Synchronized
    fun cancelOrder(tenantId: String, orderId: String, reason: String): ErpWrite? {
        val row = orders[orderId] ?: return null
        if (row.tenantId != tenantId) return null
        if (row.state == "cancelled") return null
        orders[orderId] = row.copy(state = "cancelled", note = reason)
        return ErpWrite(orderId, MizanContract.ErpModel.SALE_ORDER)
    }

    /** The amount an order would be invoiced for, or null if it is not this tenant's order. */
    @Synchronized
    fun orderAmount(tenantId: String, orderId: String): Money? {
        val row = orders[orderId] ?: return null
        if (row.tenantId != tenantId) return null
        return runCatching { Money(row.amountMinor, row.currency) }.getOrNull()
    }

    @Synchronized
    fun createInvoice(tenantId: String, orderId: String): ErpWrite? {
        val order = orders[orderId] ?: return null
        if (order.tenantId != tenantId) return null
        if (order.state == "cancelled") return null
        val id = "INV-${++invoiceSequence}"
        invoices[id] = InvoiceRow(id, tenantId, orderId, order.amountMinor, order.currency)
        return ErpWrite(id, MizanContract.ErpModel.INVOICE)
    }

    @Synchronized
    fun registerPayment(tenantId: String, invoiceId: String, amount: Money): ErpWrite? {
        val invoice = invoices[invoiceId] ?: return null
        if (invoice.tenantId != tenantId) return null
        if (invoice.currency != amount.currency) return null
        val id = "PAY-${++paymentSequence}"
        payments[id] = PaymentRow(id, tenantId, invoiceId, amount.minorUnits, amount.currency)
        return ErpWrite(id, MizanContract.ErpModel.PAYMENT)
    }

    @Synchronized
    fun stockAvailability(tenantId: String, sku: String): ErpRead? {
        val row = stock[sku.uppercase()] ?: return null
        return ErpRead(
            model = MizanContract.ErpModel.STOCK,
            recordId = row.sku,
            summary = "available=${row.availableQty - row.reservedQty} reserved=${row.reservedQty}",
            tenantId = tenantId,
        )
    }

    @Synchronized
    fun customerSearch(tenantId: String, query: String): ErpRead {
        val needle = query.trim().lowercase()
        val hits = customers.values.filter { it.name.lowercase().contains(needle) }
        return ErpRead(
            model = MizanContract.ErpModel.CUSTOMER,
            recordId = hits.firstOrNull()?.id ?: "none",
            summary = "matches=${hits.size}",
            tenantId = tenantId,
        )
    }

    @Synchronized
    fun salesSummary(tenantId: String, period: String): ErpRead {
        val scoped = orders.values.filter { it.tenantId == tenantId && it.state != "cancelled" }
        val total = scoped.sumOf { it.amountMinor }
        val currency = scoped.firstOrNull()?.currency ?: "XXX"
        return ErpRead(
            model = MizanContract.ErpModel.ANALYTICS,
            recordId = period,
            summary = "orders=${scoped.size} totalMinor=$total currency=$currency",
            tenantId = tenantId,
        )
    }

    /** The verification step. A write is only verified when this finds it back. */
    @Synchronized
    fun readBack(tenantId: String, model: String, recordId: String): ErpRead? = when (model) {
        MizanContract.ErpModel.SALE_ORDER -> {
            val row = orders[recordId] ?: return null
            if (row.tenantId != tenantId) return null
            ErpRead(model, row.id, "state=${row.state} amountMinor=${row.amountMinor} ${row.currency}", tenantId)
        }
        MizanContract.ErpModel.INVOICE -> {
            val row = invoices[recordId] ?: return null
            if (row.tenantId != tenantId) return null
            ErpRead(model, row.id, "orderId=${row.orderId} amountMinor=${row.amountMinor} ${row.currency}", tenantId)
        }
        MizanContract.ErpModel.PAYMENT -> {
            val row = payments[recordId] ?: return null
            if (row.tenantId != tenantId) return null
            ErpRead(model, row.id, "invoiceId=${row.invoiceId} amountMinor=${row.amountMinor} ${row.currency}", tenantId)
        }
        else -> null
    }

    /** Candidate ids offered to a human when the outcome is uncertain. */
    @Synchronized
    fun candidates(tenantId: String, model: String, limit: Int = 5): List<String> = when (model) {
        MizanContract.ErpModel.SALE_ORDER -> orders.values
            .filter { it.tenantId == tenantId }
            .takeLast(limit)
            .map { it.id }
        MizanContract.ErpModel.INVOICE -> invoices.values
            .filter { it.tenantId == tenantId }
            .takeLast(limit)
            .map { it.id }
        MizanContract.ErpModel.PAYMENT -> payments.values
            .filter { it.tenantId == tenantId }
            .takeLast(limit)
            .map { it.id }
        else -> emptyList()
    }

    @Synchronized
    fun customerState(customerName: String): CustomerRow? = customers[customerName.trim().lowercase()]

    @Synchronized
    fun snapshot(tenantId: String): ErpSnapshot = ErpSnapshot(
        orders = orders.values.filter { it.tenantId == tenantId }.map { it.id to it.state },
        invoices = invoices.values.filter { it.tenantId == tenantId }.map { it.id to it.currency },
        payments = payments.values.filter { it.tenantId == tenantId }.map { it.id to it.currency },
    )

    data class ErpWrite(val recordId: String, val model: String)

    data class ErpRead(
        val model: String,
        val recordId: String,
        val summary: String,
        val tenantId: String,
    )

    data class ErpSnapshot(
        val orders: List<Pair<String, String>>,
        val invoices: List<Pair<String, String>>,
        val payments: List<Pair<String, String>>,
    )

    data class OrderRow(
        val id: String,
        val tenantId: String,
        val customerName: String,
        val amountMinor: Long,
        val currency: String,
        val itemsSummary: String,
        val state: String,
        val note: String? = null,
    )

    data class InvoiceRow(
        val id: String,
        val tenantId: String,
        val orderId: String,
        val amountMinor: Long,
        val currency: String,
    )

    data class PaymentRow(
        val id: String,
        val tenantId: String,
        val invoiceId: String,
        val amountMinor: Long,
        val currency: String,
    )

    data class CustomerRow(
        val id: String,
        val name: String,
        val creditMinor: Long,
        val balanceMinor: Long,
        val currency: String,
        val status: String,
    )

    data class StockRow(
        val sku: String,
        val name: String,
        val availableQty: Int,
        val reservedQty: Int,
        val priceMinor: Long,
        val currency: String,
        val location: String,
    )
}
