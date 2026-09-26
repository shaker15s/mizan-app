package app.mizan.service

import app.mizan.service.json.Json
import app.mizan.service.json.asLong
import app.mizan.service.json.asObject
import app.mizan.service.json.field
import app.mizan.service.json.flag
import app.mizan.service.json.text
import app.mizan.service.protocol.MizanContract
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * End-to-end tests against a real listener on an ephemeral port.
 *
 * These are the tests the previous engineering report could not write: no
 * service existed. They prove the contract the Android client already
 * assumes: a token, a decision, a read-back, and an idempotent replay.
 *
 * Approval levels decide who may approve what, so the amounts are chosen on
 * purpose against the demo USD ladder (L1 up to 1,000.00, L2 up to 10,000.00):
 * a small write is confirmed by the initiator, a larger one needs a
 * privileged approver who is not the initiator.
 */
class MizanServiceHttpTest {

    data class Reply(val status: Int, val body: String) {
        fun field(name: String): String? = Json.parseOrNull(body)?.asObject()?.text(name)
        fun number(name: String): Long? = Json.parseOrNull(body)?.asObject()?.field(name)?.asLong()
        fun isStatus(value: String): Boolean = field("status") == value
    }

    companion object {
        private lateinit var service: MizanService
        private lateinit var base: String
        private lateinit var client: HttpClient

        @JvmStatic
        @BeforeClass
        fun start() {
            service = MizanService()
            val port = service.start(port = 0)
            base = "http://127.0.0.1:$port"
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        }

        @JvmStatic
        @AfterClass
        fun stop() {
            service.stop()
        }

        fun send(
            path: String,
            method: String,
            body: String? = null,
            token: String? = null,
            headers: Map<String, String> = emptyMap(),
        ): Reply {
            val builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
            headers.forEach { (name, value) -> builder.header(name, value) }
            if (token != null) builder.header(MizanContract.HEADER_AUTHORIZATION, "Bearer $token")
            val publisher = if (body == null) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                HttpRequest.BodyPublishers.ofString(body)
            }
            builder.method(method, publisher)
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            return Reply(response.statusCode(), response.body())
        }

        fun signIn(email: String, password: String): String? =
            send(
                MizanContract.PATH_SESSIONS,
                "POST",
                """{"email":"$email","password":"$password"}""",
            ).field("token")

