package app.mizan.service.erp

import app.mizan.domain.model.Money
import app.mizan.domain.security.SecretMaterial
import app.mizan.domain.tool.Capability
import app.mizan.service.json.Json
import app.mizan.service.json.JsonValue
import app.mizan.service.json.asObject
import app.mizan.service.json.asString
import app.mizan.service.json.field
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * Where an API key lives.
 *
 * A tenant's ERP binding is a server-side object. The device never holds the
 * key, never sends it, and cannot ask for it: this type exists only inside the
 * authority.
 */
data class ErpBinding(
    val tenantId: String,
    val baseUrl: String,
    val database: String?,
    val apiKey: SecretMaterial,
    val companyId: Long? = null,
    val label: String = "odoo",
    val readOnly: Boolean = false,
) {
    init {
        val loopback = baseUrl.startsWith("http://127.0.0.1") || baseUrl.startsWith("http://localhost")
        require(baseUrl.startsWith("https://") || loopback) {
            "an ERP binding must be https, or loopback for tests"
        }
    }

    // The key must not reach a log line through a rendered data class.
    override fun toString(): String =
        "ErpBinding(tenant=$tenantId, base=$baseUrl, database=${database ?: "-"}, apiKey=redacted)"
}

interface TenantErpRegistry {
    fun binding(tenantId: String): ErpBinding?
    fun bindings(): List<ErpBinding>
}

class InMemoryTenantErpRegistry(bindings: List<ErpBinding> = emptyList()) : TenantErpRegistry {
    private val byTenant = LinkedHashMap<String, ErpBinding>()

    init {
        bindings.forEach { byTenant[it.tenantId] = it }
    }

    fun register(binding: ErpBinding) {
        byTenant[binding.tenantId] = binding
    }

    override fun binding(tenantId: String): ErpBinding? = byTenant[tenantId]

    override fun bindings(): List<ErpBinding> = byTenant.values.toList()
}

/** What the transport observed. The distinction is the whole point. */
sealed interface TransportOutcome {

    data class Received(val status: Int, val body: String, val retryAfterMillis: Long? = null) : TransportOutcome

    /**
     * The request provably never left the process: DNS did not resolve, the
     * socket was refused. A retry cannot duplicate anything.
     */
    data class NotSent(val reasonCode: String, val detail: String) : TransportOutcome

    /**
     * The request was written to the wire and no answer proved anything: a
     * read timeout, a reset connection, a truncated body. This must never be
     * retried and never reported as a failure with no consequences.
     */
    data class DispatchUnknown(val reasonCode: String, val detail: String) : TransportOutcome
}

fun interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: String, timeoutMillis: Long): TransportOutcome
}

/**
 * The JDK's own HTTP client, so the service ships no network stack of its own
 * and has nothing transitive to audit.
 */
class JdkHttpTransport(
    private val connectTimeoutMillis: Long = 4_000,
) : HttpTransport {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMillis: Long,
    ): TransportOutcome {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMillis))
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> builder.header(name, value) }
        val request = try {
            builder.build()
        } catch (error: IllegalArgumentException) {
            return TransportOutcome.NotSent("ERP_REQUEST_INVALID", error.message ?: "")
        }
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            TransportOutcome.Received(
                status = response.statusCode(),
                body = response.body() ?: "",
                retryAfterMillis = response.headers().firstValue("Retry-After").orElse(null)
                    ?.toLongOrNull()?.times(1000L),
            )
        } catch (error: ConnectException) {
            TransportOutcome.NotSent("ERP_UNREACHABLE", error.message ?: "connection refused")
        } catch (error: UnknownHostException) {
            TransportOutcome.NotSent("ERP_DNS_FAILED", error.message ?: "unknown host")
        } catch (error: HttpTimeoutException) {
            TransportOutcome.DispatchUnknown("ERP_TIMEOUT", error.message ?: "timeout")
        } catch (error: IOException) {
            TransportOutcome.DispatchUnknown("ERP_IO", error.message ?: "io error")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            TransportOutcome.DispatchUnknown("ERP_INTERRUPTED", error.message ?: "interrupted")
        }
    }
}

/** Internal control flow. Never escapes a public method. */
private class ErpFailure(val result: ErpResult<Nothing>) : RuntimeException(null, null, false, false)

