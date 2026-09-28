package app.mizan.service

import app.mizan.domain.model.Money
import app.mizan.domain.security.SecretMaterial
import app.mizan.service.erp.ErpBinding
import app.mizan.service.erp.ErpResult
import app.mizan.service.erp.HttpTransport
import app.mizan.service.erp.InMemoryTenantErpRegistry
import app.mizan.service.erp.OdooJson2Connector
import app.mizan.service.erp.TransportOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the connector does when the ERP changes shape underneath it.
 *
 * A live Odoo is not a schema you can pin. Fields get renamed by an upgrade,
 * customisations add columns, a module stores a float as a string, a `many2one`
 * arrives as a bare id when the display name is not readable, and a partner
 * record arrives with four hundred fields this connector has never heard of.
 *
 * The connector's contract is that it either reads what the ERP said or refuses
 * in a way the caller can act on. The failure this file exists to prevent is
 * the third option: reading a field that is not there as a zero. A stock level
 * of zero and a credit limit of zero are *claims about the world*, and a
 * connector that makes one up has written a fact into a proposal that the ERP
 * never stated -- which is worse than an error, because an error stops.
 */
class ErpSchemaDriftTest {

    private val tenant = "sim-alamal"
    private val key = SecretMaterial.of("a-very-secret-api-key")