        fun draftOrderBody(
            customer: String,
            amountMinor: Long,
            currency: String,
            items: String,
            approverId: String = "USR-MGR",
        ): String =
            """{"tool":"sales.order.create_draft","toolVersion":"2.1.0","tenantId":"sim-alamal",""" +
                """"proposalId":"PRP-TEST","executionId":"EXE-TEST",""" +
                """"approverId":"$approverId","idempotencyKey":null,""" +
                """"arguments":{"amountMinor":"$amountMinor","currency":"$currency",""" +
                """"customerName":"$customer","itemsSummary":"$items"}}"""
    }

    private val repToken: String
        get() = signIn("rep@mizan.test", "rep-demo-password")
            ?: error("rep sign-in must succeed in the reference service")

    private val managerToken: String
        get() = signIn("manager@mizan.test", "manager-demo-password")
            ?: error("manager sign-in must succeed in the reference service")

    @Test
    fun signInIssuesTokenAndIdentity() {
        val reply = send(
            MizanContract.PATH_SESSIONS,
            "POST",
            """{"email":"rep@mizan.test","password":"rep-demo-password"}""",
        )
        assertEquals(200, reply.status)
        assertEquals("USR-REP", reply.field("actorId"))
        assertEquals("SALES_REP", reply.field("role"))
        assertEquals("sim-alamal", reply.field("tenantId"))
        assertNotNull(reply.field("token"))
        assertNotNull(reply.number("expiresAtEpochMillis"))
    }

    @Test
    fun wrongPasswordIsRefusedAndThenThrottled() {
        // The finance account is only used here, so the lockout cannot
        // interfere with the other tests that share this service instance.
        val first = send(
            MizanContract.PATH_SESSIONS,
            "POST",
            """{"email":"finance@mizan.test","password":"wrong"}""",
        )
        assertEquals(401, first.status)
        repeat(10) {
            send(
                MizanContract.PATH_SESSIONS,
                "POST",
                """{"email":"finance@mizan.test","password":"wrong"}""",
            )
        }
        // A locked account answers exactly like a wrong password, by design.
        val after = send(
            MizanContract.PATH_SESSIONS,
            "POST",
            """{"email":"finance@mizan.test","password":"finance-demo-password"}""",
        )
        assertEquals(401, after.status)
    }

    @Test
    fun executionWithoutTokenIs401() {
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody("Acme Corp", 250_000L, "USD", "10 laptops"),
        )
        assertEquals(401, reply.status)
        assertEquals("SESSION_EXPIRED", reply.field("messageCode"))
    }

    @Test
    fun draftOrderIsVerifiedByReadBack() {
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody("Acme Corp", 250_000L, "USD", "10 laptops"),
            token = repToken,
        )
        assertEquals(200, reply.status)
        assertTrue(reply.isStatus(MizanContract.Status.VERIFIED))
        assertTrue(reply.field("erpRecordId")?.startsWith("SO-") == true)
        assertEquals(MizanContract.ErpModel.SALE_ORDER, reply.field("erpModel"))
        assertEquals("READ_BACK", reply.field("verification"))
    }

    @Test
    fun sameIdempotencyKeyReplaysWithoutCreatingASecondRecord() {
        val key = "key-replay-" + System.nanoTime()
        val body = draftOrderBody("Acme Corp", 250_000L, "USD", "4 chairs")
        val headers = mapOf(MizanContract.HEADER_IDEMPOTENCY_KEY to key)
        val first = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = repToken, headers = headers)
        val second = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = repToken, headers = headers)
        assertTrue(first.isStatus(MizanContract.Status.VERIFIED))
        assertTrue(second.isStatus(MizanContract.Status.VERIFIED))
        assertEquals(first.field("erpRecordId"), second.field("erpRecordId"))
    }

    @Test
    fun sameKeyWithDifferentArgumentsIsRefused() {
        val key = "key-reuse-" + System.nanoTime()
        val headers = mapOf(MizanContract.HEADER_IDEMPOTENCY_KEY to key)
        val first = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody("Acme Corp", 250_000L, "USD", "2 desks"),
            token = repToken,
            headers = headers,
        )
        val second = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody("Acme Corp", 255_000L, "USD", "2 desks"),
            token = repToken,
            headers = headers,
        )
        assertTrue(first.isStatus(MizanContract.Status.VERIFIED))
        assertEquals(409, second.status)
        assertEquals("IDEMPOTENCY_KEY_REUSE", second.field("messageCode"))
    }

    @Test
    fun initiatorCannotApproveItsOwnPrivilegedWrite() {
        val body = draftOrderBody("Acme Corp", 250_000L, "USD", "10 laptops", approverId = "USR-REP")
        val reply = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = repToken)
        assertEquals(422, reply.status)
        assertEquals("SOD_SAME_ACTOR", reply.field("messageCode"))
    }

    @Test
    fun auditorCannotWrite() {
        val token = signIn("auditor@mizan.test", "auditor-demo-password")
        assertNotNull(token)
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody("Acme Corp", 250_000L, "USD", "10 laptops"),
            token = token,
        )
        assertEquals(422, reply.status)
        assertEquals("AUDITOR_READONLY", reply.field("messageCode"))
    }

    @Test
    fun unknownToolIsRefusedNotGuessed() {
        val body = """{"tool":"nope","toolVersion":"1.0.0","tenantId":"sim-alamal","arguments":{}}"""
        val reply = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = repToken)
        assertEquals(422, reply.status)
        assertEquals("TOOL_UNKNOWN", reply.field("messageCode"))
    }

    @Test
    fun anotherTenantIsRefused() {
        val body = draftOrderBody("Acme Corp", 250_000L, "USD", "10 laptops").replace("sim-alamal", "other-tenant")
        val reply = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = repToken)
        assertEquals(403, reply.status)
        assertEquals("TENANT_MISMATCH", reply.field("messageCode"))
    }

    @Test
    fun uncertainWriteReportsAmbiguousAndKeepsTheRecord() {
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody("Acme Corp", 250_000L, "USD", "1 desk"),
            token = repToken,
            headers = mapOf(MizanContract.HEADER_SIMULATE to MizanContract.SIMULATE_AMBIGUOUS),
        )
        assertEquals(200, reply.status)
        assertTrue(reply.isStatus(MizanContract.Status.AMBIGUOUS))
        assertFalse(reply.field("candidates").isNullOrBlank())
        // The write did land. That is exactly why the answer is ambiguous and
        // why the client opens reconciliation instead of retrying.
        val erp = send(MizanContract.PATH_ERP, "GET", token = repToken)
        assertTrue(erp.body.contains("SO-"))
    }

    @Test
    fun paymentForAMissingInvoiceFailsHonestly() {
        val body = """{"tool":"payment.register","toolVersion":"1.1.0","tenantId":"sim-alamal",""" +
            """"approverId":"USR-REP","arguments":{"invoiceId":"INV-999999","amountMinor":"5000","currency":"USD"}}"""
        val reply = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = repToken)
        assertEquals(200, reply.status)
        assertTrue(reply.isStatus(MizanContract.Status.FAILED))
        assertEquals("INVOICE_NOT_FOUND_OR_CURRENCY_MISMATCH", reply.field("messageCode"))
    }

    @Test
    fun invoiceThenPaymentIsVerified() {
        val order = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody("Acme Corp", 250_000L, "USD", "1 chair"),
            token = repToken,
        )
        val orderId = order.field("erpRecordId") ?: error("order must be created")
        val invoice = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            """{"tool":"invoice.create_from_order","toolVersion":"1.0.0","tenantId":"sim-alamal",""" +
                // The invoice carries no amount of its own: the authority judges
                // it by the amount of the order it bills. 2,500.00 USD is an L2
                // write, so it needs a privileged approver who is not the
                // initiator.
                """"approverId":"USR-FIN","arguments":{"orderId":"$orderId"}}""",
            token = repToken,
        )
        assertTrue(invoice.isStatus(MizanContract.Status.VERIFIED))
        val invoiceId = invoice.field("erpRecordId") ?: error("invoice must be created")
        val payment = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            """{"tool":"payment.register","toolVersion":"1.1.0","tenantId":"sim-alamal",""" +
                """"approverId":"USR-REP","arguments":{"invoiceId":"$invoiceId","amountMinor":"7000","currency":"USD"}}""",
            token = repToken,
        )
        assertTrue(payment.isStatus(MizanContract.Status.VERIFIED))
        assertEquals(MizanContract.ErpModel.PAYMENT, payment.field("erpModel"))
    }

    @Test
    fun cancellingAnOrderRequiresAManagerAndThenBlocksTheInvoice() {
        val order = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody("Acme Corp", 250_000L, "USD", "1 desk"),
            token = repToken,
        )
        val orderId = order.field("erpRecordId") ?: error("order must be created")
        val cancel = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            """{"tool":"sales.order.cancel","toolVersion":"1.2.0","tenantId":"sim-alamal",""" +
                """"approverId":"USR-MGR","arguments":{"orderId":"$orderId","reason":"customer withdrew"}}""",
            token = repToken,
        )
        assertTrue(cancel.isStatus(MizanContract.Status.VERIFIED))
        val invoice = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            """{"tool":"invoice.create_from_order","toolVersion":"1.0.0","tenantId":"sim-alamal",""" +
                // The invoice carries no amount of its own: the authority judges
                // it by the amount of the order it bills. 2,500.00 USD is an L2
                // write, so it needs a privileged approver who is not the
                // initiator.
                """"approverId":"USR-FIN","arguments":{"orderId":"$orderId"}}""",
            token = repToken,
        )
        assertTrue(invoice.isStatus(MizanContract.Status.FAILED))
        assertEquals("ORDER_NOT_FOUND_OR_CANCELLED", invoice.field("messageCode"))
    }

    @Test
    fun healthReportsTheServiceIsUp() {
        val health = send(MizanContract.PATH_HEALTH, "GET")
        assertEquals(200, health.status)
        assertEquals("ok", health.field("status"))
        assertEquals("wakeel-reference", health.field("service"))
        assertNotEquals("0", health.field("executions"))
    }

    @Test
    fun auditChainStaysIntactAndIsScopedToTheTenant() {
        send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody("Acme Corp", 250_000L, "USD", "1 lamp"),
            token = repToken,
        )
        val audit = send(MizanContract.PATH_AUDIT, "GET", token = repToken)
        assertEquals(200, audit.status)
        assertEquals(true, Json.parseOrNull(audit.body)?.asObject()?.flag("chainIntact"))
        assertTrue(audit.body.contains("EXECUTION_VERIFIED"))
        val otherTenant = send(MizanContract.PATH_AUDIT + "?tenant=other-tenant", "GET", token = repToken)
        assertEquals(403, otherTenant.status)
    }

    @Test
    fun managerTokenCanReadErpState() {
        val erp = send(MizanContract.PATH_ERP, "GET", token = managerToken)
        assertEquals(200, erp.status)
        assertEquals("sim-alamal", erp.field("tenant"))
        assertTrue(erp.body.contains("\"orders\""))
    }
}
