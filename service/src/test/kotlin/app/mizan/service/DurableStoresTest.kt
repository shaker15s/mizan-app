package app.mizan.service

import app.mizan.domain.error.DispatchState
import app.mizan.domain.execution.ExecutionJournal
import app.mizan.domain.execution.JournalStage
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.approval.ApprovalState
import app.mizan.domain.approval.ApprovalPolicy
import app.mizan.domain.approval.ApprovalRequest
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.IdempotencyKey
import app.mizan.domain.model.ProposalId
import app.mizan.domain.model.ReconciliationCase
import app.mizan.domain.model.ReconciliationStatus
import app.mizan.domain.model.RiskTier
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.ToolName
import app.mizan.domain.model.TraceId
import app.mizan.domain.receipt.ReceiptClaims
import app.mizan.domain.receipt.ReceiptSigner
import app.mizan.domain.receipt.ReceiptVerdict
import app.mizan.domain.security.ApprovalChallenge
import app.mizan.domain.security.DeviceKeyAlgorithm
import app.mizan.domain.security.DevicePublicKey
import app.mizan.service.store.DurableLog
import app.mizan.service.store.IdempotencyStore
import app.mizan.service.store.ServiceStores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A service that forgets is not a service. These tests kill the process the
 * polite way -- close the log, throw it away, open it again -- and check that
 * the journal, the idempotency index and the receipts are still there.
 *
 * The interesting cases are the impolite ones: a machine that died mid-append
 * leaves a half-written frame at the end of the file. The log must keep every
 * complete record before it and drop only the torn tail, because the
 * alternative is either losing an approval or replaying a write.
 */
class DurableStoresTest {

    private fun tempDir(name: String): Path {
        val dir = Files.createTempDirectory("mizan-$name-")
        dir.toFile().deleteOnExit()
        return dir
    }

    private fun sampleJournal(
        executionId: String = "EXE-0001",
        tenantId: String = "sim-alamal",
        key: String = "idem-0001",
        stage: JournalStage = JournalStage.AUTHORIZED,
    ): ExecutionJournal = ExecutionJournal(
        executionId = ExecutionId(executionId),
        tenantId = TenantId(tenantId),
        actorId = ActorId("USR-REP"),
        proposalId = ProposalId("PRP-0001"),
        proposalFingerprint = "fingerprint-0001",
        tool = ToolName.CREATE_DRAFT_ORDER,
        toolVersion = "2.1.0",
        schemaVersion = "2.1.0.s1",
        catalogVersion = app.mizan.domain.tool.ToolCatalog.VERSION,
        canonicalInputHash = "input-hash-0001",
        idempotencyKey = IdempotencyKey(key),
        policyVersionId = "12",
        policyHash = "policy-hash-0001",
        approvalId = null,
        approvalFingerprint = null,
        proofReference = null,
        stage = stage,
        riskTier = RiskTier.R2_MEDIUM,
        approvalLevel = ApprovalLevel.L2_PRIVILEGED,
        dispatch = DispatchState.NOT_SENT,
        dispatchStartedAt = null,
        dispatchFinishedAt = null,
        responseReceivedAt = null,
        verificationStartedAt = null,
        verificationFinishedAt = null,
        erpModel = null,
        erpRecordId = null,
        candidateIds = emptyList(),
        errorCode = null,
        traceId = "TRC-0001",
        revision = 3L,
        createdAt = Instant.parse("2026-09-26T09:00:00Z"),
        updatedAt = Instant.parse("2026-09-26T09:05:00Z"),
    )

    // -------------------------------------------------------------- the raw log

    @Test
    fun recordsSurviveCloseAndReopen() {
        val file = tempDir("log").resolve("journal.log")
        val log = DurableLog(file)
        log.append("""{"n":1}""")
        log.append("""{"n":2}""")
        log.close()

        val reopened = DurableLog(file)
        assertEquals(listOf("""{"n":1}""", """{"n":2}"""), reopened.records())
        assertEquals("""{"n":1}""", reopened.get(0))
        assertNull(reopened.get(7))
        reopened.close()
    }

    @Test
    fun anEmptyFileIsCreatedWithItsMagic() {
        val file = tempDir("magic").resolve("fresh.log")
        val log = DurableLog(file)
        assertEquals(emptyList<String>(), log.records())
        log.close()
        assertTrue(Files.readString(file).startsWith(DurableLog.MAGIC))
    }

