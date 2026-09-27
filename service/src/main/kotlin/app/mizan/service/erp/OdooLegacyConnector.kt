package app.mizan.service.erp

import app.mizan.domain.model.Money
import app.mizan.domain.tool.Capability
import app.mizan.service.erp.OdooLegacyWire.Xml
import app.mizan.service.erp.OdooLegacyWire.Response
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The legacy adapter: read a pre-19 installation while it is being migrated.
 *
 * This connector exists for one reason. A customer runs Odoo 17, the app ships
 * with a JSON-2 connector, and the ERP cannot be upgraded the same week. So
 * the service can be pointed at the old installation to read customers, stock,
 * orders and amounts -- and it will not write to it.
 *
 * That refusal is the design decision worth stating plainly. XML-RPC forces a
 * multi-step operation (create the order, then the line, then the link) to be
 * several independent calls, each its own transaction, none of them atomic.
 * The JSON-2 connector avoided exactly that with a single server-side method.
 * Reintroducing it here so the app "also works on 17" would put a
 * half-applied sales order behind an approval that says it happened. A
 * migration is a read against the old system and a write against the new one,
 * and nothing else.
 *
 * The reads are honest about what they are: cached capability discovery, the
 * same refusal/unknown vocabulary as the JSON-2 connector, and a fault from
 * the ERP mapped to a reason code rather than to an empty list that would read
 * as "no such customer".
 */
