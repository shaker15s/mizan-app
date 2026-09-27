package app.mizan.service

import app.mizan.domain.model.Money
import app.mizan.domain.model.Role
import app.mizan.domain.model.ToolName
import app.mizan.service.authority.ReferenceDeployment
import app.mizan.service.erp.ErpCapabilities
import app.mizan.service.erp.ErpConnector
import app.mizan.service.erp.ErpCustomer
import app.mizan.service.erp.ErpRecord
import app.mizan.service.erp.ErpResult
import app.mizan.service.erp.ErpStock
import app.mizan.service.erp.InMemoryErp
import app.mizan.service.erp.InMemoryErpConnector
import app.mizan.service.json.Json
import app.mizan.service.json.JsonValue
import app.mizan.service.json.asObject
import app.mizan.service.json.field
import app.mizan.service.json.flag
import app.mizan.service.outbox.Outbox
import app.mizan.service.outbox.OutboxWorker
import app.mizan.service.protocol.ExecutionOutcome
import app.mizan.service.protocol.ExecutionRequest
import app.mizan.service.security.ServiceUser
import app.mizan.service.store.DurableLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * The outbox is one sentence from the plan made real: a read the ERP could not
 * answer must be asked again later, and the intention must survive the process
 * that formed it.
 *
 * These tests are as much about what the queue refuses to do. A queue that
 * retries writes is a duplicate-record generator with a schedule, so half of
 * this file exists to prove that writes are never re-dispatched, that a
 * definite refusal is handed to a person rather than retried forever, and that
 * an uncertain write still goes to reconciliation instead of to the queue.
 */
class OutboxTest {

    /**
     * A connector that can be told to lose its connection to the ERP.
     *
     * The reference adapter is always reachable, so testing "the ERP is down"
     * with it would mean testing a branch that cannot happen. This wrapper
     * turns one marker sku into [ErpResult.Unavailable] and delegates
     * everything else, which is exactly the shape a real outage has.
     */
    private class FlakyErpConnector(private val delegate: ErpConnector) : ErpConnector {

        var offline: Boolean = true

        override val id: String get() = delegate.id + "+flaky"

        override fun capabilities(): ErpCapabilities = delegate.capabilities()

        override fun findCustomer(tenantId: String, query: String): ErpResult<List<ErpCustomer>> =
            delegate.findCustomer(tenantId, query)

        override fun checkStock(tenantId: String, sku: String): ErpResult<ErpStock> =
            if (offline) {
                ErpResult.Unavailable("ERP_UNAVAILABLE", retryAfterMillis = 30_000L)
            } else {
                delegate.checkStock(tenantId, sku)
            }

        override fun salesSummary(tenantId: String, period: String): ErpResult<String> =
            delegate.salesSummary(tenantId, period)

        override fun createDraftOrder(
            tenantId: String,
            customerName: String,
            amount: Money,
            itemsSummary: String,
        ): ErpResult<ErpRecord> = delegate.createDraftOrder(tenantId, customerName, amount, itemsSummary)

        override fun cancelOrder(tenantId: String, orderId: String, reason: String): ErpResult<ErpRecord> =
            delegate.cancelOrder(tenantId, orderId, reason)

        override fun createInvoice(tenantId: String, orderId: String): ErpResult<ErpRecord> =
            delegate.createInvoice(tenantId, orderId)

        override fun registerPayment(
            tenantId: String,
            invoiceId: String,
            amount: Money,
        ): ErpResult<ErpRecord> = delegate.registerPayment(tenantId, invoiceId, amount)

        override fun readBack(tenantId: String, model: String, recordId: String): ErpResult<ErpRecord> =
            delegate.readBack(tenantId, model, recordId)

        override fun orderAmount(tenantId: String, orderId: String): ErpResult<Money> =
            delegate.orderAmount(tenantId, orderId)

        override fun candidates(tenantId: String, model: String, limit: Int): List<String> =
            delegate.candidates(tenantId, model, limit)

        override fun customerState(tenantId: String, customerName: String): ErpCustomer? =
            delegate.customerState(tenantId, customerName)

        override fun recentRecords(tenantId: String, model: String, limit: Int): List<ErpRecord> =
            delegate.recentRecords(tenantId, model, limit)

        override fun reachable(): Boolean = !offline && delegate.reachable()
    }