    @Test
    fun aFileThatIsNotAJournallIsRefused() {
        val file = tempDir("foreign").resolve("other.log")
        Files.writeString(file, "this is somebody else's file\n")
        assertThrows(IllegalStateException::class.java) { DurableLog(file) }
    }

    @Test
    fun aTornTailIsDroppedAndTheGoodPrefixKept() {
        val file = tempDir("torn").resolve("journal.log")
        val log = DurableLog(file)
        log.append("""{"n":1}""")
        log.append("""{"n":2}""")
        log.append("""{"n":3}""")
        log.close()

        // Simulate a process that died mid-append: cut the file a few bytes
        // into the third frame. Nothing after the cut was ever acked.
        val bytes = Files.readAllBytes(file)
        Files.write(file, bytes.copyOf(bytes.size - 6))

        val recovered = DurableLog(file)
        assertEquals(listOf("""{"n":1}""", """{"n":2}"""), recovered.records())
        // The truncated tail is gone from the file too, not merely hidden.
        recovered.append("""{"n":4}""")
        recovered.close()
        val again = DurableLog(file)
        assertEquals(listOf("""{"n":1}""", """{"n":2}""", """{"n":4}"""), again.records())
        again.close()
    }

    @Test
    fun aCorruptedRecordIsDroppedRatherThanDecodedAsGarbage() {
        val file = tempDir("corrupt").resolve("journal.log")
        val log = DurableLog(file)
        log.append("""{"n":1}""")
        log.append("""{"n":2}""")
        log.close()

        val bytes = Files.readAllBytes(file)
        // Flip a byte inside the second record's payload: its CRC no longer
        // matches, so the record cannot be trusted and must not be returned.
        val target = bytes.size - 3
        bytes[target] = (bytes[target].toInt() xor 0x20).toByte()
        Files.write(file, bytes)

        val recovered = DurableLog(file)
        assertEquals(listOf("""{"n":1}"""), recovered.records())
        recovered.close()
    }

    @Test
    fun compactionKeepsTheLatestStateAndDropsTheHistory() {
        val file = tempDir("compact").resolve("journal.log")
        val log = DurableLog(file)
        log.append("""{"n":1}""")
        log.append("""{"n":2}""")
        log.append("""{"n":3}""")
        log.compact(listOf("""{"n":3}"""))
        log.close()

        val reopened = DurableLog(file)
        assertEquals(listOf("""{"n":3}"""), reopened.records())
        reopened.close()
        assertFalse(Files.exists(file.resolveSibling("journal.log.compact")))
    }