private fun <T> ErpResult<T>.orAbort(): T = when (this) {
    is ErpResult.Ok -> value
    is ErpResult.Refused -> throw ErpFailure(ErpResult.Refused(reasonCode, detail))
    is ErpResult.NotSupported -> throw ErpFailure(ErpResult.NotSupported(capability))
    is ErpResult.Unavailable -> throw ErpFailure(ErpResult.Unavailable(reasonCode, retryAfterMillis))
    is ErpResult.Malformed -> throw ErpFailure(ErpResult.Malformed(reasonCode, detail))
    is ErpResult.Unknown -> throw ErpFailure(ErpResult.Unknown(reasonCode, detail))
}

/**
 * The Odoo 19 connector.
 *
 * It speaks JSON-2 only. XML-RPC stays out of the write path: Odoo has
 * scheduled the external RPC APIs for removal, and a new integration built on
 * a transport with an end date is a liability.
 *
 * Reads carry a short deadline. Writes carry a longer one and are never
 * retried here; a write whose answer was lost is reported as
 * [ErpResult.Unknown], which stops the path at reconciliation instead of
 * creating a second record.
 */
class OdooJson2Connector(
    private val registry: TenantErpRegistry,
    private val transport: HttpTransport = JdkHttpTransport(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val readTimeoutMillis: Long = 8_000,
    private val writeTimeoutMillis: Long = 20_000,
    private val capabilityTtlMillis: Long = 5 * 60 * 1000L,
) : ErpConnector {

    override val id: String = "odoo-19-json2"

    private val capabilityCache = LinkedHashMap<String, ErpCapabilities>()

    override fun capabilities(): ErpCapabilities {
        val binding = registry.bindings().firstOrNull()
            ?: return ErpCapabilities(
                connectorId = id,
                capabilities = emptySet(),
                models = emptyMap(),
                discoveredAtMillis = clock(),
                notes = listOf("no ERP binding is configured for any tenant"),
            )
        val cached = capabilityCache[binding.tenantId]
        if (cached != null && clock() - cached.discoveredAtMillis < capabilityTtlMillis) return cached
        val discovered = discover(binding)
        capabilityCache[binding.tenantId] = discovered
        return discovered
    }

    /** Asks the ERP which models it has, instead of assuming a version. */
    fun discover(binding: ErpBinding): ErpCapabilities {
        val models = listOf("sale.order", "account.move", "account.payment", "stock.quant", "res.partner")
        val present = LinkedHashMap<String, Boolean>()
        val notes = mutableListOf<String>()
        for (model in models) {
            val body = Json.write(Json.obj("domain" to OdooWire.domain()))
            present[model] = when (val result = call(binding, model, "search_count", body, readTimeoutMillis)) {
                is ErpResult.Ok -> true
                is ErpResult.Refused -> {
                    notes += "$model is not readable with this key"
                    false
                }
                is ErpResult.Unavailable, is ErpResult.Unknown -> {
                    notes += "$model could not be checked: ${(result as? ErpResult.Unavailable)?.reasonCode ?: "unknown"}"
                    false
                }
                is ErpResult.NotSupported, is ErpResult.Malformed -> false
            }
        }
        val capabilities = buildSet {
            add(Capability.JSON2_TRANSPORT)
            if (present["stock.quant"] == true) add(Capability.STOCK_READ)
            if (present["res.partner"] == true) add(Capability.CUSTOMER_SEARCH)
            if (present["sale.order"] == true) {
                add(Capability.ANALYTICS_SALES)
                add(Capability.SALES_ORDER_CREATE)
                add(Capability.SALES_ORDER_CANCEL)
                add(Capability.BATCH_READ)
            }
            if (present["account.move"] == true) add(Capability.INVOICE_CREATE)
            if (present["account.payment"] == true) add(Capability.PAYMENT_CREATE)
            if (present.values.any { it }) add(Capability.READ_BACK_VERIFICATION)
        }
        return ErpCapabilities(
            connectorId = id,
            capabilities = capabilities,
            models = present,
            discoveredAtMillis = clock(),
            notes = notes + "discovered over JSON-2; XML-RPC is not used on any write path",
        )
    }

    fun invalidateCapabilities() {
        capabilityCache.clear()
    }

    override fun findCustomer(tenantId: String, query: String): ErpResult<List<ErpCustomer>> = guard {
        val binding = bindingOf(tenantId)
        val domain = OdooWire.domain(OdooWire.leaf("name", "ilike", Json.str(query)))
        val rows = OdooWire.rows(
            call(binding, "res.partner", "search_read", OdooWire.searchReadBody(domain, CUSTOMER_FIELDS, 10), readTimeoutMillis).orAbort(),
            "res.partner",
        ).orAbort()
        rows.mapNotNull { customerOf(it) }
    }

    override fun checkStock(tenantId: String, sku: String): ErpResult<ErpStock> = guard {
        val binding = bindingOf(tenantId)
        val domain = OdooWire.domain(OdooWire.leaf("product_id.default_code", "=", Json.str(sku)))
        val rows = OdooWire.rows(
            call(binding, "stock.quant", "search_read", OdooWire.searchReadBody(domain, STOCK_FIELDS, 5), readTimeoutMillis).orAbort(),
            "stock.quant",
        ).orAbort()
        val row = rows.firstOrNull() ?: abort(ErpResult.Refused("STOCK_NOT_FOUND", sku))
        ErpStock(
            sku = sku,
            name = OdooWire.many2One(row.field("product_id"))?.second ?: sku,
            availableQty = (OdooWire.numberOrNull(row.field("quantity")) ?: 0L).toInt(),
            reservedQty = (OdooWire.numberOrNull(row.field("reserved_quantity")) ?: 0L).toInt(),
            unitPriceMinor = null,
            currency = null,
            location = OdooWire.many2One(row.field("location_id"))?.second ?: "",
        )
    }

    override fun salesSummary(tenantId: String, period: String): ErpResult<String> = guard {
        val binding = bindingOf(tenantId)
        val domain = OdooWire.domain(OdooWire.leaf("state", "=", Json.str("sale")))
        val rows = OdooWire.rows(
            call(
                binding,
                "sale.order",
                "search_read",
                OdooWire.searchReadBody(domain, listOf("id", "amount_total", "currency_id"), 200),
                readTimeoutMillis,
            ).orAbort(),
            "sale.order",
        ).orAbort()
        var totalMinor = 0L
        var currency = "XXX"
        for (row in rows) {
            totalMinor += OdooWire.minorUnitsOrNull(row.field("amount_total")) ?: 0L
            OdooWire.many2One(row.field("currency_id"))?.second?.let { currency = currencyLabel(it) }
        }
        "orders=${rows.size} totalMinor=$totalMinor currency=$currency period=$period"
    }

    override fun createDraftOrder(
        tenantId: String,
        customerName: String,
        amount: Money,
        itemsSummary: String,
    ): ErpResult<ErpRecord> = guard {
        val binding = bindingOf(tenantId)
        val partner = resolvePartner(binding, customerName)
        val partnerId = partner.recordId.toLongOrNull()
            ?: abort(ErpResult.Malformed("ODOO_PARTNER_ID_NOT_NUMERIC", partner.recordId))
        // One business operation, one ERP transaction. Creating the order,
        // then its line, then the link would be three transactions, and three
        // transactions can half-apply.
        val body = OdooWire.methodBody(
            mapOf(
                "partner_id" to Json.num(partnerId),
                "amount_minor" to Json.num(amount.minorUnits),
                "currency" to Json.str(amount.currency),
                "items_summary" to Json.str(itemsSummary),
                "client_order_ref" to Json.str("wakeel"),
            ),
        )
        val created = call(binding, "sale.order", OdooWire.METHOD_CREATE_DRAFT_ORDER, body, writeTimeoutMillis).orAbort()
        val id = OdooWire.createdId(created, "sale.order").orAbort()
        ErpRecord(
            model = "sale.order",
            recordId = id.toString(),
            fields = mapOf(
                "customerId" to partner.recordId,
                "customerName" to partner.name,
                "amountMinor" to amount.minorUnits.toString(),
                "currency" to amount.currency,
                "state" to "draft",
                "itemsSummary" to itemsSummary,
            ),
        )
    }

    override fun cancelOrder(tenantId: String, orderId: String, reason: String): ErpResult<ErpRecord> = guard {
        val binding = bindingOf(tenantId)
        val id = numericId(orderId, "orderId")
        call(
            binding,
            "sale.order",
            "action_cancel",
            Json.write(Json.obj("ids" to Json.arr(listOf(Json.num(id))))),
            writeTimeoutMillis,
        ).orAbort()
        ErpRecord(
            model = "sale.order",
            recordId = orderId,
            fields = mapOf("state" to "cancel", "reason" to reason),
        )
    }

    override fun createInvoice(tenantId: String, orderId: String): ErpResult<ErpRecord> = guard {
        val binding = bindingOf(tenantId)
        val id = numericId(orderId, "orderId")
        val created = call(
            binding,
            "sale.order",
            "wakeel_create_invoice",
            OdooWire.methodBody(mapOf("order_id" to Json.num(id))),
            writeTimeoutMillis,
        ).orAbort()
        val invoiceId = OdooWire.createdId(created, "account.move").orAbort()
        ErpRecord("account.move", invoiceId.toString(), mapOf("orderId" to orderId))
    }

    override fun registerPayment(tenantId: String, invoiceId: String, amount: Money): ErpResult<ErpRecord> = guard {
        val binding = bindingOf(tenantId)
        val id = numericId(invoiceId, "invoiceId")
        val created = call(
            binding,
            "account.payment",
            "wakeel_register_payment",
            OdooWire.methodBody(
                mapOf(
                    "invoice_id" to Json.num(id),
                    "amount_minor" to Json.num(amount.minorUnits),
                    "currency" to Json.str(amount.currency),
                ),
            ),
            writeTimeoutMillis,
        ).orAbort()
        val paymentId = OdooWire.createdId(created, "account.payment").orAbort()
        ErpRecord(
            model = "account.payment",
            recordId = paymentId.toString(),
            fields = mapOf(
                "invoiceId" to invoiceId,
                "amountMinor" to amount.minorUnits.toString(),
                "currency" to amount.currency,
            ),
        )
    }

    override fun readBack(tenantId: String, model: String, recordId: String): ErpResult<ErpRecord> = guard {
        val binding = bindingOf(tenantId)
        val id = numericId(recordId, "recordId")
        val fields = VERIFY_FIELDS[model] ?: abort(ErpResult.NotSupported(Capability.READ_BACK_VERIFICATION))
        val rows = OdooWire.rows(
            call(binding, model, "read", OdooWire.readBody(listOf(id), fields), readTimeoutMillis).orAbort(),
            model,
        ).orAbort()
        val row = rows.firstOrNull() ?: abort(ErpResult.Refused("ERP_RECORD_NOT_FOUND", "$model/$recordId"))
        val values = fields.filter { row.field(it) != null }.associateWith { flatten(row.field(it)) }
        ErpRecord(model = model, recordId = recordId, fields = values, summary = summarise(values))
    }

    override fun orderAmount(tenantId: String, orderId: String): ErpResult<Money> = guard {
        val binding = bindingOf(tenantId)
        val id = numericId(orderId, "orderId")
        val rows = OdooWire.rows(
            call(
                binding,
                "sale.order",
                "read",
                OdooWire.readBody(listOf(id), listOf("id", "amount_total", "currency_id")),
                readTimeoutMillis,
            ).orAbort(),
            "sale.order",
        ).orAbort()
        val row = rows.firstOrNull() ?: abort(ErpResult.Refused("ORDER_NOT_FOUND", orderId))
        val minor = OdooWire.minorUnitsOrNull(row.field("amount_total"))
            ?: abort(ErpResult.Malformed("ODOO_AMOUNT_MISSING", orderId))
        val currency = OdooWire.many2One(row.field("currency_id"))?.second
            ?: abort(ErpResult.Malformed("ODOO_CURRENCY_MISSING", orderId))
        Money(minor, currencyLabel(currency))
    }

    override fun candidates(tenantId: String, model: String, limit: Int): List<String> =
        recentRecords(tenantId, model, limit).map { it.recordId }

    override fun customerState(tenantId: String, customerName: String): ErpCustomer? {
        val result = findCustomer(tenantId, customerName)
        if (result !is ErpResult.Ok) return null
        return result.value.firstOrNull { it.name.equals(customerName.trim(), ignoreCase = true) }
            ?: result.value.singleOrNull()
    }

    override fun recentRecords(tenantId: String, model: String, limit: Int): List<ErpRecord> {
        val binding = registry.binding(tenantId) ?: return emptyList()
        val fields = VERIFY_FIELDS[model] ?: return emptyList()
        val body = OdooWire.searchReadBody(OdooWire.domain(), fields, limit)
        val response = call(binding, model, "search_read", body, readTimeoutMillis)
        if (response !is ErpResult.Ok) return emptyList()
        val rows = OdooWire.rows(response.value, model).valueOrNull() ?: return emptyList()
        return rows.mapNotNull { row ->
            val id = OdooWire.numberOrNull(row.field("id"))?.toString() ?: return@mapNotNull null
            val values = fields.filter { row.field(it) != null }.associateWith { flatten(row.field(it)) }
            ErpRecord(model = model, recordId = id, fields = values, summary = summarise(values))
        }
    }

    override fun reachable(): Boolean {
        val binding = registry.bindings().firstOrNull() ?: return false
        val body = Json.write(Json.obj("domain" to OdooWire.domain()))
        return call(binding, "res.partner", "search_count", body, readTimeoutMillis) is ErpResult.Ok
    }

    // ------------------------------------------------------------- internals

    private inline fun <T> guard(block: () -> T): ErpResult<T> = try {
        ErpResult.Ok(block())
    } catch (failure: ErpFailure) {
        failure.result
    }

    private fun abort(result: ErpResult<Nothing>): Nothing = throw ErpFailure(result)

    private fun bindingOf(tenantId: String): ErpBinding =
        registry.binding(tenantId) ?: abort(ErpResult.Refused("ERP_BINDING_MISSING", "tenant $tenantId"))

    private fun numericId(raw: String, what: String): Long =
        raw.trim().toLongOrNull() ?: abort(ErpResult.Refused("${what.uppercase()}_NOT_NUMERIC", raw))

    private fun resolvePartner(binding: ErpBinding, customerName: String): ErpCustomer {
        val domain = OdooWire.domain(OdooWire.leaf("name", "ilike", Json.str(customerName)))
        val rows = OdooWire.rows(
            call(binding, "res.partner", "search_read", OdooWire.searchReadBody(domain, CUSTOMER_FIELDS, 10), readTimeoutMillis).orAbort(),
            "res.partner",
        ).orAbort()
        val candidates = rows.mapNotNull { customerOf(it) }
        val exact = candidates.filter { it.name.equals(customerName.trim(), ignoreCase = true) }
        return when {
            exact.size == 1 -> exact.first()
            exact.isEmpty() && candidates.size == 1 -> candidates.first()
            exact.isEmpty() -> abort(ErpResult.Refused("CUSTOMER_NOT_FOUND", customerName))
            else -> abort(ErpResult.Refused("CUSTOMER_AMBIGUOUS", "${exact.size} exact matches"))
        }
    }

    private fun call(
        binding: ErpBinding,
        model: String,
        method: String,
        body: String,
        timeoutMillis: Long,
        mutating: Boolean = method !in READ_METHODS,
    ): ErpResult<String> {
        if (binding.readOnly && method !in READ_METHODS) {
            return ErpResult.Refused("ERP_BINDING_READ_ONLY", "$model.$method")
        }
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "User-Agent" to "Wakeel/2.0",
            OdooWire.HEADER_AUTHORIZATION to OdooWire.authorizationHeader(binding.apiKey),
        )
        binding.database?.let { headers[OdooWire.HEADER_DATABASE] = it }
        val url = OdooWire.url(binding.baseUrl, model, method)
        return when (val outcome = transport.post(url, headers, body, timeoutMillis)) {
            is TransportOutcome.NotSent -> ErpResult.Unavailable(outcome.reasonCode, null)
            is TransportOutcome.DispatchUnknown -> ErpResult.Unknown("ERP_DISPATCH_UNKNOWN", outcome.detail)
            is TransportOutcome.Received -> interpret(outcome, mutating)
        }
    }

    /**
     * A 5xx on a write is not a clean failure: the ERP may have applied part of
     * the operation before failing. It is reported as [ErpResult.Unknown] so
     * the caller reconciles instead of retrying. A 5xx on a read changes
     * nothing and is reported as retryable.
     */
    private fun interpret(outcome: TransportOutcome.Received, mutating: Boolean): ErpResult<String> = when (outcome.status) {
        in 200..299 -> ErpResult.Ok(outcome.body)
        OdooWire.CODE_INVALID_KEY -> ErpResult.Refused("ERP_AUTH_REJECTED", "the API key was refused")
        OdooWire.CODE_ACCESS_DENIED -> ErpResult.Refused("ERP_ACCESS_DENIED", "the key may not do this")
        OdooWire.CODE_NOT_FOUND -> ErpResult.Refused(
            "ERP_MODEL_OR_METHOD_MISSING",
            OdooWire.errorName(outcome.body) ?: "",
        )
        OdooWire.CODE_RATE_LIMITED ->
            if (mutating) {
                ErpResult.Unknown("ERP_RATE_LIMITED_DURING_WRITE", "http 429")
            } else {
                ErpResult.Unavailable("ERP_RATE_LIMITED", outcome.retryAfterMillis ?: 30_000L)
            }
        in 400..499 -> ErpResult.Refused(
            "ERP_REJECTED",
            OdooWire.errorName(outcome.body) ?: "http ${outcome.status}",
        )
        in 500..599 ->
            if (mutating) {
                ErpResult.Unknown("ERP_FAILED_DURING_WRITE", "http ${outcome.status}")
            } else {
                ErpResult.Unavailable("ERP_UNAVAILABLE", outcome.retryAfterMillis)
            }
        else -> ErpResult.Malformed("ERP_UNEXPECTED_STATUS", "http ${outcome.status}")
    }

    private fun customerOf(row: JsonValue.Obj): ErpCustomer? {
        val id = OdooWire.numberOrNull(row.field("id"))?.toString() ?: return null
        val name = row.field("name")?.asString() ?: return null
        val active = row.field("active")
        val flaggedActive = active == null || active.asString() != "false"
        return ErpCustomer(
            recordId = id,
            name = name,
            creditMinor = OdooWire.minorUnitsOrNull(row.field("credit_limit")),
            balanceMinor = OdooWire.minorUnitsOrNull(row.field("credit")),
            currency = null,
            status = if (flaggedActive) "active" else "blocked",
        )
    }

    private fun flatten(value: JsonValue?): String = when (value) {
        null -> ""
        is JsonValue.Str -> value.value
        is JsonValue.Num -> value.raw
        is JsonValue.Bool -> value.value.toString()
        is JsonValue.Arr -> OdooWire.many2One(value)?.second ?: value.items.joinToString(",") { flatten(it) }
        is JsonValue.Obj -> value.fields.entries.joinToString(",") { "${it.key}=${flatten(it.value)}" }
        JsonValue.Null -> ""
    }

    private fun summarise(values: Map<String, String>): String =
        values.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }

    /** `$` and `USD` both mean USD; an unknown label is passed through. */
    private fun currencyLabel(label: String): String {
        val cleaned = label.trim()
        if (cleaned.length == 3 && cleaned.all { it.isLetter() }) return cleaned.uppercase()
        return CURRENCY_BY_SYMBOL[cleaned] ?: CURRENCY_BY_NAME[cleaned.lowercase()] ?: cleaned.uppercase().take(3)
    }

    companion object {
        val CUSTOMER_FIELDS = listOf("id", "name", "credit_limit", "credit", "active")
        val STOCK_FIELDS = listOf("id", "product_id", "quantity", "reserved_quantity", "location_id")

        /** The fields a read-back compares. Verification is field by field. */
        val VERIFY_FIELDS: Map<String, List<String>> = mapOf(
            "sale.order" to listOf("id", "name", "state", "amount_total", "currency_id", "partner_id"),
            "account.move" to listOf("id", "name", "state", "amount_total", "currency_id", "invoice_origin"),
            "account.payment" to listOf("id", "name", "state", "amount", "currency_id", "reconciled_invoice_ids"),
        )

        private val READ_METHODS = setOf("search_read", "read", "search_count", "fields_get")

        private val CURRENCY_BY_SYMBOL = mapOf(
            "$" to "USD", "\u20AC" to "EUR", "\u00A3" to "GBP", "\u20AA" to "ILS",
        )

        private val CURRENCY_BY_NAME = mapOf(
            "usd" to "USD", "egp" to "EGP", "eur" to "EUR", "gbp" to "GBP", "sar" to "SAR", "aed" to "AED",
        )
    }
}