    private fun tempDir(name: String): Path {
        val dir = Files.createTempDirectory("mizan-$name-")
        dir.toFile().deleteOnExit()
        return dir
    }

    private fun entry(
        id: String = "OBX-1",
        tool: ToolName = ToolName.STOCK_AVAILABILITY,
        tenantId: String = "sim-alamal",
        key: String = "idem-1",
        now: Long = 1_790_000_000_000L,
        delay: Long = 0L,
    ) = Outbox.forRetryableRead(
        id = id,
        tenantId = tenantId,
        actorId = "USR-REP",
        tool = tool,
        executionId = "EXE-" + id,
        idempotencyKey = key,
        arguments = """{"sku":"SKU-DESK-01"}""",
        nowMillis = now,
        delayMillis = delay,
    )

    /**
     * A service whose clock the test owns.
     *
     * The queue is allowed to wait before its next attempt (the ERP can ask for
     * that), and a test cannot sit still for thirty seconds. Owning the clock
     * is what makes "not due yet" and "now due" provable rather than assumed.
     */
    private class Harness(
        val service: MizanService,
        val connector: FlakyErpConnector,
        val user: ServiceUser,
        val port: Int,
        private val now: AtomicLong,
    ) {
        val nowMillis: Long get() = now.get()

        fun advance(millis: Long) {
            now.addAndGet(millis)
        }
    }

    private fun harness(name: String, sweepMillis: Long = 0L): Harness {
        val flaky = FlakyErpConnector(InMemoryErpConnector(InMemoryErp()))
        val now = AtomicLong(1_790_000_000_000L)
        val service = MizanService(
            ServiceConfig(
                storeDirectory = tempDir(name),
                connector = flaky,
                versionedPolicy = app.mizan.domain.policy.VersionedPolicy.demoV12,
                // Tests drain the queue by hand with runOnce, so the timer is
                // off unless a test is about the timer itself.
                outboxSweepMillis = sweepMillis,
            ),
            clock = { now.get() },
        )
        val port = service.start(port = 0)
        val user = ReferenceDeployment.demoUsers().first { it.actorId == "USR-REP" }
        return Harness(service, flaky, user, port, now)
    }

    private fun health(port: Int): String {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/health")).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body()
    }

