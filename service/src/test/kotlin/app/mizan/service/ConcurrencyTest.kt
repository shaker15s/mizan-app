package app.mizan.service

import app.mizan.domain.model.Money
import app.mizan.domain.policy.VersionedPolicy
import app.mizan.service.erp.ErpCapabilities
import app.mizan.service.erp.ErpConnector
import app.mizan.service.erp.ErpCustomer
import app.mizan.service.erp.ErpRecord
import app.mizan.service.erp.ErpResult
import app.mizan.service.erp.ErpStock
import app.mizan.service.erp.InMemoryErp
import app.mizan.service.erp.InMemoryErpConnector
import app.mizan.service.json.Json
import app.mizan.service.json.asObject
import app.mizan.service.json.field
import app.mizan.service.json.text
import app.mizan.service.protocol.MizanContract
import app.mizan.service.store.FileLogProvider
import app.mizan.service.store.LogProvider
import app.mizan.service.store.RecordLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the service does when two requests arrive at once.
 *
 * Every store here is synchronized per method, which is the right granularity
 * for a map and the wrong granularity for a decision. An approval is read,
 * judged and written across three calls; the consumption of an approval by an
 * execution is read-then-write; the idempotency index is consulted and then
 * written. Two requests that interleave inside one of those sequences both see
 * the same "before" state and both write their own "after" state.
 *
 * That failure mode is the reason this file exists rather than another
 * happy-path test: a race in a governance service does not look like an error.
 * It looks like two draft orders where the person asked for one, or an audit
 * trail missing the second approver, and both of those look like success from
 * every screen and every receipt.
 *
 * The assertions are about invariants rather than about which thread wins,
 * because the winner is not specified: what is specified is that exactly one
 * write reaches the ERP, that a recorded decision is not lost, and that a
 * refusal has a code.
 */
class ConcurrencyTest {

    private lateinit var dir: Path
    private lateinit var erp: InMemoryErp
    private lateinit var connector: CountingConnector
    private lateinit var service: MizanService
    private lateinit var base: String
    private var port = 0

    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    /**
     * A connector that counts what it was asked to write and can be told to be
     * slow, before it delegates: the count is the claim under test, so it is
     * taken where the write actually leaves the service.
     */
    private class CountingConnector(
        private val inner: InMemoryErpConnector,
        private val delayMillis: Long = 0,
    ) : ErpConnector {
        val writes = AtomicInteger(0)
        val reads = AtomicInteger(0)

        override val id: String get() = inner.id
        override fun capabilities(): ErpCapabilities = inner.capabilities()
        override fun findCustomer(tenantId: String, query: String): ErpResult<List<ErpCustomer>> {
            reads.incrementAndGet()
            return inner.findCustomer(tenantId, query)
        }

        override fun checkStock(tenantId: String, sku: String): ErpResult<ErpStock> = inner.checkStock(tenantId, sku)
        override fun salesSummary(tenantId: String, period: String): ErpResult<String> = inner.salesSummary(tenantId, period)

        override fun createDraftOrder(
            tenantId: String,
            customerName: String,
            amount: Money,
            itemsSummary: String,
        ): ErpResult<ErpRecord> {
            writes.incrementAndGet()
            if (delayMillis > 0) Thread.sleep(delayMillis)
            return inner.createDraftOrder(tenantId, customerName, amount, itemsSummary)
        }

        override fun cancelOrder(tenantId: String, orderId: String, reason: String): ErpResult<ErpRecord> =
            inner.cancelOrder(tenantId, orderId, reason)

        override fun createInvoice(tenantId: String, orderId: String): ErpResult<ErpRecord> =
            inner.createInvoice(tenantId, orderId)

        override fun registerPayment(
            tenantId: String,
            invoiceId: String,
            amount: Money,
        ): ErpResult<ErpRecord> = inner.registerPayment(tenantId, invoiceId, amount)

        override fun readBack(tenantId: String, model: String, recordId: String): ErpResult<ErpRecord> =
            inner.readBack(tenantId, model, recordId)

        override fun orderAmount(tenantId: String, orderId: String): ErpResult<Money> =
            inner.orderAmount(tenantId, orderId)

        override fun candidates(tenantId: String, model: String, limit: Int): List<String> =
            inner.candidates(tenantId, model, limit)

        override fun customerState(tenantId: String, customerName: String): ErpCustomer? =
            inner.customerState(tenantId, customerName)

        override fun recentRecords(tenantId: String, model: String, limit: Int): List<ErpRecord> =
            inner.recentRecords(tenantId, model, limit)

        override fun reachable(): Boolean = inner.reachable()
    }

