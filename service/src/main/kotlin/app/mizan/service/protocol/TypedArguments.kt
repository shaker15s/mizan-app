package app.mizan.service.protocol

import app.mizan.domain.model.CancelOrderArgs
import app.mizan.domain.model.CreateDraftOrderArgs
import app.mizan.domain.model.CreateInvoiceArgs
import app.mizan.domain.model.CustomerSearchArgs
import app.mizan.domain.model.Money
import app.mizan.domain.model.RegisterPaymentArgs
import app.mizan.domain.model.SalesSummaryArgs
import app.mizan.domain.model.StockLookupArgs
import app.mizan.domain.model.ToolArgs
import app.mizan.domain.model.ToolName
import app.mizan.service.json.text
import app.mizan.service.json.wholeOrNumericText

/**
 * The typed arguments a request carries, or null when the request cannot be
 * read as them.
 *
 * The service keeps the same typed shape the device does, rather than a map of
 * strings, because the typed shape is what the proposal fingerprint is
 * computed over. A service that hashed its own idea of "the arguments" and a
 * device that hashed `ToolArgs.canonical()` would be two different hashes of
 * the same order, and the approval would be refused for a reason no human
 * could explain.
 *
 * Null means the request does not carry what the tool needs. It is never a
 * default: a missing amount is not zero.
 */
fun ExecutionRequest.typedArguments(tool: ToolName): ToolArgs? = when (tool) {
    ToolName.STOCK_AVAILABILITY ->
        arguments.text(MizanContract.ArgumentField.SKU)?.let { StockLookupArgs(it) }

    ToolName.CUSTOMER_SEARCH ->
        arguments.text(MizanContract.ArgumentField.QUERY)?.let { CustomerSearchArgs(it) }

    ToolName.SALES_SUMMARY ->
        SalesSummaryArgs(arguments.text(MizanContract.ArgumentField.PERIOD) ?: "current")

    ToolName.CREATE_DRAFT_ORDER -> {
        val customer = arguments.text(MizanContract.ArgumentField.CUSTOMER_NAME)
        val items = arguments.text(MizanContract.ArgumentField.ITEMS_SUMMARY)
        val amount = money()
        if (customer == null || items == null || amount == null) {
            null
        } else {
            CreateDraftOrderArgs(customer, amount, items)
        }
    }

    ToolName.CANCEL_ORDER -> {
        val orderId = arguments.text(MizanContract.ArgumentField.ORDER_ID)
        val reason = arguments.text(MizanContract.ArgumentField.REASON)
        if (orderId == null || reason == null) null else CancelOrderArgs(orderId, reason)
    }

    ToolName.CREATE_INVOICE ->
        arguments.text(MizanContract.ArgumentField.ORDER_ID)?.let { CreateInvoiceArgs(it) }

    ToolName.REGISTER_PAYMENT -> {
        val invoiceId = arguments.text(MizanContract.ArgumentField.INVOICE_ID)
        val amount = money()
        if (invoiceId == null || amount == null) null else RegisterPaymentArgs(invoiceId, amount)
    }

    ToolName.UNKNOWN -> null
}

private fun ExecutionRequest.money(): Money? {
    val minor = arguments.wholeOrNumericText(MizanContract.ArgumentField.AMOUNT_MINOR) ?: return null
    val currency = arguments.text(MizanContract.ArgumentField.CURRENCY) ?: return null
    return runCatching { Money(minor, currency) }.getOrNull()
}