    private fun waitUntil(timeoutMillis: Long = 3_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20L)
        }
        return condition()
    }

    private fun readRequest(
        executionId: String = "EXE-READ-1",
        sku: String = "SKU-DESK-01",
        key: String = "read-key-1",
    ) = ExecutionRequest(
        executionId = executionId,
        traceId = "TRC-" + executionId,
        tenantId = "sim-alamal",
        toolWire = "stock.availability",
        toolVersion = "1.0.0",
        proposalId = null,
        arguments = Json.parseOrNull("""{"sku":"$sku"}""") as JsonValue.Obj,
        approverId = null,
        idempotencyKey = key,
    )

    // ----------------------------------------------------------------- queue

    @Test
    fun anEntrySurvivesARestart() {
        val file = tempDir("outbox").resolve("outbox.log")
        val log = DurableLog(file)
        val outbox = Outbox(log)
        outbox.enqueue(entry(id = "OBX-1", key = "idem-1"))
        outbox.enqueue(entry(id = "OBX-2", key = "idem-2"))
        log.close()

        val reopened = Outbox(DurableLog(file))
        assertEquals(2, reopened.size())
        assertEquals(2, reopened.pending().size)
        assertEquals(ToolName.STOCK_AVAILABILITY, reopened.get("OBX-1")?.tool)
        assertEquals("idem-2", reopened.get("OBX-2")?.idempotencyKey)
    }

    @Test
    fun aNewEntryCannotClaimToHaveBeenAttempted() {
        assertThrows(IllegalArgumentException::class.java) {
            val outbox = Outbox(DurableLog(tempDir("outbox-guard").resolve("outbox.log")))
            outbox.enqueue(entry().copy(attempts = 3))
        }
    }

    @Test
    fun onlyEntriesThatAreDueAreClaimed() {
        val outbox = Outbox(DurableLog(tempDir("outbox-due").resolve("outbox.log")))
        val now = 1_790_000_000_000L
        outbox.enqueue(entry(id = "OBX-NOW", key = "k1", now = now))
        outbox.enqueue(entry(id = "OBX-LATER", key = "k2", now = now, delay = 60_000L))

        assertEquals(listOf("OBX-NOW"), outbox.due(now).map { it.id })
        assertEquals(1, outbox.due(now, limit = 1).size)
        assertEquals(listOf("OBX-NOW", "OBX-LATER"), outbox.due(now + 60_000L).map { it.id })
    }

    @Test
    fun aFailureBacksOffAndEventuallyAsksAPerson() {
        val file = tempDir("outbox-backoff").resolve("outbox.log")
        val log = DurableLog(file)
        val outbox = Outbox(log)
        val now = 1_790_000_000_000L
        outbox.enqueue(entry(now = now))

        val first = outbox.markFailed("OBX-1", "ERP_UNAVAILABLE", now)!!
        assertEquals(1, first.attempts)
        assertEquals(Outbox.State.PENDING, first.state)
        assertTrue("the second attempt is not immediate", first.nextAttemptAtMillis > now)
        assertEquals(Outbox.backoffMillis(1), first.nextAttemptAtMillis - now)

        var current = first
        repeat(Outbox.MAX_ATTEMPTS - 1) { index ->
            current = outbox.markFailed("OBX-1", "ERP_UNAVAILABLE", now + index * 1000L)!!
        }
        assertEquals(Outbox.State.DEAD_LETTERED, current.state)
        assertEquals(Outbox.MAX_ATTEMPTS, current.attempts)
        assertEquals(1, outbox.deadLettered().size)
        // A dead-lettered entry is no longer offered to a worker.
        assertEquals(0, outbox.due(now + 10_000_000L).size)
    }

    @Test
    fun backoffGrowsAndIsCapped() {
        assertTrue(Outbox.backoffMillis(2) > Outbox.backoffMillis(1))
        assertTrue(Outbox.backoffMillis(3) > Outbox.backoffMillis(2))
        assertEquals(Outbox.BACKOFF_CEILING.toMillis(), Outbox.backoffMillis(50))
    }

    @Test
    fun compactionDropsFinishedWorkAndKeepsTheRest() {
        val file = tempDir("outbox-compact").resolve("outbox.log")
        val log = DurableLog(file)
        val outbox = Outbox(log)
        outbox.enqueue(entry(id = "OBX-1", key = "k1"))
        outbox.enqueue(entry(id = "OBX-2", key = "k2"))
        outbox.markDone("OBX-1")
        assertEquals(2, outbox.size())

        outbox.compact()
        log.close()
        val reopened = Outbox(DurableLog(file))
        assertEquals(1, reopened.size())
        assertNull(reopened.get("OBX-1"))
        assertNotNull(reopened.get("OBX-2"))
    }

    // ---------------------------------------------------------------- worker

    @Test
    fun aPassMarksSuccessAndKnowsWhatItDid() {
        val outbox = Outbox(DurableLog(tempDir("worker-done").resolve("outbox.log")))
        outbox.enqueue(entry(id = "OBX-1", key = "k1"))
        val worker = OutboxWorker(outbox, clock = { 1_790_000_000_000L })

        val pass = worker.runOnce { OutboxWorker.Dispatch.Done }
        assertTrue(pass.ran)
        assertEquals(1, pass.done)
        assertEquals(0, pass.retried)
        assertEquals(Outbox.State.DONE, outbox.get("OBX-1")?.state)
    }

    @Test
    fun aThrowingDispatcherDoesNotStrandTheEntry() {
        val outbox = Outbox(DurableLog(tempDir("worker-throw").resolve("outbox.log")))
        outbox.enqueue(entry(id = "OBX-1", key = "k1"))
        val worker = OutboxWorker(outbox, clock = { 1_790_000_000_000L })

        val pass = worker.runOnce { error("the ERP adapter threw") }
        assertEquals(1, pass.retried)
        // The entry is claimable again, not stuck in flight.
        val after = outbox.get("OBX-1")!!
        assertEquals(Outbox.State.PENDING, after.state)
        assertEquals("OUTBOX_DISPATCH_ERROR", after.lastErrorCode)
    }

    @Test
    fun aStopDoesNotConsumeTheRetryBudget() {
        val outbox = Outbox(DurableLog(tempDir("worker-stop").resolve("outbox.log")))
        outbox.enqueue(entry(id = "OBX-1", key = "k1"))
        val worker = OutboxWorker(outbox, clock = { 1_790_000_000_000L })

        val pass = worker.runOnce { OutboxWorker.Dispatch.Stop("OUTBOX_WRITE_NOT_RETRYABLE") }
        assertEquals(1, pass.stopped)
        assertEquals(Outbox.State.DEAD_LETTERED, outbox.get("OBX-1")?.state)
        assertEquals(1, outbox.get("OBX-1")?.attempts)
    }

    @Test
    fun aWorkerThatDiedLeavesWorkThatCanBeRecovered() {
        val outbox = Outbox(DurableLog(tempDir("worker-died").resolve("outbox.log")))
        outbox.enqueue(entry(id = "OBX-1", key = "k1"))
        outbox.markInFlight("OBX-1")
        // Nothing is offered to a worker: an in-flight entry is not pending.
        assertEquals(0, outbox.due(1_790_000_000_000L).size)

        val worker = OutboxWorker(outbox, clock = { 1_790_000_000_000L })
        assertEquals(1, worker.recoverInFlight())
        assertEquals(Outbox.State.PENDING, outbox.get("OBX-1")?.state)
        assertEquals(1, outbox.due(1_790_000_000_000L).size)
        assertEquals(0, worker.recoverInFlight())
    }

    @Test
    fun aPassIsBoundedSoOneBadBatchCannotRunForever() {
        val outbox = Outbox(DurableLog(tempDir("worker-limit").resolve("outbox.log")))
        repeat(30) { index ->
            outbox.enqueue(entry(id = "OBX-$index", key = "k$index", now = 1_790_000_000_000L))
        }
        val seen = ArrayList<String>()
        val worker = OutboxWorker(outbox, clock = { 1_790_000_000_000L })
        val pass = worker.runOnce(limit = 5) {
            seen += it.id
            OutboxWorker.Dispatch.Done
        }
        assertEquals(5, pass.attempted)
        assertEquals(5, seen.size)
        assertEquals(25, outbox.pending().size)
    }

    // ------------------------------------------------------- through the service

    @Test
    fun aReadTheErpCannotAnswerIsQueuedUnderTheRealActor() {
        val harness = harness("service-outbox")
        try {
            val outcome = harness.service.authority.decide(readRequest(), harness.user, false)
            assertTrue(outcome is ExecutionOutcome.Failed)
            assertEquals("ERP_UNAVAILABLE", (outcome as ExecutionOutcome.Failed).messageCode)

            val queued = harness.service.stores!!.outbox.pending()
            assertEquals(1, queued.size)
            val waiting = queued.single()
            assertEquals("EXE-READ-1", waiting.executionId)
            assertEquals("sim-alamal", waiting.tenantId)
            // The entry carries the person who asked, not a placeholder: a
            // retry has to run with the same permissions the first try had.
            assertEquals("USR-REP", waiting.actorId)
            assertEquals("read-key-1", waiting.idempotencyKey)
            assertEquals(ToolName.STOCK_AVAILABILITY, waiting.tool)
            // The ERP said "ask again in thirty seconds", and the entry carries
            // that instruction instead of ignoring it.
            assertEquals(30_000L, waiting.nextAttemptAtMillis - waiting.createdAtMillis)
        } finally {
            harness.service.stop()
        }
    }

    @Test
    fun aClientRetryingTheSameReadDoesNotMultiplyQueueEntries() {
        val harness = harness("service-outbox-dup")
        try {
            harness.service.authority.decide(readRequest(), harness.user, false)
            harness.service.authority.decide(readRequest(), harness.user, false)
            harness.service.authority.decide(readRequest(), harness.user, false)
            assertEquals(1, harness.service.stores!!.outbox.size())
        } finally {
            harness.service.stop()
        }
    }

    @Test
    fun theWorkerAnswersAQueuedReadOnceTheErpIsBack() {
        val harness = harness("service-outbox-back")
        try {
            harness.service.authority.decide(readRequest(), harness.user, false)
            assertEquals(1, harness.service.stores!!.outbox.pending().size)

            harness.connector.offline = false
            // The ERP asked for thirty seconds before the next attempt, so a
            // pass run immediately does nothing at all.
            val tooEarly = harness.service.outbox!!.runOnce(users = ReferenceDeployment.demoUsers())
            assertFalse(tooEarly.ran)

            harness.advance(31_000L)
            val pass = harness.service.outbox!!.runOnce(users = ReferenceDeployment.demoUsers())
            assertEquals("pass=$pass", 1, pass.done)
            assertEquals(0, pass.retried)
            assertEquals(0, harness.service.stores!!.outbox.pending().size)

            // The retry really read the ERP: the journal says which model
            // answered, and it is stamped with the read's own id.
            // The retry really read the ERP, and the durable journal says so:
            // the read-back record and the stage are recorded, not erased by
            // whatever wrote to the journal last.
            val journal = harness.service.stores!!.journals.get("EXE-READ-1")
            assertNotNull(journal)
            assertEquals("stock.quant", journal!!.erpModel)
            assertEquals("SKU-DESK-01", journal.erpRecordId)
            assertEquals(app.mizan.domain.execution.JournalStage.ACCEPTED, journal.stage)
        } finally {
            harness.service.stop()
        }
    }

    @Test
    fun aReadThatFailedCanBeAskedAgainUnderItsOwnKey() {
        val harness = harness("service-outbox-again")
        try {
            val request = readRequest()
            val first = harness.service.authority.decide(request, harness.user, false)
            assertTrue(first is ExecutionOutcome.Failed)
            assertEquals("ERP_UNAVAILABLE", (first as ExecutionOutcome.Failed).messageCode)

            // The ERP is back. The same person asks the same thing again with
            // the same key, and the key is not a burnt one: a read that failed
            // while the ERP was down must be repeatable.
            harness.connector.offline = false
            val second = harness.service.authority.decide(request, harness.user, false)
            assertTrue(
                "a definite failure must not consume the key forever, was $second",
                second is ExecutionOutcome.Accepted,
            )
        } finally {
            harness.service.stop()
        }
    }

    @Test
    fun aReadTheErpDefinitelyRefusedIsNotQueued() {
        val harness = harness("service-outbox-refused")
        try {
            harness.connector.offline = false
            // The ERP answered, and its answer was "no such sku". That is a
            // failure a person sees; a queue that retried it would hammer the
            // ERP with a question whose answer is already known.
            val outcome = harness.service.authority.decide(
                readRequest(executionId = "EXE-MISSING", sku = "SKU-NOT-THERE", key = "key-missing"),
                harness.user,
                false,
            )
            assertTrue(outcome is ExecutionOutcome.Failed)
            assertEquals("STOCK_NOT_FOUND", (outcome as ExecutionOutcome.Failed).messageCode)
            assertEquals(0, harness.service.stores!!.outbox.size())
        } finally {
            harness.service.stop()
        }
    }

    @Test
    fun aKeyNamesARequestNotAnAttempt() {
        val harness = harness("service-outbox-key")
        try {
            harness.connector.offline = false
            val first = harness.service.authority.decide(
                readRequest(sku = "SKU-NOT-THERE", key = "shared-key"),
                harness.user,
                false,
            )
            assertTrue(first is ExecutionOutcome.Failed)

            // Same key, different question. That is a client bug or an attack,
            // not a retry, and it is refused rather than answered twice.
            val other = harness.service.authority.decide(
                readRequest(executionId = "EXE-OTHER", sku = "SKU-DESK-01", key = "shared-key"),
                harness.user,
                false,
            )
            assertTrue(other is ExecutionOutcome.Rejected)
            assertEquals("IDEMPOTENCY_KEY_REUSE", (other as ExecutionOutcome.Rejected).messageCode)
        } finally {
            harness.service.stop()
        }
    }

    @Test
    fun theWorkerRefusesToRedispatchAWrite() {
        val harness = harness("service-outbox-write")
        try {
            // Something put a write in the queue: a migration, or a bug. The
            // worker must stop, not execute it.
            harness.service.stores!!.outbox.enqueue(
                Outbox.forRetryableRead(
                    id = "OBX-WRITE",
                    tenantId = "sim-alamal",
                    actorId = "USR-REP",
                    tool = ToolName.CREATE_DRAFT_ORDER,
                    executionId = "EXE-OBX-WRITE",
                    idempotencyKey = "obx-write-key",
                    arguments = """{"amountMinor":"250000","currency":"USD"}""",
                    nowMillis = harness.nowMillis,
                ),
            )
            val pass = harness.service.outbox!!.runOnce(users = ReferenceDeployment.demoUsers())
            assertEquals(1, pass.stopped)
            assertEquals(1, pass.attempted)
            assertEquals(0, pass.done)
            assertEquals(
                "OUTBOX_WRITE_NOT_RETRYABLE",
                harness.service.outbox!!.deadLettered().single().lastErrorCode,
            )
            // Nothing reached the ERP.
            assertTrue(harness.service.erp.snapshot("sim-alamal").orders.isEmpty())
        } finally {
            harness.service.stop()
        }
    }

    @Test
    fun theWorkerStopsAnEntryWhoseActorNoLongerExists() {
        val harness = harness("service-outbox-actor")
        try {
            harness.service.stores!!.outbox.enqueue(
                Outbox.forRetryableRead(
                    id = "OBX-GHOST",
                    tenantId = "sim-alamal",
                    actorId = "USR-DELETED",
                    tool = ToolName.STOCK_AVAILABILITY,
                    executionId = "EXE-OBX-GHOST",
                    idempotencyKey = "obx-ghost-key",
                    arguments = """{"sku":"SKU-DESK-01"}""",
                    nowMillis = harness.nowMillis,
                ),
            )
            val pass = harness.service.outbox!!.runOnce(users = ReferenceDeployment.demoUsers())
            assertEquals(1, pass.stopped)
            assertEquals(
                "OUTBOX_ACTOR_UNKNOWN",
                harness.service.outbox!!.deadLettered().single().lastErrorCode,
            )
        } finally {
            harness.service.stop()
        }
    }

    @Test
    fun aQueuedReadRunsWithTheQueueAsItsTraceNotTheClientSession() {
        val harness = harness("service-outbox-trace")
        try {
            harness.service.stores!!.outbox.enqueue(
                Outbox.forRetryableRead(
                    id = "OBX-TRACE",
                    tenantId = "sim-alamal",
                    actorId = "USR-REP",
                    tool = ToolName.STOCK_AVAILABILITY,
                    executionId = "EXE-OBX-TRACE",
                    idempotencyKey = "obx-trace-key",
                    arguments = """{"sku":"SKU-DESK-01"}""",
                    nowMillis = harness.nowMillis,
                ),
            )
            harness.connector.offline = false
            val pass = harness.service.outbox!!.runOnce(users = ReferenceDeployment.demoUsers())
            assertEquals("pass=$pass", 1, pass.done)
            assertEquals("outbox-OBX-TRACE", harness.service.stores!!.journals.get("EXE-OBX-TRACE")!!.traceId)
        } finally {
            harness.service.stop()
        }
    }

    @Test
    fun anUncertainWriteIsStillBlockedUnderItsKeyAndIsNeverQueued() {
        val harness = harness("service-outbox-ambiguous")
        try {
            val manager = ReferenceDeployment.demoUsers().first { it.actorId == "USR-MGR" }
            val request = ExecutionRequest(
                executionId = "EXE-AMB-1",
                traceId = "TRC-AMB-1",
                tenantId = "sim-alamal",
                toolWire = "sales.order.create_draft",
                toolVersion = "2.1.0",
                proposalId = null,
                arguments = Json.parseOrNull(
                    """{"amountMinor":"250000","currency":"USD","customerName":"Acme Corp","itemsSummary":"1 desk"}""",
                ) as JsonValue.Obj,
                approverId = manager.actorId,
                idempotencyKey = "ambiguous-key-1",
            )
            val first = harness.service.authority.decide(request, harness.user, simulateAmbiguous = true)
            assertTrue(first is ExecutionOutcome.Ambiguous)

            // The same key again: blocked, not retried, and not queued. An
            // uncertain write belongs to reconciliation, not to a scheduler.
            val again = harness.service.authority.decide(
                request.copy(executionId = "EXE-AMB-2"),
                harness.user,
                simulateAmbiguous = false,
            )
            assertTrue(again is ExecutionOutcome.Rejected)
            assertEquals("EXECUTION_ALREADY_AMBIGUOUS", (again as ExecutionOutcome.Rejected).messageCode)
            assertEquals(0, harness.service.stores!!.outbox.size())
        } finally {
            harness.service.stop()
        }
    }

    @Test
    fun aRefusalThatWillNotChangeStopsTheEntryInsteadOfRetryingIt() {
        val harness = harness("service-outbox-final")
        try {
            // The queued read is malformed: it names no sku. Asking the ERP
            // again will not fix that, so the entry is dead-lettered with the
            // refusal's own code rather than retried five times.
            harness.service.stores!!.outbox.enqueue(
                Outbox.forRetryableRead(
                    id = "OBX-FINAL",
                    tenantId = "sim-alamal",
                    actorId = "USR-REP",
                    tool = ToolName.STOCK_AVAILABILITY,
                    executionId = "EXE-OBX-FINAL",
                    idempotencyKey = "obx-final-key",
                    arguments = """{"period":"current"}""",
                    nowMillis = harness.nowMillis,
                ),
            )
            val pass = harness.service.outbox!!.runOnce(users = ReferenceDeployment.demoUsers())
            assertEquals(1, pass.stopped)
            assertEquals(0, pass.retried)
            assertEquals("MISSING_SKU", harness.service.outbox!!.deadLettered().single().lastErrorCode)
        } finally {
            harness.service.stop()
        }
    }

    // ------------------------------------------------------------- the sweeper

    @Test
    fun aRunningServiceDrainsItsOwnQueue() {
        val harness = harness("service-outbox-sweep", sweepMillis = 50L)
        try {
            harness.connector.offline = false
            harness.service.stores!!.outbox.enqueue(
                Outbox.forRetryableRead(
                    id = "OBX-SWEEP",
                    tenantId = "sim-alamal",
                    actorId = "USR-REP",
                    tool = ToolName.STOCK_AVAILABILITY,
                    executionId = "EXE-OBX-SWEEP",
                    idempotencyKey = "obx-sweep-key",
                    arguments = """{"sku":"SKU-DESK-01"}""",
                    nowMillis = harness.nowMillis,
                ),
            )
            // Nobody calls runOnce: the service the operator started is the
            // thing that has to drain it.
            //
            // An entry leaves PENDING before it is finished -- it is claimed
            // as IN_FLIGHT while it runs -- so waiting for an empty pending
            // queue would be waiting for the wrong thing. The work is done
            // when the journal says so, and the queue is empty after that.
            assertTrue(
                "the sweeper did not dispatch the entry",
                waitUntil { harness.service.stores!!.journals.get("EXE-OBX-SWEEP") != null },
            )
            assertTrue(
                "the sweeper left the entry in the queue",
                waitUntil {
                    harness.service.stores!!.outbox.all().none {
                        it.state == Outbox.State.PENDING || it.state == Outbox.State.IN_FLIGHT
                    }
                },
            )
        } finally {
            harness.service.stop()
        }
    }

    @Test
    fun aDeploymentCanTurnTheSweeperOffWithoutLosingTheQueue() {
        val harness = harness("service-outbox-nosweep", sweepMillis = 0L)
        try {
            harness.connector.offline = false
            harness.service.stores!!.outbox.enqueue(
                Outbox.forRetryableRead(
                    id = "OBX-NOSWEEP",
                    tenantId = "sim-alamal",
                    actorId = "USR-REP",
                    tool = ToolName.STOCK_AVAILABILITY,
                    executionId = "EXE-OBX-NOSWEEP",
                    idempotencyKey = "obx-nosweep-key",
                    arguments = """{"sku":"SKU-DESK-01"}""",
                    nowMillis = harness.nowMillis,
                ),
            )
            Thread.sleep(200L)
            // Two workers on one queue race for the same entries, so turning
            // the built-in sweeper off has to be enough to stop it.
            assertEquals(1, harness.service.stores!!.outbox.pending().size)
        } finally {
            harness.service.stop()
        }
    }

    @Test
    fun healthReportsWhatTheQueueIsHolding() {
        val harness = harness("service-outbox-health")
        try {
            harness.service.stores!!.outbox.enqueue(
                Outbox.forRetryableRead(
                    id = "OBX-HEALTH",
                    tenantId = "sim-alamal",
                    actorId = "USR-REP",
                    tool = ToolName.STOCK_AVAILABILITY,
                    executionId = "EXE-OBX-HEALTH",
                    idempotencyKey = "obx-health-key",
                    arguments = """{"sku":"SKU-DESK-01"}""",
                    nowMillis = harness.nowMillis,
                ),
            )
            val body = Json.parseOrNull(health(harness.port))?.asObject()
            assertNotNull(body)
            assertEquals(true, body!!.flag("durable"))
            val outbox = body.field("outbox") as JsonValue.Obj
            assertEquals(true, outbox.flag("durable"))
            assertEquals(1L, (outbox.field("pending") as JsonValue.Num).raw.toLong())
            assertEquals(0L, (outbox.field("deadLettered") as JsonValue.Num).raw.toLong())
        } finally {
            harness.service.stop()
        }
    }
}