    private class ScriptedTransport(
        private val responses: MutableList<() -> TransportOutcome> = ArrayList(),
    ) : HttpTransport {
        val calls = AtomicInteger(0)

        fun fixed(outcome: TransportOutcome) {
            responses += { outcome }
        }

        fun respondWith(outcome: () -> TransportOutcome) {
            responses += outcome
        }

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMillis: Long,
        ): TransportOutcome {
            val index = calls.getAndIncrement()
            val response = responses.getOrElse(index) { { TransportOutcome.Received(200, "[]") } }
            return response()
        }
    }

    private fun connector(transport: HttpTransport): OdooJson2Connector {
        val registry = InMemoryTenantErpRegistry(
            listOf(
                ErpBinding(
                    tenantId = tenant,
                    baseUrl = "https://erp.example.com",
                    database = "alamal",
                    apiKey = key,
                ),
            ),
        )
        return OdooJson2Connector(registry, transport, clock = { 1_790_000_000_000L })
    }

    private fun received(status: Int, body: String, retryAfterMillis: Long? = null) =
        TransportOutcome.Received(status, body, retryAfterMillis)

    // ------------------------------------------------------------ the payloads

    @Test
    fun aTruncatedBodyIsMalformedRatherThanAnEmptyAnswer() {
        // A connection that dies mid-response leaves a body that starts like
        // JSON. Reading it as "no customers" tells a salesperson the customer
        // does not exist, which is a different statement from "the ERP did not
        // answer".
        val transport = ScriptedTransport()
        transport.fixed(received(200, """[{"id":7,"name":"Acme Cor"""))
        val result = connector(transport).findCustomer(tenant, "Acme")
        assertTrue(
            "a truncated body must be malformed, not empty: $result",
            result is ErpResult.Malformed,
        )
    }

    @Test
    fun anObjectWhereAnArrayBelongsToMalformed() {
        val transport = ScriptedTransport()
        transport.fixed(received(200, """{"id":7,"name":"Acme Corp"}"""))
        val result = connector(transport).findCustomer(tenant, "Acme")
        assertTrue("an object is not a row set: $result", result is ErpResult.Malformed)
    }

    @Test
    fun aRowThatIsNotAnObjectIsMalformed() {
        val transport = ScriptedTransport()
        transport.fixed(received(200, """[["id",7]]"""))
        val result = connector(transport).findCustomer(tenant, "Acme")
        assertTrue("a row must be an object: $result", result is ErpResult.Malformed)
    }

    @Test
    fun trailingGarbageIsRejected() {
        val transport = ScriptedTransport()
        transport.fixed(received(200, """[{"id":7,"name":"Acme"}] and some prose"""))
        val result = connector(transport).findCustomer(tenant, "Acme")
        assertTrue("half a JSON document is not a document: $result", result is ErpResult.Malformed)
    }

    @Test
    fun aByteOrderMarkDoesNotMakeAValidAnswerUnreadable() {
        // Excel exports and proxies add one, and refusing the ERP's answer
        // because of it would be a self-inflicted outage.
        val transport = ScriptedTransport()
        transport.fixed(received(200, "\uFEFF" + """[{"id":7,"name":"Acme Corp","active":true}]"""))
        val result = connector(transport).findCustomer(tenant, "Acme")
        assertTrue("a BOM must not make valid JSON malformed: $result", result is ErpResult.Ok)
    }

    // ------------------------------------------------------------- field drift

    @Test
    fun aRenamedFieldIsMissingRatherThanZero() {
        // `credit` became `creditAmount`. The balance is unknown; zero would be
        // a claim that the customer owes nothing.
        val transport = ScriptedTransport()
        transport.fixed(
            received(
                200,
                """[{"id":7,"name":"Acme Corp","credit_limit":5000,"creditAmount":1200,"active":true}]""",
            ),
        )
        val result = connector(transport).findCustomer(tenant, "Acme")
        assertTrue("the read must still succeed: $result", result is ErpResult.Ok)
        val customer = (result as ErpResult.Ok).value.first()
        assertEquals("the renamed field is unknown, not zero", null, customer.balanceMinor)
        assertNotNull("the field that is still there is read", customer.creditMinor)
    }

    @Test
    fun anUnreadableAmountIsNotZero() {
        val transport = ScriptedTransport()
        transport.fixed(
            received(
                200,
                """[{"id":7,"name":"Acme Corp","credit_limit":"lots","credit":"","active":true}]""",
            ),
        )
        val result = connector(transport).findCustomer(tenant, "Acme")
        val customer = (result as ErpResult.Ok).value.first()
        assertNull("nonsense is not a limit", customer.creditMinor)
        assertNull("an empty string is not a balance", customer.balanceMinor)
    }

    @Test
    fun aNumberWrittenAsAStringIsReadExactlyOnce() {
        // Odoo sends money as a float and sometimes as a string, depending on
        // the field and the module. "5000.00" is 500000 minor units, not 500000
        // major ones and not 5000.
        val transport = ScriptedTransport()
        transport.fixed(
            received(
                200,
                """[{"id":7,"name":"Acme Corp","credit_limit":"5,000.00","credit":"1 200.50","active":"true"}]""",
            ),
        )
        val result = connector(transport).findCustomer(tenant, "Acme")
        val customer = (result as ErpResult.Ok).value.first()
        assertEquals(500_000L, customer.creditMinor)
        assertEquals("a space is not a thousands separator Odoo sends", null, customer.balanceMinor)
        assertEquals("a string true is still active", "active", customer.status)
    }

    @Test
    fun anUnknownFieldIsTolerated() {
        // Forward compatibility: an upgrade that adds columns must not break
        // the read. The connector reads what it knows and ignores the rest.
        val transport = ScriptedTransport()
        val extra = (1..500).joinToString(",") { """"custom_field_$it":"v$it"""" }
        transport.fixed(
            received(200, """[{"id":7,"name":"Acme Corp","credit_limit":5000,"active":true,$extra}]"""),
        )
        val result = connector(transport).findCustomer(tenant, "Acme")
        assertTrue("unknown fields must not break a read: $result", result is ErpResult.Ok)
        assertEquals(1, (result as ErpResult.Ok).value.size)
    }

    @Test
    fun aRecordWithoutAnIdOrNameIsDroppedRatherThanInvented() {
        val transport = ScriptedTransport()
        transport.fixed(
            received(
                200,
                """[{"name":"Acme Corp","active":true},{"id":8},{"id":9,"name":"Nile Ltd","active":true}]""",
            ),
        )
        val result = connector(transport).findCustomer(tenant, "Acme")
        val customers = (result as ErpResult.Ok).value
        assertEquals("only the complete row survives", 1, customers.size)
        assertEquals("Nile Ltd", customers.first().name)
    }

    @Test
    fun aBlockedPartnerIsBlockedEvenWhenTheFlagIsAString() {
        val transport = ScriptedTransport()
        transport.fixed(
            received(200, """[{"id":7,"name":"Acme Corp","active":"false","credit_limit":0}]"""),
        )
        val result = connector(transport).findCustomer(tenant, "Acme")
        val customer = (result as ErpResult.Ok).value.first()
        assertEquals("a string false blocks the partner", "blocked", customer.status)
        assertEquals("and a stated zero limit is a stated zero", 0L, customer.creditMinor)
    }

    @Test
    fun aMissingActiveFlagMeansActiveNotBlocked() {
        val transport = ScriptedTransport()
        transport.fixed(received(200, """[{"id":7,"name":"Acme Corp"}]"""))
        val result = connector(transport).findCustomer(tenant, "Acme")
        assertEquals("active", (result as ErpResult.Ok).value.first().status)
    }

    @Test
    fun unicodeInANameSurvivesTheRoundTrip() {
        val transport = ScriptedTransport()
        val name = "شركة الأمل للتجارة 🙂 وشركاه"
        transport.fixed(received(200, """[{"id":7,"name":"$name","active":true}]"""))
        val result = connector(transport).findCustomer(tenant, "الأمل")
        assertEquals(name, (result as ErpResult.Ok).value.first().name)
    }

    // -------------------------------------------------------------- transport

    @Test
    fun anExpiredSessionIsARefusalNotARetry() {
        // 401 is the ERP saying "this key is no longer good". Retrying it is a
        // loop; a fresh credential is the only fix, so it must not be reported
        // as something the outbox will try again.
        val transport = ScriptedTransport()
        transport.fixed(
            received(
                401,
                """{"error":{"code":401,"message":"Session expired","data":{"name":"odoo.exceptions.SessionExpiredException"}}}""",
            ),
        )
        val result = connector(transport).findCustomer(tenant, "Acme")
        assertTrue("an expired session is a refusal: $result", result is ErpResult.Refused)
        assertFalse(
            "and never something the retry queue will pick up",
            result is ErpResult.Unavailable,
        )
    }

    @Test
    fun aFiveHundredDuringAReadIsRetryableAndDuringAWriteIsNot() {
        val readTransport = ScriptedTransport()
        readTransport.fixed(received(503, """{"error":{"code":503,"message":"Service Unavailable"}}""", 5_000))
        assertTrue(
            "a read that failed changes nothing",
            connector(readTransport).findCustomer(tenant, "Acme") is ErpResult.Unavailable,
        )

        // A write first resolves its customer, so the fault is scripted for the
        // second call -- the one that creates the order.
        val writeTransport = ScriptedTransport()
        writeTransport.fixed(received(200, """[{"id":42,"name":"Acme Corp","active":true}]"""))
        writeTransport.fixed(received(503, """{"error":{"code":503,"message":"Service Unavailable"}}"""))
        val write = connector(writeTransport).createDraftOrder(
            tenant,
            "Acme Corp",
            Money(250_000, "USD"),
            "2 servers",
        )
        assertTrue("a write that may have landed is not a failure: $write", write is ErpResult.Unknown)
    }

    @Test
    fun aFailureWhileResolvingTheCustomerMeansTheWriteNeverLeft() {
        // The mirror of the case above: when the *read* that precedes a write
        // fails, nothing was created, so it is retryable rather than unknown.
        val transport = ScriptedTransport()
        transport.fixed(received(503, """{"error":{"code":503,"message":"Service Unavailable"}}""", 2_000))
        val result = connector(transport).createDraftOrder(tenant, "Acme Corp", Money(250_000, "USD"), "2 servers")
        assertTrue("no order was created, so this is retryable: $result", result is ErpResult.Unavailable)
        assertEquals("only the read was attempted", 1, transport.calls.get())
    }

    @Test
    fun aRateLimitOnAWriteIsUnknownAndOnAReadNamesTheWait() {
        val readTransport = ScriptedTransport()
        readTransport.fixed(received(429, """{"error":{"code":429,"message":"Too Many Requests"}}""", 12_000))
        val read = connector(readTransport).findCustomer(tenant, "Acme")
        assertTrue("a rate-limited read is retryable: $read", read is ErpResult.Unavailable)
        assertEquals(12_000L, (read as ErpResult.Unavailable).retryAfterMillis)

        val writeTransport = ScriptedTransport()
        writeTransport.fixed(received(200, """[{"id":42,"name":"Acme Corp","active":true}]"""))
        writeTransport.fixed(received(429, """{"error":{"code":429,"message":"Too Many Requests"}}"""))
        val write = connector(writeTransport).createDraftOrder(
            tenant,
            "Acme Corp",
            Money(250_000, "USD"),
            "2 servers",
        )
        assertTrue("a rate-limited write may have been applied: $write", write is ErpResult.Unknown)
    }

    @Test
    fun anEnormousAnswerIsReadWithoutCrashing() {
        val transport = ScriptedTransport()
        val rows = (1..2_000).joinToString(",") { """{"id":$it,"name":"Partner $it","active":true}""" }
        transport.fixed(received(200, "[$rows]"))
        val started = System.nanoTime()
        val result = connector(transport).findCustomer(tenant, "Partner")
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("2,000 rows must be read: $result", result is ErpResult.Ok)
        assertEquals(2_000, (result as ErpResult.Ok).value.size)
        assertTrue("and read quickly: $elapsedMillis ms", elapsedMillis < 5_000)
    }

    @Test
    fun anUnexpectedStatusIsMalformedRatherThanSuccess() {
        val transport = ScriptedTransport()
        transport.fixed(received(302, """{"error":{"code":302,"message":"Found"}}"""))
        val result = connector(transport).findCustomer(tenant, "Acme")
        assertTrue("a redirect is not an answer: $result", result is ErpResult.Malformed)
    }
}