    /**
     * Appends that take as long as a real durable write.
     *
     * The window between reading an approval and writing it back is invisible
     * when the store is an in-memory map, and it is the whole point of the
     * exercise: a production deployment writes to PostgreSQL over a network,
     * where an append is milliseconds at best. Slowing the log down does not
     * invent a race -- it makes the one that exists reproducible, which is how
     * a test can be evidence rather than a coin flip.
     */
    private class DurableLogProvider(
        private val delegate: LogProvider,
        private val delayMillis: Long,
    ) : LogProvider {
        override fun open(stream: String): RecordLog {
            val inner = delegate.open(stream)
            return object : RecordLog {
                override fun append(record: String) {
                    Thread.sleep(delayMillis)
                    inner.append(record)
                }

                override fun records(): List<String> = inner.records()
                override fun compact(state: List<String>) = inner.compact(state)
                override fun close() = inner.close()
            }
        }

        override fun close() = delegate.close()
    }

    @Before
    fun start() {
        dir = Files.createTempDirectory("mizan-concurrency-")
        erp = InMemoryErp()
        connector = CountingConnector(InMemoryErpConnector(erp))
        service = newService(dir, connector, sessionTtlMillis = 30 * 60 * 1000L)
        port = service.start(port = 0)
        base = "http://127.0.0.1:$port"
    }

    @After
    fun stop() {
        runCatching { service.stop() }
        runCatching { dir.toFile().deleteRecursively() }
    }

    private fun newService(
        directory: Path,
        connector: ErpConnector,
        sessionTtlMillis: Long,
        versionedPolicy: VersionedPolicy = VersionedPolicy.demoV12,
        appendDelayMillis: Long = 0,
    ): MizanService = MizanService(
        ServiceConfig(
            storeDirectory = directory,
            logProvider = if (appendDelayMillis > 0) {
                DurableLogProvider(FileLogProvider(directory), appendDelayMillis)
            } else {
                null
            },
            signingSecret = "concurrency-test-key",
            versionedPolicy = versionedPolicy,
            sessionTtlMillis = sessionTtlMillis,
            connector = connector,
            // The rate limiter is per actor and would turn a legitimate burst
            // of concurrent requests into a 429, which is a different test.
            rateLimiting = false,
            // The sweeper would retry behind the test's back; the retry path
            // has its own tests.
            outboxSweepMillis = 0,
        ),
    )

    private data class Reply(val status: Int, val body: String) {
        fun field(name: String): String? = Json.parseOrNull(body)?.asObject()?.text(name)
        fun isStatus(value: String): Boolean = field("status") == value
    }

