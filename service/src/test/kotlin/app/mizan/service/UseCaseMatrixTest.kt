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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The twenty things a company actually does with this app, run against a real
 * listener on a real port.
 *
 * `MizanServiceHttpTest` proves the contract. This file proves the product:
 * each test is a use case a sales rep, a manager, a finance approver or an
 * auditor would recognise, and each one asserts the answer the person is
 * entitled to get -- not merely that the endpoint replied.
 *
 * The amounts are chosen against the demo USD ladder, which the UI must label
 * as simulation:
 *
 *     L1  up to 1,000.00    the initiator confirms it themselves
 *     L2  up to 10,000.00   a privileged approver who is not the initiator
 *     L3  up to 25,000.00   a sales manager
 *     L4  above 25,000.00   two privileged approvers
 */
class UseCaseMatrixTest {

    data class Reply(val status: Int, val body: String) {
        fun text(name: String): String? = Json.parseOrNull(body)?.asObject()?.text(name)
        fun number(name: String): Long? = Json.parseOrNull(body)?.asObject()?.field(name)?.asLong()
        fun truth(name: String): Boolean? = Json.parseOrNull(body)?.asObject()?.flag(name)
        fun isStatus(value: String): Boolean = text("status") == value
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

        fun signIn(email: String, password: String): String? = send(
            MizanContract.PATH_SESSIONS,
            "POST",
            """{"email":"$email","password":"$password"}""",
        ).text("token")

        /** A sales rep, a manager, a finance approver, an auditor. */
        val repToken: String
            get() = signIn("rep@mizan.test", "rep-demo-password")!!

        val managerToken: String
            get() = signIn("manager@mizan.test", "manager-demo-password")!!

        val financeToken: String
            get() = signIn("finance@mizan.test", "finance-demo-password")!!

        val auditorToken: String
            get() = signIn("auditor@mizan.test", "auditor-demo-password")!!

        fun draftOrder(
            customer: String,
            amountMinor: Long,
            currency: String = "USD",
            items: String,
            approverId: String = "USR-MGR",
        ): String =
            """{"tool":"sales.order.create_draft","toolVersion":"2.1.0","tenantId":"sim-alamal",""" +
                """"approverId":"$approverId",""" +
                """"arguments":{"amountMinor":"$amountMinor","currency":"$currency",""" +
                """"customerName":"$customer","itemsSummary":"$items"}}"""

        fun cancelOrder(orderId: String, reason: String?, approverId: String = "USR-MGR"): String =
            """{"tool":"sales.order.cancel","toolVersion":"1.2.0","tenantId":"sim-alamal",""" +
                """"approverId":"$approverId","arguments":{""" +
                (if (reason == null) "" else """"reason":"$reason",""") +
                """"orderId":"$orderId"}}"""

        fun invoiceFor(orderId: String, approverId: String = "USR-FIN"): String =
            """{"tool":"invoice.create_from_order","toolVersion":"1.0.0","tenantId":"sim-alamal",""" +
                """"approverId":"$approverId","arguments":{"orderId":"$orderId"}}"""

        fun paymentFor(invoiceId: String, amountMinor: Long, approverId: String = "USR-REP"): String =
            """{"tool":"payment.register","toolVersion":"1.1.0","tenantId":"sim-alamal",""" +
                """"approverId":"$approverId","arguments":{"invoiceId":"$invoiceId",""" +
                """"amountMinor":"$amountMinor","currency":"USD"}}"""
    }

    // -----------------------------------------------------------------------
    // Identity
    // -----------------------------------------------------------------------

    /** 1. A rep opens the app and signs in with the company directory. */
    @Test
    fun aRepSignsInAndTheServiceAnswersWithTheirIdentity() {
        val reply = send(
            MizanContract.PATH_SESSIONS,
            "POST",
            """{"email":"rep@mizan.test","password":"rep-demo-password"}""",
        )
        assertEquals(200, reply.status)
        assertEquals("USR-REP", reply.text("actorId"))
        assertEquals("SALES_REP", reply.text("role"))
        assertEquals("sim-alamal", reply.text("tenantId"))
        assertNotEquals(null, reply.text("token"))
        // A wrong password and an unknown account are the same answer.
        val wrong = send(
            MizanContract.PATH_SESSIONS,
            "POST",
            """{"email":"rep@mizan.test","password":"nope"}""",
        )
        assertEquals(401, wrong.status)
        assertNull(wrong.text("token"))
        val unknown = send(
            MizanContract.PATH_SESSIONS,
            "POST",
            """{"email":"nobody@mizan.test","password":"rep-demo-password"}""",
        )
        assertEquals(401, unknown.status)
        assertEquals(wrong.text("messageCode"), unknown.text("messageCode"))
    }

