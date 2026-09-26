package app.mizan.service

import app.mizan.domain.tool.Capability
import app.mizan.domain.model.Money
import app.mizan.domain.security.SecretMaterial
import app.mizan.service.erp.ErpBinding
import app.mizan.service.erp.ErpResult
import app.mizan.service.erp.HttpTransport
import app.mizan.service.erp.InMemoryTenantErpRegistry
import app.mizan.service.erp.OdooJson2Connector
import app.mizan.service.erp.OdooWire
import app.mizan.service.erp.TransportOutcome
import app.mizan.service.json.Json
import app.mizan.service.json.asObject
import app.mizan.service.json.field
import app.mizan.service.json.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The ERP boundary is where this product either keeps a promise or breaks one.
 *
 * Every test here is about the difference between "the ERP said no" and "we do
 * not know what the ERP did". The first is safe to retry. The second must never
 * be retried, because the record may already exist, and a duplicate purchase
 * order is somebody's real money.
 *
 * The transport is scripted rather than mocked: it records exactly what the
 * connector sent, so the request shape is under test too, and it answers with
 * the bytes a real Odoo 19 would send.
 */
class OdooJson2ConnectorTest {

    private val tenant = "sim-alamal"
    private val key = SecretMaterial.of("a-very-secret-api-key")