    private fun send(
        path: String,
        method: String,
        body: String? = null,
        token: String? = null,
        idempotencyKey: String? = null,
        timeoutMillis: Long = 20_000,
    ): Reply {
        val builder = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofMillis(timeoutMillis))
            .header("Content-Type", "application/json")
        if (token != null) builder.header(MizanContract.HEADER_AUTHORIZATION, "Bearer $token")
        if (idempotencyKey != null) builder.header(MizanContract.HEADER_IDEMPOTENCY_KEY, idempotencyKey)
        val publisher = if (body == null) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(body)
        }
        builder.method(method, publisher)
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return Reply(response.statusCode(), response.body())
    }

    private fun signIn(email: String, password: String): String =
        send(MizanContract.PATH_SESSIONS, "POST", """{"email":"$email","password":"$password"}""")
            .field("token") ?: error("sign in must succeed for $email")

    private fun orderBody(
        executionId: String,
        amountMinor: Long = 250_000L,
        tenantId: String = "sim-alamal",
        approvalId: String? = null,
        fingerprint: String? = null,
        approverId: String? = null,
    ): String {
        val fields = mutableListOf(
            """"tool":"sales.order.create_draft"""",
            """"toolVersion":"2.1.0"""",
            """"tenantId":"$tenantId"""",
            """"executionId":"$executionId"""",
            """"proposalId":"PROP-$executionId"""",
        )
        approvalId?.let { fields += """"approvalId":"$it"""" }
        fingerprint?.let { fields += """"proposalFingerprint":"$it"""" }
        approverId?.let { fields += """"approverId":"$it"""" }
        fields += """"arguments":{"amountMinor":"$amountMinor","currency":"USD",""" +
            """"customerName":"Acme Corp","itemsSummary":"1 desk"}"""
        return "{" + fields.joinToString(",") + "}"
    }

    private fun openApproval(executionId: String, token: String, amountMinor: Long = 250_000L): Pair<String, String> {
        val opened = send(
            MizanContract.PATH_APPROVALS,
            "POST",
            orderBody(executionId, amountMinor = amountMinor),
            token = token,
        )
        assertEquals("an approval must open: ${opened.body}", 201, opened.status)
        return opened.field("approvalId")!! to opened.field("proposalFingerprint")!!
    }

    /** Sends the same request from [threads] callers, released together. */
    private fun inParallel(
        threads: Int,
        block: (Int) -> Reply,
    ): List<Reply> {
        val pool = Executors.newFixedThreadPool(threads)
        val gate = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val replies = Collections.synchronizedList(mutableListOf<Reply>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        repeat(threads) { index ->
            pool.execute {
                try {
                    gate.await(30, TimeUnit.SECONDS)
                    replies.add(block(index))
                } catch (error: Throwable) {
                    failures.add(error)
                } finally {
                    done.countDown()
                }
            }
        }
        gate.countDown()
        assertTrue("every caller must finish", done.await(60, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals("no caller may crash: $failures", 0, failures.size)
        return replies.toList()
    }

    // --------------------------------------------------------- the approvals

    @Test
    fun twoApproversAnsweringAtOnceAreBothRecorded() {
        // An order above the top threshold needs two different people. The
        // manager and the finance approver answer at the same instant, which
        // is what a real dual approval looks like when both are at their desk.
        //
        // The failure this catches is not an error message: one answer is read,
        // the other answer is read, both write their own copy of the record,
        // and the last writer wins. The approval then says one person approved
        // it -- and looks exactly like a successful, complete, dual approval.
        //
        // The window between the read and the write is what decides whether
        // that happens, and with the file-backed store in one process it is
        // narrow: the store synchronizes each call, so the two answers have to
        // interleave inside microseconds. In the PostgreSQL deployment it is a
        // network round trip, which is why the lock this test protects is not
        // optional there.
        runCatching { service.stop() }
        service = newService(dir, connector, sessionTtlMillis = 30 * 60 * 1000L, appendDelayMillis = 15)
        port = service.start(port = 0)
        base = "http://127.0.0.1:$port"

        val rep = signIn("rep@mizan.test", "rep-demo-password")
        val (approvalId, _) = openApproval("EXE-CONC-DUAL", rep, amountMinor = 2_600_000L)
        val manager = signIn("manager@mizan.test", "manager-demo-password")
        val finance = signIn("finance@mizan.test", "finance-demo-password")

        val replies = inParallel(threads = 2) { index ->
            val token = if (index == 0) manager else finance
            send("${MizanContract.PATH_APPROVALS}/$approvalId/grant", "POST", "{}", token = token)
        }

        val accepted = replies.count { it.status == 200 }
        assertEquals("both approvers are allowed to answer: ${replies.map { it.body }}", 2, accepted)
        val stored = service.stores!!.approvals.get(approvalId)!!
        assertEquals(
            "both answers must be in the record, not just the last one written",
            2,
            stored.decisions.size,
        )
        assertEquals(
            "two distinct approvers, recorded as such",
            2,
            stored.approvals.map { it.approver.id.value }.distinct().size,
        )
        assertEquals(
            "the approval is complete, so an execution is legal: ${stored.state}",
            app.mizan.domain.approval.ApprovalState.GRANTED,
            stored.state,
        )
    }

    @Test
    fun twoExecutionsUnderOneApprovalReachTheErpOnce() {
        // The window here is the whole ERP round trip: both callers validate
        // the same granted approval, and the approval is only marked consumed
        // on the way to the write. A connector that takes as long as a real
        // one makes that window visible.
        runCatching { service.stop() }
        val slow = CountingConnector(InMemoryErpConnector(erp), delayMillis = 400)
        connector = slow
        service = newService(dir, slow, sessionTtlMillis = 30 * 60 * 1000L)
        port = service.start(port = 0)
        base = "http://127.0.0.1:$port"

        val rep = signIn("rep@mizan.test", "rep-demo-password")
        val manager = signIn("manager@mizan.test", "manager-demo-password")
        val (approvalId, fingerprint) = openApproval("EXE-CONC-CONSUME", rep)
        val granted = send("${MizanContract.PATH_APPROVALS}/$approvalId/grant", "POST", "{}", token = manager)
        assertEquals(granted.body, 200, granted.status)

        val before = erp.snapshot("sim-alamal").orders.size
        // Same execution, same approval, four different idempotency keys: the
        // approval is the scarce resource, and it may authorise one write.
        val replies = inParallel(threads = 4) { index ->
            send(
                MizanContract.PATH_EXECUTIONS,
                "POST",
                orderBody(
                    "EXE-CONC-CONSUME",
                    approvalId = approvalId,
                    fingerprint = fingerprint,
                    approverId = "USR-MGR",
                ),
                token = rep,
                idempotencyKey = "conc-consume-$index",
            )
        }

        val accepted = replies.count { it.status == 200 }
        assertEquals("exactly one execution may consume the approval", 1, accepted)
        assertEquals(
            "one approval means one draft order",
            before + 1,
            erp.snapshot("sim-alamal").orders.size,
        )
        assertEquals("the ERP was asked to write once", 1, connector.writes.get())
        replies.filter { it.status != 200 }.forEach { reply ->
            assertNotNull("a refusal says why: ${reply.body}", reply.field("messageCode"))
        }
    }

    // ------------------------------------------------------ the idempotency key

    @Test
    fun oneIdempotencyKeyDispatchesOnceUnderConcurrency() {
        runCatching { service.stop() }
        val slow = CountingConnector(InMemoryErpConnector(erp), delayMillis = 400)
        connector = slow
        service = newService(dir, slow, sessionTtlMillis = 30 * 60 * 1000L)
        port = service.start(port = 0)
        base = "http://127.0.0.1:$port"

        val rep = signIn("rep@mizan.test", "rep-demo-password")
        val (approvalId, fingerprint) = openApproval("EXE-CONC-KEY", rep)
        val manager = signIn("manager@mizan.test", "manager-demo-password")
        send("${MizanContract.PATH_APPROVALS}/$approvalId/grant", "POST", "{}", token = manager)

        val before = erp.snapshot("sim-alamal").orders.size
        val body = orderBody(
            "EXE-CONC-KEY",
            approvalId = approvalId,
            fingerprint = fingerprint,
            approverId = "USR-MGR",
        )
        // The same key, the same body, eight callers: this is what a client
        // retrying a request it did not hear back about looks like.
        val replies = inParallel(threads = 8) {
            send(MizanContract.PATH_EXECUTIONS, "POST", body, token = rep, idempotencyKey = "conc-one-key")
        }

        val accepted = replies.filter { it.status == 200 }
        assertTrue("at least one caller must succeed", accepted.isNotEmpty())
        assertEquals(
            "the ERP is asked to write once, however many callers asked",
            before + 1,
            erp.snapshot("sim-alamal").orders.size,
        )
        assertEquals("one write left the service", 1, connector.writes.get())

        // Everything else is either a replay of the same outcome or a refusal
        // with a code. A second *different* outcome would mean two executions.
        val recordIds = accepted.mapNotNull { it.field("erpRecordId") }.distinct()
        assertEquals("every accepted caller sees the same record", 1, recordIds.size)
        replies.filter { it.status != 200 }.forEach { reply ->
            assertNotNull("a refusal says why: ${reply.body}", reply.field("messageCode"))
        }
    }

    @Test
    fun aLateErpAnswerIsReplayedRatherThanRepeated() {
        // The connector takes longer than the client is willing to wait. From
        // the client's side the request failed; from the service's side it is
        // about to succeed. The only safe behaviour is that the retry with the
        // same key finds the write and reports it, instead of writing again.
        val slow = CountingConnector(InMemoryErpConnector(erp), delayMillis = 900)
        runCatching { service.stop() }
        connector = slow
        service = newService(dir, slow, sessionTtlMillis = 30 * 60 * 1000L)
        port = service.start(port = 0)
        base = "http://127.0.0.1:$port"

        val rep = signIn("rep@mizan.test", "rep-demo-password")
        val (approvalId, fingerprint) = openApproval("EXE-CONC-LATE", rep)
        val manager = signIn("manager@mizan.test", "manager-demo-password")
        send("${MizanContract.PATH_APPROVALS}/$approvalId/grant", "POST", "{}", token = manager)

        val body = orderBody(
            "EXE-CONC-LATE",
            approvalId = approvalId,
            fingerprint = fingerprint,
            approverId = "USR-MGR",
        )
        val timedOut = runCatching {
            send(
                MizanContract.PATH_EXECUTIONS,
                "POST",
                body,
                token = rep,
                idempotencyKey = "conc-late",
                timeoutMillis = 250,
            )
        }
        assertTrue("the client must have given up waiting", timedOut.isFailure)

        // Let the service finish what it started.
        val deadline = System.currentTimeMillis() + 10_000
        while (erp.snapshot("sim-alamal").orders.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals("the write landed once", 1, slow.writes.get())

        val retry = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            body,
            token = rep,
            idempotencyKey = "conc-late",
        )
        assertEquals("the retry must be answered, not refused as a conflict: ${retry.body}", 200, retry.status)
        assertEquals("the ERP was not asked to write twice", 1, slow.writes.get())
        assertEquals("one order exists", 1, erp.snapshot("sim-alamal").orders.size)
    }

    // ------------------------------------------------------------- the session

    @Test
    fun aSessionThatExpiresDuringTheWriteRefusesInsteadOfWriting() {
        val shortLived = newService(dir, CountingConnector(InMemoryErpConnector(erp)), sessionTtlMillis = 1_200L)
        runCatching { service.stop() }
        service = shortLived
        port = shortLived.start(port = 0)
        base = "http://127.0.0.1:$port"

        val rep = signIn("rep@mizan.test", "rep-demo-password")
        val (approvalId, fingerprint) = openApproval("EXE-CONC-EXPIRY", rep)
        val manager = signIn("manager@mizan.test", "manager-demo-password")
        send("${MizanContract.PATH_APPROVALS}/$approvalId/grant", "POST", "{}", token = manager)

        Thread.sleep(1_400)

        val before = erp.snapshot("sim-alamal").orders.size
        val attempt = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            orderBody("EXE-CONC-EXPIRY", approvalId = approvalId, fingerprint = fingerprint, approverId = "USR-MGR"),
            token = rep,
        )
        assertTrue("an expired session is not a success: ${attempt.body}", attempt.status >= 400)
        assertEquals("nothing reached the ERP", before, erp.snapshot("sim-alamal").orders.size)
        assertEquals("nothing left the service", 0, connector.writes.get())
        assertEquals(
            "no journal was opened for a request that was never authorised",
            null,
            service.stores!!.journals.get("EXE-CONC-EXPIRY"),
        )
    }

    @Test
    fun aSessionForOneTenantCannotWriteForAnother() {
        val rep = signIn("rep@mizan.test", "rep-demo-password")
        val attempts = inParallel(threads = 3) { index ->
            send(
                MizanContract.PATH_EXECUTIONS,
                "POST",
                orderBody("EXE-CONC-TENANT-$index", tenantId = "other-tenant"),
                token = rep,
                idempotencyKey = "conc-tenant-$index",
            )
        }
        assertEquals(0, connector.writes.get())
        attempts.forEach { reply ->
            assertEquals("a session may not cross a tenant: ${reply.body}", 403, reply.status)
            assertEquals("TENANT_MISMATCH", reply.field("messageCode"))
        }
    }

    // --------------------------------------------- the world moves underneath

    @Test
    fun anApprovalOpenedUnderOnePolicyIsRefusedAfterThePolicyMoves() {
        val rep = signIn("rep@mizan.test", "rep-demo-password")
        val (approvalId, fingerprint) = openApproval("EXE-CONC-POLICY", rep)
        val manager = signIn("manager@mizan.test", "manager-demo-password")
        send("${MizanContract.PATH_APPROVALS}/$approvalId/grant", "POST", "{}", token = manager)

        // The deployment applies a new policy version and restarts. The
        // approval was granted against v12 and carries that version; the
        // execution must be refused rather than interpreted under v13.
        val moved = VersionedPolicy(
            version = app.mizan.domain.policy.PolicyVersion(
                version = 13,
                effectiveFrom = java.time.Instant.parse("2026-09-27T00:00:00Z"),
                label = "demo-moved",
            ),
            ladders = VersionedPolicy.demoV12.ladders,
            rules = VersionedPolicy.demoV12.rules,
        )
        runCatching { service.stop() }
        service = newService(dir, connector, sessionTtlMillis = 30 * 60 * 1000L, versionedPolicy = moved)
        port = service.start(port = 0)
        base = "http://127.0.0.1:$port"

        val repAgain = signIn("rep@mizan.test", "rep-demo-password")
        val before = erp.snapshot("sim-alamal").orders.size
        val attempt = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            orderBody("EXE-CONC-POLICY", approvalId = approvalId, fingerprint = fingerprint, approverId = "USR-MGR"),
            token = repAgain,
            idempotencyKey = "conc-policy",
        )
        assertEquals("a moved policy invalidates the approval: ${attempt.body}", 409, attempt.status)
        assertEquals("POLICY_VERSION_CHANGED", attempt.field("messageCode"))
        assertEquals("nothing reached the ERP", before, erp.snapshot("sim-alamal").orders.size)
        assertEquals("nothing left the service", 0, connector.writes.get())
    }

    @Test
    fun aRestartAfterTheWriteReplaysInsteadOfWritingAgain() {
        val rep = signIn("rep@mizan.test", "rep-demo-password")
        val (approvalId, fingerprint) = openApproval("EXE-CONC-RESTART", rep)
        val manager = signIn("manager@mizan.test", "manager-demo-password")
        send("${MizanContract.PATH_APPROVALS}/$approvalId/grant", "POST", "{}", token = manager)

        val body = orderBody(
            "EXE-CONC-RESTART",
            approvalId = approvalId,
            fingerprint = fingerprint,
            approverId = "USR-MGR",
        )
        val first = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = rep, idempotencyKey = "conc-restart")
        assertEquals(first.body, 200, first.status)
        assertEquals(1, connector.writes.get())

        // The process dies here: the store directory is the only thing that
        // survives, which is exactly the claim a durable journal makes.
        runCatching { service.stop() }
        val survivor = newService(dir, connector, sessionTtlMillis = 30 * 60 * 1000L)
        service = survivor
        port = survivor.start(port = 0)
        base = "http://127.0.0.1:$port"

        val repAgain = signIn("rep@mizan.test", "rep-demo-password")
        val retry = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            body,
            token = repAgain,
            idempotencyKey = "conc-restart",
        )
        assertEquals("the retry after a restart is answered: ${retry.body}", 200, retry.status)
        assertEquals("the journal survived the restart", first.field("status"), retry.field("status"))
        assertEquals("the ERP was not asked to write twice", 1, connector.writes.get())
        assertEquals("one order exists", 1, erp.snapshot("sim-alamal").orders.size)
    }
}