    /** 2. Someone guessing passwords is locked out, and the lock is silent. */
    @Test
    fun repeatedWrongPasswordsLockTheAccountWithoutSayingSo() {
        repeat(12) {
            send(
                MizanContract.PATH_SESSIONS,
                "POST",
                """{"email":"finance@mizan.test","password":"guess-$it"}""",
            )
        }
        val correct = send(
            MizanContract.PATH_SESSIONS,
            "POST",
            """{"email":"finance@mizan.test","password":"finance-demo-password"}""",
        )
        assertEquals(401, correct.status)
        assertNull(correct.text("token"))
    }

    /** 3. A stolen or expired token buys nothing. */
    @Test
    fun anUnknownTokenIsRefusedExactlyLikeNoTokenAtAll() {
        val none = send(MizanContract.PATH_EXECUTIONS, "POST", draftOrder("Acme", 50_000, "USD", "1 lamp"))
        val garbage = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrder("Acme", 50_000, "USD", "1 lamp"),
            token = "not-a-token",
        )
        assertEquals(401, none.status)
        assertEquals(401, garbage.status)
        assertEquals("SESSION_EXPIRED", none.text("messageCode"))
        assertEquals("SESSION_EXPIRED", garbage.text("messageCode"))
    }

    // -----------------------------------------------------------------------
    // Writing, with the ladder
    // -----------------------------------------------------------------------

    /** 4. A small order: the rep confirms it themselves and it is verified. */
    @Test
    fun aSmallOrderIsConfirmedByItsAuthorAndVerifiedByReadingItBack() {
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrder("Cairo Tech", 50_000, "USD", "2 keyboards", approverId = "USR-REP"),
            token = repToken,
        )
        assertEquals(200, reply.status)
        assertTrue(reply.isStatus(MizanContract.Status.VERIFIED))
        assertTrue(reply.text("erpRecordId")?.startsWith("SO-") == true)
        assertEquals(MizanContract.ErpModel.SALE_ORDER, reply.text("erpModel"))
        assertEquals("READ_BACK", reply.text("verification"))
    }

    /** 5. A mid-size order needs a privileged approver who is not the author. */
    @Test
    fun aMidSizeOrderNeedsAPrivilegedApproverWhoIsNotTheAuthor() {
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrder("Cairo Tech", 250_000, "USD", "4 chairs", approverId = "USR-MGR"),
            token = repToken,
        )
        assertEquals(200, reply.status)
        assertTrue(reply.isStatus(MizanContract.Status.VERIFIED))
    }

    /** 6. The author cannot approve their own privileged write. */
    @Test
    fun theAuthorCannotApproveTheirOwnPrivilegedWrite() {
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrder("Cairo Tech", 250_000, "USD", "4 chairs", approverId = "USR-REP"),
            token = repToken,
        )
        assertEquals(422, reply.status)
        assertEquals("SOD_SAME_ACTOR", reply.text("messageCode"))
    }

    /** 7. Naming an approver without the rank does not raise the rank. */
    @Test
    fun namingAnApproverWithoutTheRankDoesNotRaiseTheRank() {
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrder("Cairo Tech", 250_000, "USD", "4 chairs", approverId = "USR-AUD"),
            token = repToken,
        )
        assertEquals(422, reply.status)
        assertEquals("SOD_ROLE_INSUFFICIENT", reply.text("messageCode"))
    }

    /** 8. A large order needs the manager, not a finance approver's colleague. */
    @Test
    fun anOrderAboveTheManagerThresholdNeedsTheManager() {
        // 30,000.00 USD is above L3, so two privileged approvers are required
        // and the contract carries only one. The answer has to be a refusal
        // that says what is missing, never a half-approved write.
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrder("Cairo Tech", 3_000_000, "USD", "1 production line", approverId = "USR-MGR"),
            token = repToken,
        )
        assertEquals(422, reply.status)
        assertEquals("SOD_NEED_SECOND_APPROVER", reply.text("messageCode"))
    }

    /** 9. An auditor can read everything and change nothing. */
    @Test
    fun anAuditorCanReadTheErpAndCannotChangeIt() {
        val read = send(MizanContract.PATH_ERP, "GET", token = auditorToken)
        assertEquals(200, read.status)
        assertEquals("sim-alamal", read.text("tenant"))

        val write = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrder("Cairo Tech", 50_000, "USD", "1 lamp", approverId = "USR-REP"),
            token = auditorToken,
        )
        assertEquals(422, write.status)
        assertEquals("AUDITOR_READONLY", write.text("messageCode"))
    }

    // -----------------------------------------------------------------------
    // Safety under repetition and uncertainty
    // -----------------------------------------------------------------------

    /** 10. A retried request with the same key does not double the order. */
    @Test
    fun retryingWithTheSameKeyReplaysTheAnswerAndCreatesNothing() {
        val key = "uc-retry-" + System.nanoTime()
        val headers = mapOf(MizanContract.HEADER_IDEMPOTENCY_KEY to key)
        val body = draftOrder("Cairo Tech", 250_000, "USD", "6 desks", approverId = "USR-MGR")
        val first = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = repToken, headers = headers)
        val second = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = repToken, headers = headers)
        assertTrue(first.isStatus(MizanContract.Status.VERIFIED))
        assertTrue(second.isStatus(MizanContract.Status.VERIFIED))
        assertEquals(first.text("erpRecordId"), second.text("erpRecordId"))
    }

    /** 11. Reusing a key for a different request is refused, not overwritten. */
    @Test
    fun reusingAKeyForADifferentRequestIsRefused() {
        val key = "uc-reuse-" + System.nanoTime()
        val headers = mapOf(MizanContract.HEADER_IDEMPOTENCY_KEY to key)
        val first = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrder("Cairo Tech", 250_000, "USD", "2 desks", approverId = "USR-MGR"),
            token = repToken,
            headers = headers,
        )
        val second = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrder("Cairo Tech", 260_000, "USD", "2 desks", approverId = "USR-MGR"),
            token = repToken,
            headers = headers,
        )
        assertTrue(first.isStatus(MizanContract.Status.VERIFIED))
        assertEquals(409, second.status)
        assertEquals("IDEMPOTENCY_KEY_REUSE", second.text("messageCode"))
    }

    /** 12. When the ERP answer is unclear, the app says so and keeps the row. */
    @Test
    fun anUnclearErpAnswerIsReportedAsAmbiguousAndTheWriteIsKept() {
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrder("Cairo Tech", 250_000, "USD", "1 projector", approverId = "USR-MGR"),
            token = repToken,
            headers = mapOf(MizanContract.HEADER_SIMULATE to MizanContract.SIMULATE_AMBIGUOUS),
        )
        assertEquals(200, reply.status)
        assertTrue(reply.isStatus(MizanContract.Status.AMBIGUOUS))
        assertFalse(reply.text("candidates").isNullOrBlank())
        // It did land. That is why the answer is ambiguous and why the client
        // opens reconciliation instead of retrying.
        val erp = send(MizanContract.PATH_ERP, "GET", token = managerToken)
        assertTrue(erp.body.contains("SO-"))
    }

    /** 13. A request aimed at another tenant never reaches the ERP. */
    @Test
    fun aRequestForAnotherTenantIsRefusedBeforeTheErpHearsIt() {
        val body = draftOrder("Cairo Tech", 250_000, "USD", "1 lamp", approverId = "USR-MGR")
            .replace("sim-alamal", "other-tenant")
        val reply = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = repToken)
        assertEquals(403, reply.status)
        assertEquals("TENANT_MISMATCH", reply.text("messageCode"))
    }

    /** 14. A tool the contract does not define is refused, never guessed. */
    @Test
    fun anUnknownToolIsRefusedInsteadOfGuessed() {
        val body = """{"tool":"erp.do.whatever","toolVersion":"1.0.0","tenantId":"sim-alamal","arguments":{}}"""
        val reply = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = repToken)
        assertEquals(422, reply.status)
        assertEquals("TOOL_UNKNOWN", reply.text("messageCode"))
    }

    /** 15. A request missing its amount is refused instead of defaulted. */
    @Test
    fun aRequestMissingItsAmountIsRefusedInsteadOfDefaulted() {
        val body = """{"tool":"sales.order.create_draft","toolVersion":"2.1.0","tenantId":"sim-alamal",""" +
            """"approverId":"USR-MGR","arguments":{"customerName":"Cairo Tech","itemsSummary":"1 lamp"}}"""
        val reply = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = repToken)
        assertEquals(422, reply.status)
        assertEquals("MISSING_AMOUNT", reply.text("messageCode"))
    }

    /** 16. A negative amount is not a small amount. */
    @Test
    fun aNegativeAmountIsRefusedAsANegativeAmount() {
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrder("Cairo Tech", -5_000, "USD", "1 lamp", approverId = "USR-REP"),
            token = repToken,
        )
        assertEquals(422, reply.status)
        assertEquals("NEGATIVE_AMOUNT", reply.text("messageCode"))
    }

    // -----------------------------------------------------------------------
    // The money path
    // -----------------------------------------------------------------------

    /** 17. Order, invoice, payment: each step verified before the next. */
    @Test
    fun orderInvoiceAndPaymentFormAVerifiedChain() {
        val order = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrder("Cairo Tech", 250_000, "USD", "3 monitors", approverId = "USR-MGR"),
            token = repToken,
        )
        assertTrue(order.isStatus(MizanContract.Status.VERIFIED))
        val orderId = order.text("erpRecordId")!!

        val invoice = send(MizanContract.PATH_EXECUTIONS, "POST", invoiceFor(orderId), token = repToken)
        assertTrue("invoice: " + invoice.body, invoice.isStatus(MizanContract.Status.VERIFIED))
        assertEquals(MizanContract.ErpModel.INVOICE, invoice.text("erpModel"))
        val invoiceId = invoice.text("erpRecordId")!!

        val payment = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            paymentFor(invoiceId, 250_000, approverId = "USR-FIN"),
            token = repToken,
        )
        assertTrue("payment: " + payment.body, payment.isStatus(MizanContract.Status.VERIFIED))
        assertEquals(MizanContract.ErpModel.PAYMENT, payment.text("erpModel"))
    }

    /** 18. A payment for an invoice that does not exist fails honestly. */
    @Test
    fun aPaymentForAMissingInvoiceFailsWithTheReason() {
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            paymentFor("INV-999999", 5_000),
            token = repToken,
        )
        assertEquals(200, reply.status)
        assertTrue(reply.isStatus(MizanContract.Status.FAILED))
        assertEquals("INVOICE_NOT_FOUND_OR_CURRENCY_MISMATCH", reply.text("messageCode"))
    }

    /** 19. Cancelling needs a reason, and a cancelled order cannot be invoiced. */
    @Test
    fun cancellingNeedsAReasonAndACancelledOrderCannotBeInvoiced() {
        val order = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrder("Cairo Tech", 250_000, "USD", "1 desk", approverId = "USR-MGR"),
            token = repToken,
        )
        val orderId = order.text("erpRecordId")!!

        val noReason = send(MizanContract.PATH_EXECUTIONS, "POST", cancelOrder(orderId, null), token = repToken)
        assertEquals(422, noReason.status)
        assertEquals("MISSING_REASON", noReason.text("messageCode"))

        val cancel = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            cancelOrder(orderId, "customer withdrew"),
            token = repToken,
        )
        assertTrue("cancel: " + cancel.body, cancel.isStatus(MizanContract.Status.VERIFIED))

        val invoice = send(MizanContract.PATH_EXECUTIONS, "POST", invoiceFor(orderId), token = repToken)
        assertTrue("invoice after cancel: " + invoice.body, invoice.isStatus(MizanContract.Status.FAILED))
        assertEquals("ORDER_NOT_FOUND_OR_CANCELLED", invoice.text("messageCode"))
    }

    /** 20. The audit trail is hash linked, and stops at the tenant boundary. */
    @Test
    fun theAuditTrailIsHashLinkedAndScopedToTheTenant() {
        send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrder("Cairo Tech", 250_000, "USD", "1 lamp", approverId = "USR-MGR"),
            token = repToken,
        )
        val audit = send(MizanContract.PATH_AUDIT, "GET", token = repToken)
        assertEquals(200, audit.status)
        assertEquals(true, audit.truth("chainIntact"))
        assertTrue(audit.body.contains("EXECUTION_VERIFIED"))
        assertTrue(audit.body.contains("SESSION_ISSUED"))

        val other = send(MizanContract.PATH_AUDIT + "?tenant=other-tenant", "GET", token = repToken)
        assertEquals(403, other.status)
    }

    /**
     * 21. An invoice is judged by the order it bills, not by its own body.
     *
     * The invoice request carries only an order id. If the authority read
     * nothing but the request it would call every invoice amountless, and
     * therefore low risk, however large the order behind it was.
     */
    @Test
    fun anInvoiceInheritsTheApprovalLadderOfItsOrder() {
        val order = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrder("Cairo Tech", 1_500_000, "USD", "1 server rack", approverId = "USR-MGR"),
            token = repToken,
        )
        assertTrue("order: " + order.body, order.isStatus(MizanContract.Status.VERIFIED))
        val orderId = order.text("erpRecordId")!!

        // 15,000.00 USD is an L3 write. A finance approver is privileged but
        // is not a sales manager, so this has to be refused.
        val finance = send(MizanContract.PATH_EXECUTIONS, "POST", invoiceFor(orderId, "USR-FIN"), token = repToken)
        assertEquals(422, finance.status)
        assertEquals("SOD_ROLE_INSUFFICIENT", finance.text("messageCode"))

        val manager = send(MizanContract.PATH_EXECUTIONS, "POST", invoiceFor(orderId, "USR-MGR"), token = repToken)
        assertTrue("invoice: " + manager.body, manager.isStatus(MizanContract.Status.VERIFIED))
    }

    /** 22. Operations can see the service is up without signing in. */
    @Test
    fun anyoneCanAskWhetherTheServiceIsUp() {
        val health = send(MizanContract.PATH_HEALTH, "GET")
        assertEquals(200, health.status)
        assertEquals("ok", health.text("status"))
        assertNotEquals("0", health.text("executions"))
    }
}
