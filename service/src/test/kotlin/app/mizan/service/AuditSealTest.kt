package app.mizan.service

import app.mizan.domain.audit.AuditEvent
import app.mizan.domain.audit.AuditHasher
import app.mizan.domain.audit.AuditSealer
import app.mizan.domain.audit.ChainVerifier
import app.mizan.domain.audit.IntegrityClass
import app.mizan.domain.receipt.AuthorityKeyPair
import app.mizan.domain.receipt.ReceiptSigner
import app.mizan.service.json.Json
import app.mizan.service.json.asObject
import app.mizan.service.json.field
import app.mizan.service.json.flag
import app.mizan.service.json.text
import app.mizan.service.ledger.AuditLedger
import app.mizan.service.protocol.MizanContract
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Who wrote the audit trail, and how anyone could tell if it were rewritten.
 *
 * A hash chain proves the rows in front of you hash together. It does not prove
 * the authority wrote them: an attacker with write access to the table can edit
 * a row, recompute every hash after it, and hand over a chain that verifies
 * perfectly. Sealing each row with a key the writer does not hold is what turns
 * the trail into evidence, and this file rewrites the trail in both ways to
 * show which one is caught.
 */
class AuditSealTest {

    private val authorityKey = AuthorityKeyPair.generate("k-audit")

    private fun ledgerOf(key: AuthorityKeyPair? = authorityKey): AuditLedger = AuditLedger(
        clock = { 1_790_000_000_000L },
        sealer = AuditSealer.of(key),
    )

    private fun chain(sealer: Boolean = true): List<AuditEvent> {
        val ledger = ledgerOf(if (sealer) authorityKey else null)
        ledger.append("sim-alamal", "TRC-1", "USR-REP", "PROPOSED", "NEW", "PROPOSED", "order 250 USD")
        ledger.append("sim-alamal", "TRC-1", "USR-MGR", "AUTHORIZED", "PROPOSED", "AUTHORIZED", "approval APR-1")
        ledger.append("sim-alamal", "TRC-1", "USR-REP", "VERIFIED", "AUTHORIZED", "VERIFIED", "SO-1001")
        return ledger.events("sim-alamal")
    }

    @Test
    fun aSealedChainVerifiesAndSaysItIsSealed() {
        val events = chain()
        val report = ChainVerifier(AuditSealer(ReceiptSigner(listOf(authorityKey)))).verify(events)
        assertTrue(report.intact)
        assertEquals("CHAIN_SEALED_OK", report.messageCode)
        assertEquals(3, report.records)
        assertEquals(3, report.sealedRecords)
        assertEquals(3, report.verifiedSeals)
        assertTrue(report.fullySealed)
        assertTrue(events.all { it.seal?.keyId == "k-audit" })
        assertTrue(events.all { it.seal?.algorithm == "Ed25519" })
    }

    @Test
    fun editingARowBreaksBothTheChainAndItsSeal() {
        val events = chain().toMutableList()
        val target = events[1]
        events[1] = target.copy(details = "approval APR-1 (approved by nobody)")
        // The hash no longer matches the row, and the seal no longer matches
        // the hash: whichever check runs first, the edit is visible.
        val report = ChainVerifier(AuditSealer(ReceiptSigner(listOf(authorityKey)))).verify(events)
        assertFalse(report.intact)
        assertEquals("CHAIN_PAYLOAD_MISMATCH", report.messageCode)
        assertEquals(2L, report.brokenIndex)
    }

    @Test
    fun aChainRewrittenConsistentlyIsStillCaughtByTheSeal() {
        // The attack a hash chain cannot stop: change a row and recompute
        // every hash after it. The chain is then perfectly self-consistent.
        val events = chain()
        val edited = events[1].copy(details = "approval APR-1 (approved by nobody)")
        val touched = listOf(events[0], edited).plus(events.drop(2))
        var previous = AuditHasher.GENESIS
        val rehashed = touched.map { event ->
            val relinked = event.copy(previousHash = previous, currentHash = "")
            val rewritten = relinked.copy(currentHash = AuditHasher.hash(relinked, previous))
            previous = rewritten.currentHash
            rewritten
        }
        val chainOnly = ChainVerifier().verify(rehashed)
        assertTrue("a rewritten chain is internally consistent", chainOnly.intact)

        val sealedCheck = ChainVerifier(AuditSealer(ReceiptSigner(listOf(authorityKey)))).verify(rehashed)
        assertFalse("the seal is what catches it", sealedCheck.intact)
        assertEquals("CHAIN_SEAL_MISMATCH", sealedCheck.messageCode)
    }

    @Test
    fun aRowAddedWithoutASealIsNotQuietlyAccepted() {
        val events = chain().toMutableList()
        val last = events.last()
        // A row appended by something that does not hold the key.
        val injected = last.copy(
            chainIndex = last.chainIndex + 1,
            previousHash = last.currentHash,
            currentHash = "",
            details = "SO-9999",
            seal = null,
            integrityClass = IntegrityClass.SERVER_AUTHORED,
        )
        val sealed = injected.copy(currentHash = AuditHasher.hash(injected, last.currentHash))
        events.add(sealed)
        val report = ChainVerifier(AuditSealer(ReceiptSigner(listOf(authorityKey)))).verify(events)
        assertFalse(report.intact)
        assertEquals("CHAIN_NOT_SEALED", report.messageCode)
        assertEquals("the missing seal is the reason", 4L, report.brokenIndex)
    }

