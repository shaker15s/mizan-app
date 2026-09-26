package app.mizan.service

import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.receipt.AuthorityKeyPair
import app.mizan.domain.receipt.ReceiptClaims
import app.mizan.domain.receipt.ReceiptExpectation
import app.mizan.domain.receipt.ReceiptInspector
import app.mizan.domain.receipt.ReceiptSigner
import app.mizan.domain.receipt.ReceiptTrust
import app.mizan.domain.receipt.ReceiptVerdict
import app.mizan.domain.receipt.SignedReceipt
import app.mizan.service.json.Json
import app.mizan.service.json.asObject
import app.mizan.service.json.field
import app.mizan.service.json.text
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
 * Whether a receipt is a proof, or only a sentence.
 *
 * The plan's gate is "the server signs a receipt and the device verifies it".
 * The second half of that sentence is the hard half: a signature the device
 * cannot check -- an HMAC, whose verification requires the same secret that
 * mints receipts -- is not a proof, it is a promise that the phone can forge.
 * This file runs a service that signs with a real Ed25519 authority key,
 * fetches the receipt over real HTTP, rebuilds it from the bytes the device
 * would receive, and verifies it with nothing but the published public key.
 *
 * The JSON is parsed here the way the client parses it, so a field renamed on
 * the service side fails this test rather than silently disarming the app.
 */
class ReceiptTrustTest {

    data class Reply(val status: Int, val body: String) {
        fun text(name: String): String? = Json.parseOrNull(body)?.asObject()?.text(name)
        fun field(name: String) = Json.parseOrNull(body)?.asObject()?.field(name)
    }