    @Test
    fun appendsFromSeveralThreadsAreAllPersisted() {
        val file = tempDir("threads").resolve("journal.log")
        val log = DurableLog(file)
        val threads = 8
        val perThread = 25
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) { thread ->
            pool.submit {
                start.await()
                repeat(perThread) { index -> log.append("""{"t":$thread,"i":$index}""") }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        log.close()

        assertEquals(threads * perThread, DurableLog(file).also { it.close() }.records().size.let { size ->
            DurableLog(file).records().size
        })
    }

    // --------------------------------------------------------- typed stores

    @Test
    fun theJournalIsIndexedByExecutionAndByKey() {
        val dir = tempDir("journal")
        ServiceStores(dir).use { stores ->
            stores.journals.save(sampleJournal(executionId = "EXE-0001", key = "idem-0001"))
            stores.journals.save(sampleJournal(executionId = "EXE-0002", key = "idem-0002"))
            assertEquals(2, stores.journals.count())
            assertEquals("EXE-0001", stores.journals.get("EXE-0001")?.executionId?.value)
            assertEquals(
                "EXE-0002",
                stores.journals.byIdempotency("sim-alamal", "idem-0002")?.executionId?.value,
            )
            // Another tenant with the same key is a different execution.
            assertNull(stores.journals.byIdempotency("other-tenant", "idem-0002"))
            assertEquals(2, stores.journals.forTenant("sim-alamal").size)
            assertEquals(0, stores.journals.forTenant("other-tenant").size)
        }
    }

    @Test
    fun theJournalSurvivesARestart() {
        val dir = tempDir("journal-restart")
        ServiceStores(dir).use { stores ->
            stores.journals.save(sampleJournal(stage = JournalStage.VERIFIED))
        }
        ServiceStores(dir).use { stores ->
            val entry = stores.journals.get("EXE-0001")
            assertNotNull(entry)
            assertEquals(JournalStage.VERIFIED, entry!!.stage)
            assertEquals(3L, entry.revision)
            assertEquals("policy-hash-0001", entry.policyHash)
            assertEquals(Instant.parse("2026-09-26T09:05:00Z"), entry.updatedAt)
        }
    }

    @Test
    fun anUncertainWriteIsStillAQuestionAfterARestart() {
        val dir = tempDir("journal-attention")
        ServiceStores(dir).use { stores ->
            stores.journals.save(sampleJournal(stage = JournalStage.RECONCILIATION_REQUIRED))
            stores.journals.save(sampleJournal(executionId = "EXE-0003", key = "idem-0003"))
        }
        ServiceStores(dir).use { stores ->
            val waiting = stores.journals.requiringAttention("sim-alamal")
            assertEquals(1, waiting.size)
            assertEquals("EXE-0001", waiting.first().executionId.value)
        }
    }

    @Test
    fun theIdempotencyIndexRemembersWhatAKeyDid() {
        val dir = tempDir("idempotency")
        ServiceStores(dir).use { stores ->
            stores.idempotency.record(
                IdempotencyStore.Entry(
                    tenantId = "sim-alamal",
                    key = "idem-0001",
                    canonicalArguments = """{"amountMinor":"250000"}""",
                    executionId = "EXE-0001",
                    status = "verified",
                    stage = JournalStage.VERIFIED,
                    erpRecordId = "SO-1001",
                    erpModel = "sale.order",
                    messageCode = "VERIFIED_BY_READ_BACK",
                    candidateRecordIds = emptyList(),
                    summary = "SO-1001",
                    recordedAtMillis = 1_790_000_000_000L,
                ),
            )
        }
        ServiceStores(dir).use { stores ->
            val found = stores.idempotency.find("sim-alamal", "idem-0001")
            assertNotNull(found)
            assertEquals("SO-1001", found!!.erpRecordId)
            assertEquals(JournalStage.VERIFIED, found.stage)
            assertNull(stores.idempotency.find("sim-alamal", "never-used"))
            assertNull(stores.idempotency.find("other-tenant", "idem-0001"))
        }
    }

    @Test
    fun compactionShrinksTheIdempotencyLogWithoutLosingTheLatest() {
        val dir = tempDir("idempotency-compact")
        ServiceStores(dir).use { stores ->
            repeat(6) { index ->
                stores.idempotency.record(
                    IdempotencyStore.Entry(
                        tenantId = "sim-alamal",
                        key = "idem-$index",
                        canonicalArguments = "{}",
                        executionId = "EXE-$index",
                        status = "accepted",
                        stage = JournalStage.ACCEPTED,
                        erpRecordId = null,
                        erpModel = null,
                        messageCode = "READ_RESULT",
                        candidateRecordIds = emptyList(),
                        summary = null,
                        recordedAtMillis = 1_790_000_000_000L + index,
                    ),
                )
            }
            stores.idempotency.maybeCompact(maxRecords = 2)
        }
        ServiceStores(dir).use { stores ->
            assertEquals(6, stores.idempotency.size())
            assertEquals("EXE-5", stores.idempotency.find("sim-alamal", "idem-5")?.executionId)
        }
    }

    @Test
    fun aReceiptKeepsItsSignatureAcrossARestart() {
        val dir = tempDir("receipts")
        val signer = ReceiptSigner(listOf(ReceiptSigner.demoKey("restart-test")))
        val claims = ReceiptClaims(
            receiptId = "RCT-0001",
            executionId = "EXE-0001",
            tenantId = "sim-alamal",
            actorId = "USR-REP",
            approverIds = listOf("USR-MGR"),
            proposalFingerprint = "fingerprint-0001",
            tool = "sales.order.create_draft",
            toolVersion = "2.1.0",
            catalogVersion = app.mizan.domain.tool.ToolCatalog.VERSION,
            policyVersionId = "12",
            policyHash = "policy-hash-0001",
            approvalLevel = ApprovalLevel.L2_PRIVILEGED,
            inputHash = "input-hash-0001",
            erpModel = "sale.order",
            erpRecordId = "SO-1001",
            verificationHash = "verification-hash-0001",
            verifiedFields = listOf("amountMinor", "currency"),
            issuedAtMillis = 1_790_000_000_000L,
            traceId = "TRC-0001",
        )
        ServiceStores(dir).use { stores -> stores.receipts.save(signer.sign(claims)) }
        ServiceStores(dir).use { stores ->
            val stored = stores.receipts.get("RCT-0001")
            assertNotNull(stored)
            assertEquals(ReceiptVerdict.VALID, signer.verify(stored!!).verdict)
            assertEquals("SO-1001", stores.receipts.forExecution("EXE-0001")?.claims?.erpRecordId)
            assertEquals(listOf("amountMinor", "currency"), stored.claims.verifiedFields)
        }
    }

    @Test
    fun devicesAndChallengesSurviveARestart() {
        val dir = tempDir("devices")
        ServiceStores(dir).use { stores ->
            stores.devices.save(
                DevicePublicKey(
                    deviceId = "DEV-1",
                    tenantId = TenantId("sim-alamal"),
                    actorId = ActorId("USR-REP"),
                    algorithm = DeviceKeyAlgorithm.ED25519,
                    publicKeyBase64 = "cHVibGljLWtleQ==",
                    label = "Pixel",
                    enrolledAtMillis = 1_790_000_000_000L,
                ),
            )
            stores.challenges.save(
                ApprovalChallenge(
                    challengeId = "CHG-1",
                    nonce = "nonce-1",
                    deviceId = "DEV-1",
                    tenantId = TenantId("sim-alamal"),
                    actorId = ActorId("USR-REP"),
                    executionId = ExecutionId("EXE-1"),
                    proposalFingerprint = "fingerprint-0001",
                    issuedAtMillis = 1_790_000_000_000L,
                    expiresAtMillis = 1_790_000_120_000L,
                ),
            )
        }
        ServiceStores(dir).use { stores ->
            val device = stores.devices.find("DEV-1")
            assertNotNull(device)
            assertEquals("Pixel", device!!.label)
            assertEquals(1, stores.devices.byTenant(TenantId("sim-alamal")).size)
            val challenge = stores.challenges.find("CHG-1")
            assertNotNull(challenge)
            assertNull(challenge!!.consumedAtMillis)
            assertEquals("nonce-1", challenge.nonce)
        }
    }

    @Test
    fun aReconciliationCaseKeepsItsResolutionAcrossARestart() {
        val dir = tempDir("reconciliation")
        ServiceStores(dir).use { stores ->
            val opened = ReconciliationCase(
                id = "REC-0001",
                executionId = ExecutionId("EXE-0001"),
                traceId = TraceId("TRC-0001"),
                tenantId = TenantId("sim-alamal"),
                tool = ToolName.CREATE_DRAFT_ORDER,
                intent = "sales.order.create_draft",
                idempotencyKey = IdempotencyKey("idem-0001"),
                candidateRecordIds = listOf("SO-1001"),
                status = ReconciliationStatus.OPEN,
                notes = "ERP_TIMEOUT",
                openedAt = Instant.parse("2026-09-26T09:00:00Z"),
                reasonCode = "ERP_TIMEOUT",
            )
            stores.reconciliations.upsert(opened)
            stores.reconciliations.upsert(
                opened.resolve(
                    status = ReconciliationStatus.LINKED,
                    actorId = "USR-FIN",
                    atMillis = 1_790_000_100_000L,
                    recordId = "SO-1001",
                    labelKey = "reconciliation_linked",
                ),
            )
        }
        ServiceStores(dir).use { stores ->
            assertEquals(1, stores.reconciliations.size())
            val resolved = stores.reconciliations.get("REC-0001")
            assertNotNull(resolved)
            assertEquals(ReconciliationStatus.LINKED, resolved!!.status)
            assertEquals("USR-FIN", resolved.resolvedByActorId)
            assertEquals("SO-1001", resolved.resolvedRecordId)
            assertFalse(resolved.open)
            assertTrue(stores.reconciliations.open("sim-alamal").isEmpty())
            assertEquals(1, stores.reconciliations.forTenant("sim-alamal").size)
        }
    }

    @Test
    fun theAuditChainIsRestorableAndStillVerifiable() {
        val dir = tempDir("audit")
        val writer = app.mizan.domain.audit.AuditHasher
        ServiceStores(dir).use { stores ->
            val first = stores.audit.append(
                app.mizan.domain.audit.AuditEvent(
                    chainIndex = 1L,
                    timestampMillis = 1_790_000_000_000L,
                    traceId = "TRC-0001",
                    tenantId = "sim-alamal",
                    actorId = "USR-REP",
                    action = "EXECUTION_VERIFIED",
                    stateBefore = "AUTHORIZED",
                    stateAfter = "VERIFIED",
                    details = "SO-1001",
                    previousHash = app.mizan.domain.audit.AuditHasher.GENESIS,
                    currentHash = "",
                    integrityClass = app.mizan.domain.audit.IntegrityClass.SERVER_AUTHORED,
                ).let { it.copy(currentHash = writer.hash(it, app.mizan.domain.audit.AuditHasher.GENESIS)) },
            )
            assertEquals(64, first.currentHash.length)
        }
        ServiceStores(dir).use { stores ->
            val events = stores.audit.forTenant("sim-alamal")
            assertEquals(1, events.size)
            val report = app.mizan.domain.audit.ChainVerifier().verify(events)
            assertTrue(report.intact)
            assertEquals(1, stores.audit.size())
        }
    }

    @Test
    fun approvalsAreDurableAndIndexedByProposal() {
        val dir = tempDir("approvals")
        val approval = ApprovalRequest(
            id = "APR-0001",
            proposalId = "PRP-0001",
            tenantId = TenantId("sim-alamal"),
            initiatorId = ActorId("USR-REP"),
            proposalRevision = 1,
            proposalFingerprint = "fingerprint-0001",
            requiredLevel = ApprovalLevel.L2_PRIVILEGED,
            policyVersionId = "12",
            policyHash = "policy-hash-0001",
            state = ApprovalState.GRANTED,
            createdAtMillis = 1_790_000_000_000L,
            expiresAtMillis = 1_790_014_400_000L,
            approvals = emptyList(),
            decisions = emptyList(),
        )
        ServiceStores(dir).use { stores -> stores.approvals.save(approval) }
        ServiceStores(dir).use { stores ->
            assertEquals("APR-0001", stores.approvals.get("APR-0001")?.id)
            assertEquals("APR-0001", stores.approvals.forProposal("PRP-0001")?.id)
            assertEquals(ApprovalState.GRANTED, stores.approvals.get("APR-0001")?.state)
            assertEquals(1, stores.approvals.forTenant("sim-alamal").size)
            assertTrue(stores.approvals.pending("sim-alamal").isEmpty())
        }
    }

    @Test
    fun aGrantedApprovalCanBeOpenedAndValidatedThroughThePolicy() {
        val policy = ApprovalPolicy()
        val now = 1_790_000_000_000L
        val approval = ApprovalRequest(
            id = "APR-0001",
            proposalId = "PRP-0001",
            tenantId = TenantId("sim-alamal"),
            initiatorId = ActorId("USR-REP"),
            proposalRevision = 1,
            proposalFingerprint = "fingerprint-0001",
            requiredLevel = ApprovalLevel.L2_PRIVILEGED,
            policyVersionId = "12",
            policyHash = "policy-hash-0001",
            state = ApprovalState.PENDING,
            createdAtMillis = now,
            expiresAtMillis = now + 4 * 60 * 60 * 1000L,
            approvals = emptyList(),
            decisions = emptyList(),
        )
        val validation = policy.validate(
            request = approval,
            currentFingerprint = "fingerprint-0001",
            currentRevision = 1,
            currentPolicyVersionId = "12",
            currentPolicyHash = "policy-hash-0001",
            nowMillis = now + 60_000L,
        )
        assertFalse(validation.valid)
        assertEquals(app.mizan.domain.approval.ApprovalVerdict.MISSING, validation.verdict)
    }

    @Test
    fun aCorruptJournalLogLosesOnlyTheCorruptRecord() {
        val dir = tempDir("corrupt-store")
        ServiceStores(dir).use { stores ->
            stores.journals.save(sampleJournal(executionId = "EXE-0001", key = "idem-0001"))
            stores.journals.save(sampleJournal(executionId = "EXE-0002", key = "idem-0002"))
            stores.journals.save(sampleJournal(executionId = "EXE-0003", key = "idem-0003"))
        }
        val file = dir.resolve("execution-journal.log")
        val bytes = Files.readAllBytes(file)
        bytes[bytes.size - 20] = (bytes[bytes.size - 20].toInt() xor 0x11).toByte()
        Files.write(file, bytes)

        ServiceStores(dir).use { stores ->
            // The first two records are intact; the third is not trusted.
            assertNotNull(stores.journals.get("EXE-0001"))
            assertNotNull(stores.journals.get("EXE-0002"))
            assertTrue(stores.journals.count() <= 3)
        }
    }
}