class OdooLegacyConnector(
    private val registry: TenantErpRegistry,
    private val transport: HttpTransport = JdkHttpTransport(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val readTimeoutMillis: Long = 8_000,
    private val sessionTtlMillis: Long = 30 * 60 * 1000L,
) : ErpConnector {

    override val id: String = "odoo-legacy-xmlrpc-migration"

    private data class Session(val uid: Long, val login: String, val openedAtMillis: Long)

    private val sessions = LinkedHashMap<String, Session>()
    private val capabilityCache = LinkedHashMap<String, ErpCapabilities>()

    override fun capabilities(): ErpCapabilities {
        val binding = registry.bindings().firstOrNull()
            ?: return ErpCapabilities(
                connectorId = id,
                capabilities = emptySet(),
                models = emptyMap(),
                discoveredAtMillis = clock(),
                notes = listOf("no legacy ERP binding is configured for any tenant"),
            )
        val cached = capabilityCache[binding.tenantId]
        if (cached != null && clock() - cached.discoveredAtMillis < sessionTtlMillis) return cached
        val discovered = discover(binding)
        capabilityCache[binding.tenantId] = discovered
        return discovered
    }

    /**
     * Asks the legacy installation which models it exposes.
     *
     * Only the read capabilities are ever advertised: the connector reports
     * `LEGACY_RPC_TRANSPORT` and the read set, and the authority therefore
     * hides the write actions rather than offering buttons that would fail.
     */
    fun discover(binding: ErpBinding): ErpCapabilities {
        val models = listOf("sale.order", "account.move", "account.payment", "stock.quant", "res.partner")
        val present = LinkedHashMap<String, Boolean>()
        val notes = mutableListOf<String>()
        for (model in models) {
            when (val count = searchCount(binding, model, Xml.Struct(emptyMap()))) {
                is ErpResult.Ok -> present[model] = true
                is ErpResult.Refused -> {
                    present[model] = false
                    notes += "$model is not readable with this credential (${count.reasonCode})"
                }
                is ErpResult.Unavailable, is ErpResult.Unknown -> {
                    present[model] = false
                    notes += "$model could not be checked"
                }
                is ErpResult.NotSupported, is ErpResult.Malformed -> present[model] = false
            }
        }
        val capabilities = buildSet {
            add(Capability.LEGACY_RPC_TRANSPORT)
            add(Capability.STOCK_READ)
            add(Capability.CUSTOMER_SEARCH)
            add(Capability.ANALYTICS_SALES)
            if (present["res.partner"] == true) add(Capability.BATCH_READ)
            // Deliberately absent: every write capability and JSON2_TRANSPORT.
            // This connector reads a system that is on its way out.
        }
        return ErpCapabilities(
            connectorId = id,
            capabilities = capabilities,
            models = present,
            discoveredAtMillis = clock(),
            notes = notes + "legacy XML-RPC is read-only here: writes need the atomic JSON-2 path",
        )
    }

    override fun findCustomer(tenantId: String, query: String): ErpResult<List<ErpCustomer>> = guard {
        val binding = bindingOf(tenantId)
        val domain = listOf(
            Xml.Array(listOf(Xml.Str("name"), Xml.Str("ilike"), Xml.Str(query))),
        )
        val rows = searchRead(binding, "res.partner", domain, CUSTOMER_FIELDS, 10).orAbort()
        rows.mapNotNull { customerOf(it) }
    }

    override fun checkStock(tenantId: String, sku: String): ErpResult<ErpStock> = guard {
        val binding = bindingOf(tenantId)
        val domain = listOf(
            Xml.Array(listOf(Xml.Str("product_id.default_code"), Xml.Str("="), Xml.Str(sku))),
        )
        val rows = searchRead(binding, "stock.quant", domain, STOCK_FIELDS, 5).orAbort()
        val row = rows.firstOrNull() ?: abort(ErpResult.Refused("QUANT_NOT_FOUND", sku))
        val name = row["product_id"]?.many2One()?.second ?: sku
        ErpStock(
            sku = sku,
            name = name,
            availableQty = number(row["quantity"])?.toInt() ?: 0,
            reservedQty = number(row["reserved_quantity"])?.toInt() ?: 0,
            unitPriceMinor = null,
            currency = null,
            location = row["location_id"]?.many2One()?.second ?: "",
        )
    }

    override fun salesSummary(tenantId: String, period: String): ErpResult<String> = guard {
        val binding = bindingOf(tenantId)
        val domain = listOf(Xml.Array(listOf(Xml.Str("state"), Xml.Str("!="), Xml.Str("cancel"))))
        val count = searchCount(binding, "sale.order", Xml.Struct(mapOf("domain" to toXml(domain)))).orAbort()
        "legacy XML-RPC: $count sale orders in $period (read-only view)"
    }

    /**
     * Writes are not supported, and the answer says which capability is
     * missing rather than failing at the transport. The authority turns this
     * into a hidden action, not a failed one.
     */
    override fun createDraftOrder(
        tenantId: String,
        customerName: String,
        amount: Money,
        itemsSummary: String,
    ): ErpResult<ErpRecord> = ErpResult.NotSupported(Capability.SALES_ORDER_CREATE)

    override fun cancelOrder(tenantId: String, orderId: String, reason: String): ErpResult<ErpRecord> =
        ErpResult.NotSupported(Capability.SALES_ORDER_CANCEL)

    override fun createInvoice(tenantId: String, orderId: String): ErpResult<ErpRecord> =
        ErpResult.NotSupported(Capability.INVOICE_CREATE)

    override fun registerPayment(tenantId: String, invoiceId: String, amount: Money): ErpResult<ErpRecord> =
        ErpResult.NotSupported(Capability.PAYMENT_CREATE)

    override fun readBack(tenantId: String, model: String, recordId: String): ErpResult<ErpRecord> = guard {
        val binding = bindingOf(tenantId)
        val id = numericId(recordId, "recordId")
        val fields = VERIFY_FIELDS[model] ?: abort(ErpResult.NotSupported(Capability.READ_BACK_VERIFICATION))
        val rows = read(binding, model, listOf(id), fields).orAbort()
        val row = rows.firstOrNull() ?: abort(ErpResult.Refused("ERP_RECORD_NOT_FOUND", "$model/$recordId"))
        // A read-back through the legacy adapter is evidence about the old
        // system only. It is returned, and the note travels with it.
        val values = fields.filter { row[it] != null }.associateWith { field -> flatten(row[field]!!) }
        ErpRecord(
            model = model,
            recordId = recordId,
            fields = values,
            summary = "legacy:" + values.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" },
        )
    }

    override fun orderAmount(tenantId: String, orderId: String): ErpResult<Money> = guard {
        val binding = bindingOf(tenantId)
        val id = numericId(orderId, "orderId")
        val rows = read(binding, "sale.order", listOf(id), listOf("id", "amount_total", "currency_id")).orAbort()
        val row = rows.firstOrNull() ?: abort(ErpResult.Refused("ORDER_NOT_FOUND", orderId))
        val minor = minorUnits(row["amount_total"]) ?: abort(ErpResult.Malformed("ODOO_AMOUNT_MISSING", orderId))
        val currency = row["currency_id"]?.many2One()?.second
            ?: abort(ErpResult.Malformed("ODOO_CURRENCY_MISSING", orderId))
        Money(minor, currency.uppercase().take(3))
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
        val rows = searchRead(binding, model, emptyList(), fields, limit).valueOrNull() ?: return emptyList()
        return rows.mapNotNull { row ->
            val id = number(row["id"])?.toString() ?: return@mapNotNull null
            val values = fields.filter { row[it] != null }.associateWith { field -> flatten(row[field]!!) }
            ErpRecord(
                model = model,
                recordId = id,
                fields = values,
                summary = "legacy:" + values.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" },
            )
        }
    }

    override fun reachable(): Boolean {
        val binding = registry.bindings().firstOrNull() ?: return false
        return searchCount(binding, "res.partner", Xml.Struct(emptyMap())) is ErpResult.Ok
    }

    /** Forgets a cached login, so a rotated key is picked up on the next call. */
    fun invalidate(tenantId: String) {
        sessions.remove(tenantId)
        capabilityCache.remove(tenantId)
    }

    // ------------------------------------------------------------- internals

    private inline fun <T> guard(block: () -> T): ErpResult<T> = try {
        ErpResult.Ok(block())
    } catch (failure: LegacyFailure) {
        failure.result
    }

    private fun abort(result: ErpResult<Nothing>): Nothing = throw LegacyFailure(result)

    private fun <T> ErpResult<T>.orAbort(): T = when (this) {
        is ErpResult.Ok -> value
        is ErpResult.Refused -> throw LegacyFailure(ErpResult.Refused(reasonCode, detail))
        is ErpResult.NotSupported -> throw LegacyFailure(ErpResult.NotSupported(capability))
        is ErpResult.Unavailable -> throw LegacyFailure(ErpResult.Unavailable(reasonCode, retryAfterMillis))
        is ErpResult.Malformed -> throw LegacyFailure(ErpResult.Malformed(reasonCode, detail))
        is ErpResult.Unknown -> throw LegacyFailure(ErpResult.Unknown(reasonCode, detail))
    }

    private fun bindingOf(tenantId: String): ErpBinding =
        registry.binding(tenantId) ?: abort(ErpResult.Refused("ERP_BINDING_MISSING", "tenant $tenantId"))

    /**
     * Logs in once per tenant and keeps the uid for [sessionTtlMillis].
     *
     * XML-RPC has no session, so "logging in" is really validating the key and
     * remembering what uid it belongs to. A failed login is refused, never
     * cached, and never retried with a different credential.
     */
    private fun sessionFor(binding: ErpBinding): ErpResult<Session> {
        val cached = sessions[binding.tenantId]
        if (cached != null && clock() - cached.openedAtMillis < sessionTtlMillis) return ErpResult.Ok(cached)
        val login = binding.login
            ?: return ErpResult.Refused("ERP_LEGACY_LOGIN_MISSING", "a legacy binding needs the login that owns the key")
        val database = binding.database
            ?: return ErpResult.Refused("ERP_DATABASE_MISSING", "XML-RPC needs the database name in every call")
        val call = OdooLegacyWire.authenticateCall(database, login, binding.apiKey)
        return when (val outcome = transport.post(OdooLegacyWire.url(binding.baseUrl, OdooLegacyWire.PATH_COMMON), OdooLegacyWire.headers(), call, readTimeoutMillis)) {
            is TransportOutcome.NotSent -> ErpResult.Unavailable(outcome.reasonCode, null)
            is TransportOutcome.DispatchUnknown -> ErpResult.Unknown("ERP_DISPATCH_UNKNOWN", outcome.detail)
            is TransportOutcome.Received -> when (outcome.status) {
                in 200..299 -> when (val parsed = OdooLegacyWire.parse(outcome.body)) {
                    is Response.Fault -> ErpResult.Refused(
                        OdooLegacyWire.reasonForFault(parsed.code, parsed.message),
                        parsed.message,
                    )
                    is Response.Unreadable -> ErpResult.Malformed(parsed.reasonCode, "authenticate")
                    is Response.Value -> {
                        val uid = parsed.value.asLong()
                        // Odoo answers `false` for a bad login, and 0 is not a
                        // uid: both mean the credential was refused.
                        if (uid == null || uid <= 0L) {
                            ErpResult.Refused("ERP_AUTH_REJECTED", "the login or key was refused")
                        } else {
                            val opened = Session(uid = uid, login = login, openedAtMillis = clock())
                            sessions[binding.tenantId] = opened
                            ErpResult.Ok(opened)
                        }
                    }
                }
                in 400..499 -> ErpResult.Refused("ERP_AUTH_REJECTED", "http ${outcome.status}")
                in 500..599 -> ErpResult.Unavailable("ERP_UNAVAILABLE", outcome.retryAfterMillis)
                else -> ErpResult.Malformed("ERP_UNEXPECTED_STATUS", "http ${outcome.status}")
            }
        }
    }

    private fun searchRead(
        binding: ErpBinding,
        model: String,
        domain: List<Xml>,
        fields: List<String>,
        limit: Int,
    ): ErpResult<List<Map<String, Xml>>> = execute(
        binding = binding,
        model = model,
        method = "search_read",
        args = listOf(Xml.Array(domain)),
        kwargs = linkedMapOf(
            "fields" to Xml.Array(fields.map { Xml.Str(it) }),
            "limit" to Xml.Int(limit.toLong()),
        ),
    ).mapRows("$model.search_read")

    private fun read(
        binding: ErpBinding,
        model: String,
        ids: List<Long>,
        fields: List<String>,
    ): ErpResult<List<Map<String, Xml>>> = execute(
        binding = binding,
        model = model,
        method = "read",
        args = listOf(Xml.Array(ids.map { Xml.Int(it) })),
        kwargs = linkedMapOf("fields" to Xml.Array(fields.map { Xml.Str(it) })),
    ).mapRows("$model.read")

    private fun searchCount(binding: ErpBinding, model: String, kwargs: Xml.Struct): ErpResult<Long> =
        when (val executed = execute(binding, model, "search_count", emptyList(), kwargs.fields)) {
            is ErpResult.Ok -> {
                val count = executed.value.asLong()
                if (count == null) {
                    ErpResult.Malformed("ODOO_LEGACY_COUNT_UNREADABLE", "$model.search_count")
                } else {
                    ErpResult.Ok(count)
                }
            }
            is ErpResult.Refused -> executed
            is ErpResult.NotSupported -> executed
            is ErpResult.Unavailable -> executed
            is ErpResult.Malformed -> executed
            is ErpResult.Unknown -> executed
        }

    private fun execute(
        binding: ErpBinding,
        model: String,
        method: String,
        args: List<Xml>,
        kwargs: Map<String, Xml>,
    ): ErpResult<Xml> {
        val session = when (val opened = sessionFor(binding)) {
            is ErpResult.Ok -> opened.value
            is ErpResult.Refused -> return opened
            is ErpResult.NotSupported -> return opened
            is ErpResult.Unavailable -> return opened
            is ErpResult.Malformed -> return opened
            is ErpResult.Unknown -> return opened
        }
        val database = binding.database ?: return ErpResult.Refused("ERP_DATABASE_MISSING", "XML-RPC needs a database")
        val body = OdooLegacyWire.executeCall(
            database = database,
            uid = session.uid,
            apiKey = binding.apiKey,
            model = model,
            method = method,
            args = args,
            kwargs = kwargs,
        )
        val url = OdooLegacyWire.url(binding.baseUrl, OdooLegacyWire.PATH_OBJECT)
        return when (val outcome = transport.post(url, OdooLegacyWire.headers(), body, readTimeoutMillis)) {
            is TransportOutcome.NotSent -> ErpResult.Unavailable(outcome.reasonCode, null)
            // Every call here is a read, so an unanswered one proves nothing
            // happened: it is retryable and it is not a reconciliation case.
            // That is the one place this adapter differs from a write path,
            // where the same outcome would be Unknown and would block.
            is TransportOutcome.DispatchUnknown -> ErpResult.Unavailable("ERP_DISPATCH_UNKNOWN_READ", null)
            is TransportOutcome.Received -> when (outcome.status) {
                in 200..299 -> when (val parsed = OdooLegacyWire.parse(outcome.body)) {
                    is Response.Fault -> {
                        // An expired uid is a fault, and the session is
                        // dropped so the next call logs in again rather than
                        // returning the same refusal forever.
                        if (parsed.code == OdooLegacyWire.FAULT_AUTHENTICATION) sessions.remove(binding.tenantId)
                        ErpResult.Refused(OdooLegacyWire.reasonForFault(parsed.code, parsed.message), parsed.message)
                    }
                    is Response.Unreadable -> ErpResult.Malformed(parsed.reasonCode, "$model.$method")
                    is Response.Value -> ErpResult.Ok(parsed.value)
                }
                in 400..499 -> ErpResult.Refused("ERP_REJECTED", "http ${outcome.status}")
                in 500..599 -> ErpResult.Unavailable("ERP_UNAVAILABLE", outcome.retryAfterMillis)
                else -> ErpResult.Malformed("ERP_UNEXPECTED_STATUS", "http ${outcome.status}")
            }
        }
    }

    /**
     * An array of structs, or a drift failure.
     *
     * An empty answer is a real answer here: XML-RPC has no way to say
     * "nothing matched" other than an empty array, and reading a shape the
     * connector does not understand as "no such customer" is how a live
     * customer becomes an order that was never placed.
     */
    private fun ErpResult<Xml>.mapRows(what: String): ErpResult<List<Map<String, Xml>>> = when (this) {
        is ErpResult.Ok -> {
            val items = value.asArray() ?: return ErpResult.Malformed("ODOO_LEGACY_EXPECTED_ARRAY", what)
            val rows = ArrayList<Map<String, Xml>>(items.size)
            for (item in items) {
                val fields = item.asStruct() ?: return ErpResult.Malformed("ODOO_LEGACY_EXPECTED_STRUCT", what)
                rows += fields
            }
            ErpResult.Ok(rows)
        }
        is ErpResult.Refused -> this
        is ErpResult.NotSupported -> this
        is ErpResult.Unavailable -> this
        is ErpResult.Malformed -> this
        is ErpResult.Unknown -> this
    }

    private fun customerOf(row: Map<String, Xml>): ErpCustomer? {
        val id = number(row["id"])?.toString() ?: return null
        val name = row["name"]?.asString() ?: return null
        val active = row["active"]?.asBool() ?: true
        return ErpCustomer(
            recordId = id,
            name = name,
            creditMinor = minorUnits(row["credit_limit"]),
            balanceMinor = minorUnits(row["credit"]),
            currency = null,
            status = if (active) "active" else "blocked",
        )
    }

    private fun number(value: Xml?): Long? = when (value) {
        null -> null
        is Xml.Int -> value.value
        is Xml.Str -> value.value.trim().toBigDecimalOrNull()?.setScale(0, RoundingMode.DOWN)?.longValueExact()
        else -> null
    }

    /** Odoo stores money in major units; the service speaks minor units. */
    private fun minorUnits(value: Xml?): Long? = when (value) {
        null -> null
        is Xml.Int -> value.value * 100L
        is Xml.Str -> value.value.trim().toBigDecimalOrNull()
            ?.multiply(BigDecimal(100))?.setScale(0, RoundingMode.HALF_UP)?.longValueExact()
        is Xml.Bool -> null
        else -> null
    }

    private fun flatten(value: Xml): String = when (value) {
        is Xml.Str -> value.value
        is Xml.Int -> value.value.toString()
        is Xml.Bool -> value.value.toString()
        Xml.Nil -> ""
        is Xml.Array -> value.many2One()?.let { "${it.second} (#${it.first})" }
            ?: value.items.joinToString(",") { flatten(it) }
        is Xml.Struct -> value.fields.entries.joinToString(",") { "${it.key}=${flatten(it.value)}" }
    }

    private fun numericId(raw: String, what: String): Long =
        raw.trim().toLongOrNull() ?: abort(ErpResult.Refused("${what.uppercase()}_NOT_NUMERIC", raw))

    private fun toXml(domain: List<Xml>): Xml = Xml.Array(domain)

    companion object {
        val CUSTOMER_FIELDS = listOf("id", "name", "credit_limit", "credit", "active")
        val STOCK_FIELDS = listOf("id", "product_id", "quantity", "reserved_quantity", "location_id")

        val VERIFY_FIELDS: Map<String, List<String>> = mapOf(
            "sale.order" to listOf("id", "name", "state", "amount_total", "currency_id", "partner_id"),
            "account.move" to listOf("id", "name", "state", "amount_total", "currency_id", "invoice_origin"),
            "account.payment" to listOf("id", "name", "state", "amount", "currency_id", "reconciled_invoice_ids"),
        )
    }
}

private class LegacyFailure(val result: ErpResult<Nothing>) : RuntimeException(null, null, false, false)
