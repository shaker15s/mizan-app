package app.mizan.service

import app.mizan.domain.execution.JournalStage
import app.mizan.domain.policy.VersionedPolicy
import app.mizan.service.authority.ReferenceDeployment
import app.mizan.service.json.Json
import app.mizan.service.json.JsonValue
import app.mizan.service.protocol.ExecutionOutcome
import app.mizan.service.protocol.ExecutionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * What the service knows about an execution must not depend on the process
 * that performed it.
 *
 * These tests kill the service the only way a test can -- close the store,
 * build a new service over the same directory -- and then ask the *second*
 * process what happened. Two questions are being answered, and they are the
 * two the plan's production gates name: does the journal still describe the
 * execution that ran, and does the same idempotency key produce the same
 * answer without touching the ERP a second time.
 */
class JournalDurabilityTest {

    private fun tempDir(name: String): Path {
        val dir = Files.createTempDirectory("mizan-$name-")
        dir.toFile().deleteOnExit()
        return dir
    }

    private fun service(directory: Path) = MizanService(
        ServiceConfig(
            storeDirectory = directory,
            signingSecret = "journal-durability-key",
            versionedPolicy = VersionedPolicy.demoV12,
        ),
    )

    private fun readRequest(executionId: String, key: String = "jd-read-key") = ExecutionRequest(
        executionId = executionId,
        traceId = "TRC-" + executionId,
        tenantId = "sim-alamal",
        toolWire = "stock.availability",
        toolVersion = "1.0.0",
        proposalId = null,
        arguments = Json.parseOrNull("""{"sku":"SKU-DESK-01"}""") as JsonValue.Obj,
        approverId = null,
        idempotencyKey = key,
    )

    private fun writeRequest(executionId: String, key: String = "jd-write-key") = ExecutionRequest(
        executionId = executionId,
        traceId = "TRC-" + executionId,
        tenantId = "sim-alamal",
        toolWire = "sales.order.create_draft",
        toolVersion = "2.1.0",
        proposalId = null,
        arguments = Json.parseOrNull(
            """{"amountMinor":"250000","currency":"USD","customerName":"Acme Corp","itemsSummary":"1 desk"}""",
        ) as JsonValue.Obj,
        approverId = "USR-MGR",
        idempotencyKey = key,
    )

    @Test
    fun aReadsRecordIsStillThereAfterARestart() {
        val directory = tempDir("journal-read")
        val user = ReferenceDeployment.demoUsers().first { it.actorId == "USR-REP" }
        val first = service(directory)
        try {
            val outcome = first.authority.decide(readRequest("EXE-JD-READ"), user, false)
            assertTrue(outcome is ExecutionOutcome.Accepted)
        } finally {
            first.stop()
        }

        val second = service(directory)
        try {
            val journal = second.stores!!.journals.get("EXE-JD-READ")
            assertNotNull("the journal did not survive the restart", journal)
            assertEquals(JournalStage.ACCEPTED, journal!!.stage)
            assertEquals("stock.quant", journal.erpModel)
            assertEquals("SKU-DESK-01", journal.erpRecordId)
            assertEquals("USR-REP", journal.actorId.value)
            // The journal of a read is a fact about the read, not about the
            // process: a read is never reported as verified.
            assertTrue(journal.candidateIds.isEmpty())
        } finally {
            second.stop()
        }
    }

    @Test
    fun aVerifiedWritesProofIsStillThereAfterARestart() {
        val directory = tempDir("journal-write")
        val user = ReferenceDeployment.demoUsers().first { it.actorId == "USR-REP" }
        val first = service(directory)
        val recordId: String
        val receiptId: String
        try {
            val outcome = first.authority.decide(writeRequest("EXE-JD-WRITE"), user, false)
            assertTrue("the write must be verified by reading it back, was $outcome", outcome is ExecutionOutcome.Verified)
            val verified = outcome as ExecutionOutcome.Verified
            assertEquals("sale.order", verified.erpModel)
            assertTrue(
                "a verified write names the fields the read-back matched",
                verified.verifiedFields.isNotEmpty(),
            )
            recordId = verified.erpRecordId
            receiptId = verified.receiptId ?: error("a signed deployment must produce a receipt")
        } finally {
            first.stop()
        }

        val second = service(directory)
        try {
            val journal = second.stores!!.journals.get("EXE-JD-WRITE")
            assertNotNull(journal)
            assertEquals(JournalStage.VERIFIED, journal!!.stage)
            assertEquals("sale.order", journal.erpModel)
            assertEquals(recordId, journal.erpRecordId)
            // The receipt that proves it is durable too, and still verifiable.
            val receipt = second.stores!!.receipts.forExecution("EXE-JD-WRITE")
            assertNotNull(receipt)
            assertEquals(receiptId, receipt!!.claims.receiptId)
            assertEquals(recordId, receipt.claims.erpRecordId)
        } finally {
            second.stop()
        }
    }

    @Test
    fun theSameKeyReplaysTheSameAnswerWithoutTouchingTheErpAgain() {
        val directory = tempDir("journal-replay")
        val user = ReferenceDeployment.demoUsers().first { it.actorId == "USR-REP" }
        val request = writeRequest("EXE-JD-REPLAY")
        val first = service(directory)
        try {
            val outcome = first.authority.decide(request, user, false)
            assertTrue(outcome is ExecutionOutcome.Verified)
        } finally {
            first.stop()
        }

        val second = service(directory)
        try {
            // The second process has an empty ERP: its reference adapter has
            // never been written to. A replay that really came from the durable
            // answer must still report the verified write.
            assertTrue(second.erp.snapshot("sim-alamal").orders.isEmpty())
            val replayed = second.authority.decide(request.copy(executionId = "EXE-JD-REPLAY-2"), user, false)
            assertTrue("a durable key must replay, was $replayed", replayed is ExecutionOutcome.Verified)
            val verified = replayed as ExecutionOutcome.Verified
            assertEquals("sale.order", verified.erpModel)
            assertNotNull("the replay must carry the receipt, not a bare tick", verified.receiptId)
            // Nothing was written a second time.
            assertTrue(second.erp.snapshot("sim-alamal").orders.isEmpty())
        } finally {
            second.stop()
        }
    }

    @Test
    fun aFailedReadIsReEvaluatedRatherThanBlockedAfterARestart() {
        val directory = tempDir("journal-failed")
        val user = ReferenceDeployment.demoUsers().first { it.actorId == "USR-REP" }
        val request = readRequest("EXE-JD-MISSING", key = "jd-missing-key").let {
            it.copy(arguments = Json.parseOrNull("""{"sku":"SKU-NOT-THERE"}""") as JsonValue.Obj)
        }
        val first = service(directory)
        try {
            assertTrue(first.authority.decide(request, user, false) is ExecutionOutcome.Failed)
        } finally {
            first.stop()
        }

        val second = service(directory)
        try {
            assertEquals(JournalStage.FAILED, second.stores!!.journals.get("EXE-JD-MISSING")!!.stage)
            // The same request again. A definite failure is an answer, not a
            // lock: the second process re-evaluates it instead of replaying a
            // failure forever or refusing the key.
            val retried = second.authority.decide(request.copy(executionId = "EXE-JD-MISSING-2"), user, false)
            assertTrue("a failed read must be re-evaluated, was $retried", retried is ExecutionOutcome.Failed)
            assertEquals("STOCK_NOT_FOUND", (retried as ExecutionOutcome.Failed).messageCode)
            assertEquals(JournalStage.FAILED, second.stores!!.journals.get("EXE-JD-MISSING-2")!!.stage)
        } finally {
            second.stop()
        }
    }
}