    /** A transport that answers with whatever the next scripted response is. */
    private class ScriptedTransport(
        private val responses: MutableList<() -> TransportOutcome> = ArrayList(),
    ) : HttpTransport {
        val calls = ArrayList<Call>()
        private val counter = AtomicInteger(0)

        data class Call(val url: String, val headers: Map<String, String>, val body: String, val timeoutMillis: Long)

        fun respondWith(response: () -> TransportOutcome) {
            responses += response
        }

        fun fixed(outcome: TransportOutcome) {
            responses += { outcome }
        }

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMillis: Long,
        ): TransportOutcome {
            calls += Call(url, headers, body, timeoutMillis)
            val index = counter.getAndIncrement()
            val response = responses.getOrElse(index) { { TransportOutcome.Received(200, "[]") } }
            return response()
        }
    }

    private fun connector(
        transport: HttpTransport,
        database: String? = "alamal",
        readOnly: Boolean = false,
    ): OdooJson2Connector {
        val registry = InMemoryTenantErpRegistry(
            listOf(
                ErpBinding(
                    tenantId = tenant,
                    baseUrl = "https://erp.example.com",
                    database = database,
                    apiKey = key,
                    readOnly = readOnly,
                ),
            ),
        )
        return OdooJson2Connector(registry, transport, clock = { 1_790_000_000_000L })
    }

    private fun received(status: Int, body: String, retryAfterMillis: Long? = null) =
        TransportOutcome.Received(status, body, retryAfterMillis)

    // ------------------------------------------------------------- the request

    @Test
    fun aReadIsOneJson2PostWithABearerKeyAndTheDatabaseHeader() {
        val transport = ScriptedTransport()
        transport.fixed(received(200, """[{"id":7,"name":"Acme Corp","credit_limit":5000,"credit":1200,"active":true}]"""))
        val customers = connector(transport).findCustomer(tenant, "Acme")
        assertTrue(customers is ErpResult.Ok)
        val call = transport.calls.single()
        assertEquals("https://erp.example.com/json/2/res.partner/search_read", call.url)
        assertEquals("bearer a-very-secret-api-key", call.headers[OdooWire.HEADER_AUTHORIZATION])
        assertEquals("alamal", call.headers[OdooWire.HEADER_DATABASE])
        assertEquals("application/json", call.headers["Content-Type"])
        val body = Json.parseOrNull(call.body)?.asObject()
        assertNotNull(body)
        assertNotNull(body!!.field("domain"))
    }

    @Test
    fun theDatabaseHeaderIsOmittedWhenTheBindingHasNoDatabase() {
        val transport = ScriptedTransport()
        transport.fixed(received(200, "[]"))
        connector(transport, database = null).findCustomer(tenant, "Acme")
        assertNull(transport.calls.single().headers[OdooWire.HEADER_DATABASE])
    }

    @Test
    fun moneyFromTheErpIsConvertedToMinorUnitsExactlyOnce() {
        val transport = ScriptedTransport()
        transport.fixed(
            received(
                200,
                """[{"id":7,"name":"Acme Corp","credit_limit":5000.25,"credit":"1200.10","active":true}]""",
            ),
        )
        val result = connector(transport).findCustomer(tenant, "Acme") as ErpResult.Ok
        assertEquals(500_025L, result.value.first().creditMinor)
        assertEquals(120_010L, result.value.first().balanceMinor)
    }

    @Test
    fun anInactivePartnerIsReportedAsBlocked() {
        val transport = ScriptedTransport()
        transport.fixed(received(200, """[{"id":7,"name":"Nile Industrial","active":false}]"""))
        val result = connector(transport).findCustomer(tenant, "Nile") as ErpResult.Ok
        assertEquals("blocked", result.value.first().status)
    }

    @Test
    fun aWriteIsOneErpCallNotThree() {
        // Partner lookup is a read; the order itself is a single create.
        val transport = ScriptedTransport()
        transport.fixed(received(200, """[{"id":42,"name":"Acme Corp","active":true}]"""))
        transport.fixed(received(200, """{"id":9001}"""))
        val result = connector(transport)
            .createDraftOrder(tenant, "Acme Corp", Money(250_000, "USD"), "10 laptops")
        assertTrue(result is ErpResult.Ok)
        assertEquals(2, transport.calls.size)
        assertEquals("https://erp.example.com/json/2/sale.order/wakeel_create_draft_order", transport.calls[1].url)
        val body = Json.parseOrNull(transport.calls[1].body)?.asObject()
        assertNotNull(body)
        assertEquals(42L, body!!.field("partner_id")?.let { Json.write(it).toLong() })
        assertEquals("9001", (result as ErpResult.Ok).value.recordId)
        assertEquals("sale.order", result.value.model)
    }

    @Test
    fun anAmbiguousCustomerNameIsRefusedRatherThanGuessed() {
        val transport = ScriptedTransport()
        transport.fixed(
            received(
                200,
                """[{"id":1,"name":"Acme Corp"},{"id":2,"name":"Acme Corp Holdings"}]""",
            ),
        )
        val result = connector(transport).createDraftOrder(tenant, "Acme", Money(1_000, "USD"), "1 box")
        // "Acme" matches nothing exactly and two rows partially: not a choice
        // this code is allowed to make.
        assertTrue(result is ErpResult.Refused)
        assertEquals("CUSTOMER_NOT_FOUND", (result as ErpResult.Refused).reasonCode)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun twoExactCustomerMatchesAreRefusedAsAmbiguous() {
        val transport = ScriptedTransport()
        transport.fixed(received(200, """[{"id":1,"name":"Acme Corp"},{"id":2,"name":"Acme Corp"}]"""))
        val result = connector(transport).createDraftOrder(tenant, "Acme Corp", Money(1_000, "USD"), "1 box")
        assertEquals("CUSTOMER_AMBIGUOUS", (result as ErpResult.Refused).reasonCode)
    }

    // ------------------------------------------------- statuses, honestly read

    @Test
    fun anInvalidKeyIsARefusalNotAnAmbiguity() {
        val transport = ScriptedTransport()
        transport.fixed(received(401, """{"error":{"message":"Invalid apikey"}}"""))
        val result = connector(transport).findCustomer(tenant, "Acme")
        assertEquals("ERP_AUTH_REJECTED", (result as ErpResult.Refused).reasonCode)
    }

    @Test
    fun aForbiddenOperationNamesThePermissionProblem() {
        val transport = ScriptedTransport()
        transport.fixed(received(403, """{"error":{"message":"Access Denied"}}"""))
        val result = connector(transport).createDraftOrder(tenant, "Acme", Money(1_000, "USD"), "1")
        assertEquals("ERP_ACCESS_DENIED", (result as ErpResult.Refused).reasonCode)
    }

    @Test
    fun aMissingModelOrMethodIsNamedFromTheErpError() {
        val transport = ScriptedTransport()
        transport.fixed(received(404, """{"error":{"data":{"name":"werkzeug.exceptions.NotFound"}}}"""))
        val result = connector(transport).findCustomer(tenant, "Acme")
        val refused = result as ErpResult.Refused
        assertEquals("ERP_MODEL_OR_METHOD_MISSING", refused.reasonCode)
        assertTrue(refused.detail.contains("NotFound"))
    }

    @Test
    fun aBusinessRejectionIsARefusalWithTheErpReason() {
        val transport = ScriptedTransport()
        transport.fixed(received(400, """{"error":{"data":{"message":"The order is already confirmed"}}}"""))
        val result = connector(transport).cancelOrder(tenant, "9001", "customer changed their mind")
        assertTrue(result is ErpResult.Refused)
        assertEquals("ERP_REJECTED", (result as ErpResult.Refused).reasonCode)
    }

    // ---------------------------------------------- the write/read distinction

    @Test
    fun aServerErrorDuringAWriteIsUnknownNeverAFailure() {
        val transport = ScriptedTransport()
        transport.fixed(received(200, """[{"id":42,"name":"Acme Corp","active":true}]"""))
        transport.fixed(received(503, """{"error":{"message":"service unavailable"}}"""))
        val result = connector(transport).createDraftOrder(tenant, "Acme Corp", Money(1_000, "USD"), "1 box")
        assertTrue("a dispatched write with no answer is unknown", result is ErpResult.Unknown)
        assertEquals("ERP_FAILED_DURING_WRITE", (result as ErpResult.Unknown).reasonCode)
    }

    @Test
    fun aServerErrorDuringAReadIsRetryable() {
        val transport = ScriptedTransport()
        transport.fixed(received(503, "{}", retryAfterMillis = 5_000L))
        val result = connector(transport).checkStock(tenant, "SKU-DESK-01")
        assertTrue(result is ErpResult.Unavailable)
        assertEquals(5_000L, (result as ErpResult.Unavailable).retryAfterMillis)
    }

    @Test
    fun beingRateLimitedDuringAWriteIsUnknown() {
        // A 429 means the request arrived and was refused *after* the ERP had
        // it. Retrying it is exactly the mistake this rule prevents.
        val transport = ScriptedTransport()
        transport.fixed(received(200, """[{"id":42,"name":"Acme Corp","active":true}]"""))
        transport.fixed(received(429, "{}", retryAfterMillis = 30_000L))
        val result = connector(transport).createDraftOrder(tenant, "Acme Corp", Money(1_000, "USD"), "1 box")
        assertEquals("ERP_RATE_LIMITED_DURING_WRITE", (result as ErpResult.Unknown).reasonCode)
    }

    @Test
    fun beingRateLimitedDuringAReadNamesHowLongToWait() {
        val transport = ScriptedTransport()
        transport.fixed(received(429, "{}", retryAfterMillis = 20_000L))
        val result = connector(transport).findCustomer(tenant, "Acme")
        assertEquals(20_000L, (result as ErpResult.Unavailable).retryAfterMillis)
        assertEquals("ERP_RATE_LIMITED", result.reasonCode)
    }

    @Test
    fun aRequestThatNeverLeftIsSafeToRetry() {
        val transport = ScriptedTransport()
        transport.fixed(TransportOutcome.NotSent("ERP_UNREACHABLE", "connection refused"))
        val result = connector(transport).createDraftOrder(tenant, "Acme Corp", Money(1_000, "USD"), "1 box")
        assertTrue("nothing was written", result is ErpResult.Unavailable)
        assertEquals("ERP_UNREACHABLE", (result as ErpResult.Unavailable).reasonCode)
    }

    @Test
    fun aRequestWhoseAnswerWasLostIsUnknown() {
        val transport = ScriptedTransport()
        transport.fixed(TransportOutcome.DispatchUnknown("ERP_TIMEOUT", "read timed out"))
        val result = connector(transport).registerPayment(tenant, "5001", Money(5_000, "USD"))
        assertTrue(result is ErpResult.Unknown)
        assertEquals("ERP_DISPATCH_UNKNOWN", (result as ErpResult.Unknown).reasonCode)
    }

    @Test
    fun aReadRefusesToWearTheWritesCoat() {
        // `search_read` must be judged as a read even when it is the only call
        // in a wrapper: a 503 on it is retryable, not ambiguous.
        val transport = ScriptedTransport()
        transport.fixed(received(500, "{}"))
        val result = connector(transport).readBack(tenant, "sale.order", "9001")
        assertTrue(result is ErpResult.Unavailable)
    }

    // --------------------------------------------------------------- read back

    @Test
    fun aReadBackReturnsTheFieldsAVerificationCompares() {
        val transport = ScriptedTransport()
        transport.fixed(
            received(
                200,
                """[{"id":9001,"name":"SO9001","state":"draft","amount_total":2500.0,""" +
                    """"currency_id":[1,"USD"],"partner_id":[42,"Acme Corp"]}]""",
            ),
        )
        val result = connector(transport).readBack(tenant, "sale.order", "9001") as ErpResult.Ok
        assertEquals("9001", result.value.recordId)
        assertEquals("draft", result.value.fields["state"])
        assertEquals("2500.0", result.value.fields["amount_total"])
        assertEquals("USD", result.value.fields["currency_id"])
        assertEquals("Acme Corp", result.value.fields["partner_id"])
        assertEquals("https://erp.example.com/json/2/sale.order/read", transport.calls.single().url)
    }

    @Test
    fun aReadBackOfAMissingRecordIsARefusal() {
        val transport = ScriptedTransport()
        transport.fixed(received(200, "[]"))
        val result = connector(transport).readBack(tenant, "sale.order", "9001")
        assertEquals("ERP_RECORD_NOT_FOUND", (result as ErpResult.Refused).reasonCode)
    }

    @Test
    fun aModelWithNoVerificationFieldsCannotBeReadBack() {
        val transport = ScriptedTransport()
        val result = connector(transport).readBack(tenant, "ir.model", "1")
        assertTrue(result is ErpResult.NotSupported)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun aNonNumericRecordIdIsRefusedBeforeAnyCall() {
        val transport = ScriptedTransport()
        val result = connector(transport).readBack(tenant, "sale.order", "SO-9001")
        assertEquals("RECORDID_NOT_NUMERIC", (result as ErpResult.Refused).reasonCode)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun anOrderAmountComesBackWithItsCurrency() {
        val transport = ScriptedTransport()
        transport.fixed(received(200, """[{"id":9001,"amount_total":2500.5,"currency_id":[1,"$"]}]"""))
        val result = connector(transport).orderAmount(tenant, "9001") as ErpResult.Ok
        assertEquals(Money(250_050, "USD"), result.value)
    }

    @Test
    fun anOrderWithoutAnAmountIsMalformedNotZero() {
        val transport = ScriptedTransport()
        transport.fixed(received(200, """[{"id":9001,"currency_id":[1,"USD"]}]"""))
        val result = connector(transport).orderAmount(tenant, "9001")
        assertEquals("ODOO_AMOUNT_MISSING", (result as ErpResult.Malformed).reasonCode)
    }

    @Test
    fun nonJsonIsMalformedRatherThanAnEmptyAnswer() {
        val transport = ScriptedTransport()
        transport.fixed(received(200, "<html>proxy error</html>"))
        val result = connector(transport).findCustomer(tenant, "Acme")
        assertEquals("ODOO_NOT_JSON", (result as ErpResult.Malformed).reasonCode)
    }

    @Test
    fun aCreateWithoutAnIdIsMalformed() {
        val transport = ScriptedTransport()
        transport.fixed(received(200, """[{"id":42,"name":"Acme Corp","active":true}]"""))
        transport.fixed(received(200, """{"result":true}"""))
        val result = connector(transport).createDraftOrder(tenant, "Acme Corp", Money(1_000, "USD"), "1")
        assertEquals("ODOO_CREATE_WITHOUT_ID", (result as ErpResult.Malformed).reasonCode)
    }

    // --------------------------------------------------------- binding rules

    @Test
    fun aBindingOverPlainHttpIsRefusedUnlessItIsLoopback() {
        assertThrows(IllegalArgumentException::class.java) {
            ErpBinding("t", "http://erp.example.com", null, key)
        }
        // Loopback is allowed so the connector can be tested against a local
        // server; the Android client still refuses to send a write anywhere
        // but https.
        assertNotNull(ErpBinding("t", "http://127.0.0.1:8069", null, key))
    }

    @Test
    fun aBindingNeverPrintsItsKey() {
        val binding = ErpBinding(tenant, "https://erp.example.com", "alamal", key)
        assertFalse(binding.toString().contains("a-very-secret-api-key"))
        assertTrue(binding.toString().contains("redacted"))
        assertFalse(key.toString().contains("a-very-secret-api-key"))
    }

    @Test
    fun aModelNameThatIsNotAModelNameNeverBecomesAUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            OdooWire.url("https://erp.example.com", "../../admin", "search_read")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OdooWire.url("https://erp.example.com", "sale.order", "read;drop")
        }
    }

    @Test
    fun aReadOnlyBindingRefusesEveryWrite() {
        val transport = ScriptedTransport()
        val readOnly = connector(transport, readOnly = true)
        val write = readOnly.createDraftOrder(tenant, "Acme Corp", Money(1_000, "USD"), "1 box")
        assertEquals("ERP_BINDING_READ_ONLY", (write as ErpResult.Refused).reasonCode)
        assertTrue("nothing must reach the ERP", transport.calls.isEmpty())
    }

    @Test
    fun aTenantWithoutABindingIsRefusedByEveryOperation() {
        val transport = ScriptedTransport()
        val other = OdooJson2Connector(InMemoryTenantErpRegistry(emptyList()), transport)
        assertEquals("ERP_BINDING_MISSING", (other.findCustomer("nobody", "x") as ErpResult.Refused).reasonCode)
        assertEquals("ERP_BINDING_MISSING", (other.reachable()).let { if (it) "" else "ERP_BINDING_MISSING" })
    }

    // ----------------------------------------------------------- capabilities

    @Test
    fun capabilitiesAreDiscoveredFromTheErpNotAssumedFromAUrl() {
        val transport = ScriptedTransport()
        // sale.order and account.move answer; the rest do not resolve.
        transport.fixed(received(200, "3"))
        transport.fixed(received(200, "2"))
        transport.fixed(received(404, """{"error":{"data":{"name":"NotFound"}}}"""))
        transport.fixed(received(404, """{"error":{"data":{"name":"NotFound"}}}"""))
        transport.fixed(received(404, """{"error":{"data":{"name":"NotFound"}}}"""))
        val connector = connector(transport)
        val capabilities = connector.capabilities()
        assertTrue(capabilities.capabilities.contains(Capability.JSON2_TRANSPORT))
        assertTrue(capabilities.capabilities.contains(Capability.SALES_ORDER_CREATE))
        assertTrue(capabilities.capabilities.contains(Capability.INVOICE_CREATE))
        assertFalse(capabilities.capabilities.contains(Capability.PAYMENT_CREATE))
        assertEquals(true, capabilities.models["sale.order"])
        assertEquals(false, capabilities.models["account.payment"])
        assertTrue(capabilities.notes.any { it.contains("XML-RPC") })
    }

    @Test
    fun aCapabilityAnswerIsCachedForItsWindow() {
        var now = 1_790_000_000_000L
        val transport = ScriptedTransport()
        repeat(5) { transport.fixed(received(200, "1")) }
        val registry = InMemoryTenantErpRegistry(
            listOf(ErpBinding(tenant, "https://erp.example.com", "alamal", key)),
        )
        val connector = OdooJson2Connector(registry, transport, clock = { now })
        connector.capabilities()
        val afterFirst = transport.calls.size
        now += 60_000L
        connector.capabilities()
        assertEquals("a second call inside the window must not hit the ERP", afterFirst, transport.calls.size)
        now += 6 * 60 * 1000L
        connector.capabilities()
        assertTrue(transport.calls.size > afterFirst)
    }

    @Test
    fun reachabilityIsAFactAboutTheErpNotAHope() {
        val transport = ScriptedTransport()
        transport.fixed(received(200, "12"))
        assertTrue(connector(transport).reachable())
        val dead = ScriptedTransport()
        dead.fixed(TransportOutcome.NotSent("ERP_UNREACHABLE", "refused"))
        assertFalse(connector(dead).reachable())
    }

    @Test
    fun recentRecordsReadsTheNewestRowsForAReconciliation() {
        val transport = ScriptedTransport()
        transport.fixed(
            received(
                200,
                """[{"id":9002,"name":"SO9002","state":"draft","amount_total":10,"currency_id":[1,"USD"],"partner_id":[42,"Acme"]}]""",
            ),
        )
        val records = connector(transport).recentRecords(tenant, "sale.order", 5)
        assertEquals(1, records.size)
        assertEquals("9002", records.first().recordId)
    }

    @Test
    fun aCustomerStateUsesTheExactNameWhenItExists() {
        val transport = ScriptedTransport()
        transport.fixed(
            received(
                200,
                """[{"id":1,"name":"Acme Corp","credit_limit":100,"credit":10,"active":true},""" +
                    """{"id":2,"name":"Acme Corp Holdings","credit_limit":50,"credit":5,"active":true}]""",
            ),
        )
        val state = connector(transport).customerState(tenant, "Acme Corp")
        assertNotNull(state)
        assertEquals("1", state!!.recordId)
    }

    @Test
    fun theConnectorNamesItselfSoAnOperatorKnowsWhatWroteARecord() {
        val transport = ScriptedTransport()
        assertEquals("odoo-19-json2", connector(transport).id)
        assertEquals("Wakeel/2.0", transport.let { "Wakeel/2.0" })
    }
}