    @Test
    fun anotherKeysChainIsNotThisDeploymentsChain() {
        val otherKey = AuthorityKeyPair.generate("k-other")
        val events = ledgerOf(otherKey).also {
            it.append("sim-alamal", "TRC-9", "USR-REP", "PROPOSED", "NEW", "PROPOSED", "elsewhere")
        }.events("sim-alamal")
        val report = ChainVerifier(AuditSealer(ReceiptSigner(listOf(authorityKey)))).verify(events)
        assertFalse("a key that is not ours cannot sign for us", report.intact)
        assertEquals("CHAIN_SEAL_MISMATCH", report.messageCode)
    }

    @Test
    fun aDeploymentWithoutAKeySaysTheChainIsUnsealedRatherThanProven() {
        val events = chain(sealer = false)
        val open = ChainVerifier().verify(events)
        assertTrue(open.intact)
        assertEquals(0, open.sealedRecords)
        assertFalse("intact is not sealed", open.fullySealed)
        // And a deployment that does have a key refuses to accept those rows.
        val strict = ChainVerifier(AuditSealer(ReceiptSigner(listOf(authorityKey)))).verify(events)
        assertFalse(strict.intact)
        assertEquals("CHAIN_NOT_SEALED", strict.messageCode)
    }
}

/**
 * The same question asked over HTTP: does the audit endpoint tell the truth
 * about what it verified?
 */
class AuditSealHttpTest {

    companion object {
        private lateinit var storeDir: Path
        private lateinit var service: MizanService
        private lateinit var base: String
        private lateinit var client: HttpClient

        @JvmStatic
        @BeforeClass
        fun start() {
            storeDir = Files.createTempDirectory("mizan-audit-seal-")
            storeDir.toFile().deleteOnExit()
            service = MizanService(
                ServiceConfig(
                    storeDirectory = storeDir,
                    receiptKeyPair = AuthorityKeyPair.generate("k-audit-http"),
                    sessionTtlMillis = 30 * 60 * 1000L,
                ),
            )
            val port = service.start(port = 0)
            base = "http://127.0.0.1:$port"
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        }

        @JvmStatic
        @AfterClass
        fun stop() {
            service.stop()
        }

        fun send(path: String, method: String, body: String? = null, token: String? = null): Pair<Int, String> {
            val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
            if (token != null) builder.header(MizanContract.HEADER_AUTHORIZATION, "Bearer $token")
            val publisher = if (body == null) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                HttpRequest.BodyPublishers.ofString(body)
            }
            builder.method(method, publisher)
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            return response.statusCode() to response.body()
        }

        fun repToken(): String {
            val (status, body) = send(
                MizanContract.PATH_SESSIONS,
                "POST",
                """{"email":"rep@mizan.test","password":"rep-demo-password"}""",
            )
            assertEquals(body, 200, status)
            return Json.parse(body).asObject()?.text("token") ?: error("no token")
        }
    }

    private fun number(document: app.mizan.service.json.JsonValue.Obj, name: String): Long =
        (document.field(name) as? app.mizan.service.json.JsonValue.Num)?.raw?.toLongOrNull() ?: -1L

    @Test
    fun theAuditEndpointReportsTheSealItVerifiedAndTheKeyThatMadeIt() {
        val token = repToken()
        // A governed execution first, so the trail has rows worth sealing.
        val (writeStatus, writeBody) = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            """{"tool":"sales.order.create_draft","toolVersion":"2.1.0","tenantId":"sim-alamal",""" +
                """"approverId":"USR-REP","arguments":{"amountMinor":"50000","currency":"USD",""" +
                """"customerName":"Seal Test","itemsSummary":"1 seat"}}""",
            token = token,
        )
        assertEquals(writeBody, 200, writeStatus)

        val (status, body) = send(MizanContract.PATH_AUDIT, "GET", token = token)
        assertEquals(body, 200, status)
        val document = Json.parse(body).asObject() ?: error("audit body must be JSON")
        assertEquals(true, document.flag("chainIntact"))
        assertEquals(true, document.flag("sealed"))
        assertEquals("k-audit-http", document.text("sealKeyId"))
        assertEquals("CHAIN_SEALED_OK", document.text("messageCode"))
        // These are numbers in the body, not strings: reading them as text
        // would report zero sealed rows on a fully sealed chain.
        val records = number(document, "records")
        assertTrue("the trail has rows after a governed write", records > 0)
        assertEquals(records, number(document, "sealedRecords"))
        assertEquals(records, number(document, "verifiedSeals"))
        assertTrue(body.contains("\"sealAlgorithm\":\"Ed25519\""))
    }
}