    companion object {
        private lateinit var storeDir: Path
        private lateinit var service: MizanService
        private lateinit var base: String
        private lateinit var client: HttpClient
        private lateinit var authorityKey: AuthorityKeyPair

        /**
         * The fields a device needs: every signed field, plus what tells it
         * which key to check the signature against and whether the authority
         * itself considers the receipt good.
         */
        val RECEIPT_FIELDS = (app.mizan.domain.receipt.ReceiptWire.SIGNED_FIELDS +
            listOf("signature", "algorithm", "keyId", "verdict", "messageCode", "authorityVerified", "verified"))
            .sorted()

        @JvmStatic
        @BeforeClass
        fun start() {
            storeDir = Files.createTempDirectory("mizan-receipts-")
            storeDir.toFile().deleteOnExit()
            authorityKey = AuthorityKeyPair.generate("k-ed25519-test")
            service = MizanService(
                ServiceConfig(
                    storeDirectory = storeDir,
                    receiptKeyPair = authorityKey,
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

        fun send(path: String, method: String, body: String? = null, token: String? = null): Reply {
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
            return Reply(response.statusCode(), response.body())
        }

        fun repToken(): String {
            val reply = send(
                MizanContract.PATH_SESSIONS,
                "POST",
                """{"email":"rep@mizan.test","password":"rep-demo-password"}""",
            )
            assertEquals(reply.body, 200, reply.status)
            return reply.text("token") ?: error("no session: ${reply.body}")
        }

        /** A small order: the rep confirms it and the service can verify it. */
        fun smallOrder() = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            """{"tool":"sales.order.create_draft","toolVersion":"2.1.0","tenantId":"sim-alamal",""" +
                """"approverId":"USR-REP","arguments":{"amountMinor":"50000","currency":"USD",""" +
                """"customerName":"Receipt Trust","itemsSummary":"2 keyboards"}}""",
            token = repToken(),
        )
    }

    /** A JSON object seen through the reader the domain contract defines. */
    private class JsonSource(private val json: app.mizan.service.json.JsonValue.Obj) :
        app.mizan.domain.receipt.ReceiptFieldSource {

        override fun text(name: String): String? = json.text(name)

        override fun list(name: String): List<String> =
            (json.field(name) as? app.mizan.service.json.JsonValue.Arr)
                ?.items?.mapNotNull { (it as? app.mizan.service.json.JsonValue.Str)?.value }.orEmpty()

        override fun number(name: String): Long? =
            (json.field(name) as? app.mizan.service.json.JsonValue.Num)?.raw?.toLongOrNull()
    }

    /** Rebuilds the receipt from the wire, exactly as a device must. */
    private fun receiptOf(body: String): SignedReceipt {
        val json = Json.parse(body).asObject() ?: error("receipt body is not JSON")
        val reply = Reply(200, body)
        return app.mizan.domain.receipt.ReceiptWire.signedReceiptOf(
            source = JsonSource(json),
            algorithm = reply.text("algorithm"),
            keyId = reply.text("keyId"),
            signature = reply.text("signature"),
        ) ?: error("the receipt must be rebuildable from the fields it publishes")
    }

    private fun verifiedExecution(): Reply {
        val write = smallOrder()
        assertEquals(write.body, 200, write.status)
        assertEquals(write.body, MizanContract.Status.VERIFIED, write.text("status"))
        return write
    }

    @Test
    fun theVerifiedAnswerCarriesTheAlgorithmThatSignedIt() {
        val write = verifiedExecution()
        assertEquals("Ed25519", write.text("receiptAlgorithm"))
        assertEquals("k-ed25519-test", write.text("receiptKeyId"))
        assertNotNull(write.text("receiptSignature"))
        assertNotNull(write.text("receiptId"))
    }

    @Test
    fun theServicePublishesThePublicHalfAndOnlyThePublicHalf() {
        val caps = send(MizanContract.PATH_CAPABILITIES, "GET", token = repToken())
        assertEquals(200, caps.status)
        assertEquals("Ed25519", caps.text("receiptAlgorithm"))
        val keys = caps.field("receiptKeys") as? app.mizan.service.json.JsonValue.Arr ?: error("no keys")
        assertEquals(1, keys.items.size)
        val key = keys.items.first() as app.mizan.service.json.JsonValue.Obj
        assertEquals("k-ed25519-test", key.text("keyId"))
        assertEquals("Ed25519", key.text("algorithm"))
        assertFalse(key.text("retired") == "true")
        val published = key.text("publicKey") ?: error("no public key")
        assertTrue("a public key, not a private one", ReceiptSigner.parseAuthorityPublicKey(published) != null)
    }

    @Test
    fun aDeviceWithOnlyThePinnedPublicKeyCanProveTheWriteHappened() {
        val write = verifiedExecution()
        val receiptId = write.text("receiptId") ?: error("no receipt id")
        val fetched = send(MizanContract.PATH_RECEIPTS + "/" + receiptId, "GET", token = repToken())
        assertEquals(fetched.body, 200, fetched.status)
        assertEquals("Ed25519", fetched.text("algorithm"))
        val signed = receiptOf(fetched.body)
        val claims = signed.claims
        val pinned = listOf(authorityKey.publicKey())
        val inspection = ReceiptInspector.inspect(
            signed,
            pinned,
            expected = ReceiptExpectation(
                tenantId = "sim-alamal",
                executionId = write.text("executionId"),
                proposalFingerprint = claims.proposalFingerprint,
                erpRecordId = write.text("erpRecordId"),
            ),
        )
        assertEquals(inspection.reasonCode, ReceiptTrust.VERIFIED, inspection.trust)
        assertTrue(inspection.proven)
        // And the service's own verifier agrees, using the private half it holds.
        assertEquals(ReceiptVerdict.VALID, ReceiptSigner(listOf(authorityKey)).verify(signed).verdict)
    }

    @Test
    fun anEditedReceiptIsRefusedEvenThoughItStillReadsPerfectly() {
        val write = verifiedExecution()
        val receiptId = write.text("receiptId") ?: error("no receipt id")
        val fetched = send(MizanContract.PATH_RECEIPTS + "/" + receiptId, "GET", token = repToken())
        val signed = receiptOf(fetched.body)
        val rewritten = signed.copy(claims = signed.claims.copy(erpRecordId = "SO-999999"))
        val inspection = ReceiptInspector.inspect(rewritten, listOf(authorityKey.publicKey()))
        assertEquals(ReceiptTrust.SIGNATURE_MISMATCH, inspection.trust)
        assertFalse(inspection.proven)
    }

    @Test
    fun theReceiptBodyCarriesExactlyTheFieldsAReaderExpects() {
        val write = verifiedExecution()
        val receiptId = write.text("receiptId") ?: error("no receipt id")
        val fetched = send(MizanContract.PATH_RECEIPTS + "/" + receiptId, "GET", token = repToken())
        val fields = (Json.parse(fetched.body).asObject() ?: error("not JSON")).fields.keys.sorted()
        assertEquals(RECEIPT_FIELDS, fields)
    }

    @Test
    fun everyFieldTheSignatureCoversIsAPartOfWhatTheReceiptSays() {
        val write = verifiedExecution()
        val receiptId = write.text("receiptId") ?: error("no receipt id")
        val fetched = send(MizanContract.PATH_RECEIPTS + "/" + receiptId, "GET", token = repToken())
        val source = JsonSource(Json.parse(fetched.body).asObject() ?: error("not JSON"))
        val claims = app.mizan.domain.receipt.ReceiptWire.claimsOf(source)
            ?: error("a published receipt must rebuild")
        // The rebuilt body must be the signed body, field for field. If it is
        // not, no device anywhere can verify this receipt, however correct
        // the service's own verdict says it is.
        assertEquals(
            app.mizan.domain.receipt.ReceiptWire.SIGNED_FIELDS.sorted(),
            claims.canonical().let { canonical ->
                (canonical as app.mizan.domain.model.CanonicalValue.Obj)
                    .fields.map { it.first }.sorted()
            },
        )
        assertEquals("k-ed25519-test", fetched.text("keyId"))
    }
}

/**
 * The same question asked of a deployment that signs with a shared secret.
 *
 * It is a legitimate configuration -- an auditor holding the secret can check
 * the receipt -- and it must never be presented to a phone as a proof it can
 * verify for itself.
 */
class HmacReceiptTest {

    @Test
    fun aSharedSecretReceiptIsReportedAsUnverifiableRatherThanVerified() {
        val signer = ReceiptSigner(listOf(ReceiptSigner.demoKey("hmac-secret")))
        val authorityKey = AuthorityKeyPair.generate("k-ed25519")
        val ed25519 = ReceiptSigner(listOf(authorityKey))

        val hmacReceipt = signer.sign(HmacReceiptTest.sampleClaims())
        assertEquals("HMAC-SHA256", hmacReceipt.algorithm)
        // The service can check it: it holds the secret.
        assertEquals(ReceiptVerdict.VALID, signer.verify(hmacReceipt).verdict)
        // The device cannot, and says so instead of showing a tick.
        val inspection = ReceiptInspector.inspect(hmacReceipt, ed25519.publicKeys())
        assertEquals(ReceiptTrust.UNVERIFIABLE_SHARED_SECRET, inspection.trust)
        assertFalse(inspection.proven)
        assertEquals("RECEIPT_HMAC_NOT_DEVICE_VERIFIABLE", inspection.reasonCode)
    }

    companion object {
        fun sampleClaims() = ReceiptClaims(
            receiptId = "RCT-HMAC-1",
            executionId = "EXE-HMAC-1",
            tenantId = "sim-alamal",
            actorId = "USR-REP",
            approverIds = listOf("USR-MGR"),
            proposalFingerprint = "fp-hmac",
            tool = "sales.order.create_draft",
            toolVersion = "2.1.0",
            catalogVersion = "tools-1",
            policyVersionId = "v1",
            policyHash = "hash-1",
            approvalLevel = ApprovalLevel.L2_PRIVILEGED,
            inputHash = "input-1",
            erpModel = "sale.order",
            erpRecordId = "SO-2001",
            verificationHash = "verification-1",
            verifiedFields = listOf("amount_total", "state"),
            issuedAtMillis = 1_790_000_000_000L,
            traceId = "TRC-HMAC-1",
        )
    }
}
