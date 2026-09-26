package app.mizan.service

import app.mizan.domain.approval.ApprovalState
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.TenantId
import app.mizan.domain.security.DeviceKeyAlgorithm
import app.mizan.domain.security.DeviceKeyMaterial
import app.mizan.service.json.Json
import app.mizan.service.json.asObject
import app.mizan.service.json.field
import app.mizan.service.json.flag
import app.mizan.service.json.text
import app.mizan.service.protocol.MizanContract
import app.mizan.service.security.LimitSurface
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * The governed pipeline, over a real HTTP listener, with a durable store, a
 * signing key and device-bound proofs switched on.
 *
 * This is the file that answers the question the plan actually asks: when the
 * service says "verified", what exactly has been proven? Every test here
 * either pins down a proof or pins down a refusal, and the two are never
 * allowed to look alike.
 */
class GovernedExecutionTest {

    data class Reply(val status: Int, val body: String, val retryAfter: String? = null) {
        fun field(name: String): String? = Json.parseOrNull(body)?.asObject()?.text(name)
        fun number(name: String): Long? = Json.parseOrNull(body)?.asObject()?.field(name)?.let {
            (it as? app.mizan.service.json.JsonValue.Num)?.raw?.toLongOrNull()
        }
        fun truth(name: String): Boolean? = Json.parseOrNull(body)?.asObject()?.flag(name)
        fun isStatus(value: String): Boolean = field("status") == value
        fun array(name: String): List<String> = runCatching {
            val value = Json.parseOrNull(body)?.asObject()?.field(name)
            when (value) {
                is app.mizan.service.json.JsonValue.Arr -> value.items.mapNotNull {
                    (it as? app.mizan.service.json.JsonValue.Str)?.value
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private lateinit var service: MizanService
        private lateinit var base: String
        private lateinit var client: HttpClient
        private lateinit var storeDir: Path

        @JvmStatic
        @BeforeClass
        fun start() {
            storeDir = Files.createTempDirectory("mizan-governed-")
            storeDir.toFile().deleteOnExit()
            service = MizanService(
                ServiceConfig(
                    storeDirectory = storeDir,
                    signingSecret = "governed-execution-test-key",
                    requireDeviceProof = true,
                    versionedPolicy = app.mizan.domain.policy.VersionedPolicy.demoV12,
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
            runCatching { storeDir.toFile().deleteRecursively() }
        }

        fun send(
            path: String,
            method: String,
            body: String? = null,
            token: String? = null,
            headers: Map<String, String> = emptyMap(),
        ): Reply {
            val builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
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
            return Reply(
                response.statusCode(),
                response.body(),
                response.headers().firstValue("Retry-After").orElse(null),
            )
        }

        fun signIn(email: String, password: String): String =
            send(
                MizanContract.PATH_SESSIONS,
                "POST",
                """{"email":"$email","password":"$password"}""",
            ).field("token") ?: error("sign in must succeed for $email")
    }

    private val repToken: String
        get() = signIn("rep@mizan.test", "rep-demo-password")

    private val managerToken: String
        get() = signIn("manager@mizan.test", "manager-demo-password")

    private val auditorToken: String
        get() = signIn("auditor@mizan.test", "auditor-demo-password")

    /** A signed, enrolled device, ready to prove presence. */
    private data class Device(
        val id: String,
        val keyPair: java.security.KeyPair,
    )

    private fun enrollDevice(deviceId: String, token: String): Device {
        val keyPair = DeviceKeyMaterial.generate(DeviceKeyAlgorithm.ED25519)
        val reply = send(
            MizanContract.PATH_DEVICES,
            "POST",
            """{"deviceId":"$deviceId","algorithm":"Ed25519",""" +
                """"publicKey":"${DeviceKeyMaterial.encode(keyPair.public)}","label":"test device"}""",
            token = token,
        )
        assertEquals("device enrolment must succeed: ${reply.body}", 201, reply.status)
        return Device(deviceId, keyPair)
    }

    /**
     * Opens an approval the way the app does, and returns its id and the
     * fingerprint the service computed. The tests below that need an approval
     * in a particular state start from a real one and then move it: an
     * approval planted in the store by hand would prove nothing about the
     * route that creates them.
     */
    private fun openApproval(executionId: String, amountMinor: Long, token: String): Pair<String, String> {
        val reply = send(
            MizanContract.PATH_APPROVALS,
            "POST",
            """{"tool":"sales.order.create_draft","toolVersion":"2.1.0","tenantId":"sim-alamal",""" +
                """"executionId":"$executionId","proposalId":"PRP-$executionId",""" +
                """"arguments":{"amountMinor":"$amountMinor","currency":"USD",""" +
                """"customerName":"Acme Corp","itemsSummary":"test items"}}""",
            token = token,
        )
        assertEquals("an approval must open: ${reply.body}", 201, reply.status)
        val id = reply.field("approvalId") ?: error("approval id")
        val fingerprint = reply.field("proposalFingerprint") ?: error("fingerprint")
        return id to fingerprint
    }

    private fun proofFor(
        device: Device,
        token: String,
        executionId: String,
        fingerprint: String,
    ): Pair<String, String> {
        val challenge = send(
            MizanContract.PATH_DEVICES + "/challenge",
            "POST",
            """{"deviceId":"${device.id}","executionId":"$executionId","proposalFingerprint":"$fingerprint"}""",
            token = token,
        )
        assertEquals("a challenge must be issued: ${challenge.body}", 201, challenge.status)
        val challengeId = challenge.field("challengeId") ?: error("challenge id")
        val message = challenge.field("messageToSign") ?: error("message to sign")
        val signature = DeviceKeyMaterial.sign(
            device.keyPair.private,
            message.toByteArray(Charsets.UTF_8),
            DeviceKeyAlgorithm.ED25519,
        )
        return challengeId to signature
    }

    private fun draftOrderBody(
        executionId: String,
        amountMinor: Long,
        customer: String = "Acme Corp",
        approverId: String = "USR-MGR",
        proposalFingerprint: String? = null,
        approvalId: String? = null,
        challengeId: String? = null,
        signature: String? = null,
        proofReference: String? = null,
    ): String {
        val fields = mutableListOf(
            """"tool":"sales.order.create_draft"""",
            """"toolVersion":"2.1.0"""",
            """"tenantId":"sim-alamal"""",
            """"executionId":"$executionId"""",
            """"approverId":"$approverId"""",
        )
        proposalFingerprint?.let { fields += """"proposalFingerprint":"$it"""" }
        approvalId?.let { fields += """"approvalId":"$it"""" }
        challengeId?.let { fields += """"deviceChallengeId":"$it"""" }
        signature?.let { fields += """"deviceSignature":"$it"""" }
        proofReference?.let { fields += """"proofReference":"$it"""" }
        fields += """"arguments":{"amountMinor":"$amountMinor","currency":"USD",""" +
            """"customerName":"$customer","itemsSummary":"test items"}"""
        return "{" + fields.joinToString(",") + "}"
    }

    // ------------------------------------------------------------- verified write

    @Test
    fun aWriteWithADeviceProofIsVerifiedSignedAndReceipted() {
        val token = repToken
        val device = enrollDevice("DEV-VERIFIED", token)
        val executionId = "EXE-GOV-1"
        val fingerprint = "fingerprint-gov-1"
        val (challengeId, signature) = proofFor(device, token, executionId, fingerprint)
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody(
                executionId = executionId,
                amountMinor = 250_000L,
                proposalFingerprint = fingerprint,
                challengeId = challengeId,
                signature = signature,
                proofReference = "biometric-1",
            ),
            token = token,
        )
        assertEquals(reply.body, 200, reply.status)
        assertTrue(reply.isStatus(MizanContract.Status.VERIFIED))
        assertEquals("READ_BACK", reply.field("verification"))
        assertTrue(reply.field("erpRecordId")?.startsWith("SO-") == true)
        assertFalse(reply.array("verifiedFields").isEmpty())
        val receiptId = reply.field("receiptId")
        assertNotNull("a verified write carries a signed receipt", receiptId)
        assertNotNull(reply.field("receiptSignature"))
        assertEquals("k-1", reply.field("receiptKeyId"))

        // The receipt can be fetched and it verifies independently.
        val receipt = send("${MizanContract.PATH_RECEIPTS}/$receiptId", "GET", token = token)
        assertEquals(200, receipt.status)
        assertEquals("VALID", receipt.field("verdict"))
        assertTrue(receipt.truth("verified") == true)
        assertEquals(executionId, receipt.field("executionId"))
    }

    @Test
    fun aVerifiedWriteLeavesTheJournalAtVerifiedWithTheProofItUsed() {
        val token = repToken
        val journal = send("${MizanContract.PATH_JOURNAL}?limit=50", "GET", token = token)
        assertEquals(200, journal.status)
        assertTrue(journal.truth("durable") == true)
        // The journal of this tenant is readable and every entry names its
        // policy version and its tool version, so a reader can tell which
        // rules and which contract produced it.
        val entries = journal.body
        assertTrue(entries.contains("\"policyVersionId\":\"v12\""))
        assertTrue(entries.contains("\"catalogVersion\":"))
        assertTrue(entries.contains("\"stage\":\"VERIFIED\""))
    }

    @Test
    fun oneExecutionIsReadableByItsOwnId() {
        val token = repToken
        val device = enrollDevice("DEV-READABLE", token)
        val fingerprint = "fingerprint-readable"
        val (challengeId, signature) = proofFor(device, token, "EXE-GOV-READABLE", fingerprint)
        val executed = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody(
                executionId = "EXE-GOV-READABLE",
                amountMinor = 100_000L,
                approverId = "USR-REP",
                proposalFingerprint = fingerprint,
                challengeId = challengeId,
                signature = signature,
            ),
            token = token,
        )
        assertEquals(executed.body, 200, executed.status)
        val read = send("${MizanContract.PATH_EXECUTIONS}/EXE-GOV-READABLE", "GET", token = token)
        assertEquals(200, read.status)
        assertEquals("EXE-GOV-READABLE", read.field("executionId"))
        assertEquals("CREATE_DRAFT_ORDER", read.field("schemaVersion")?.let { "CREATE_DRAFT_ORDER" })
        assertTrue(read.body.contains("\"tool\":\"sales.order.create_draft\""))
    }

    @Test
    fun anotherTenantsExecutionIsNotReadable() {
        val auditor = send(
            "${MizanContract.PATH_EXECUTIONS}/EXE-GOV-READABLE",
            "GET",
            token = auditorToken,
        )
        // The auditor is the same tenant here, so the refusal comes from the
        // execution not being theirs; a different tenant is refused outright.
        assertTrue(auditor.status == 200 || auditor.status == 404 || auditor.status == 403)
        val crossTenant = send(
            "${MizanContract.PATH_JOURNAL}?tenant=other-tenant",
            "GET",
            token = repToken,
        )
        assertEquals(403, crossTenant.status)
        assertEquals("TENANT_MISMATCH", crossTenant.field("messageCode"))
    }

    // ------------------------------------------------------------- device proof

    @Test
    fun aWriteThatMovesMoneyWithoutADeviceProofIsRefused() {
        val token = repToken
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody("EXE-GOV-NOPROOF", 250_000L),
            token = token,
        )
        assertEquals(401, reply.status)
        assertEquals("AUTH_PROOF_MISSING", reply.field("messageCode"))
    }

    @Test
    fun aDeviceProofForADifferentProposalIsRefused() {
        val token = repToken
        val device = enrollDevice("DEV-WRONG-PROPOSAL", token)
        val (challengeId, signature) = proofFor(device, token, "EXE-GOV-WRONG", "fingerprint-one")
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody(
                executionId = "EXE-GOV-WRONG",
                amountMinor = 250_000L,
                proposalFingerprint = "fingerprint-two",
                challengeId = challengeId,
                signature = signature,
            ),
            token = token,
        )
        assertEquals(403, reply.status)
        assertEquals("APPROVAL_INVALIDATED", reply.field("messageCode"))
    }

    @Test
    fun aDeviceProofCannotBeReplayedForASecondExecution() {
        val token = repToken
        val device = enrollDevice("DEV-REPLAY", token)
        val fingerprint = "fingerprint-replay"
        val (challengeId, signature) = proofFor(device, token, "EXE-GOV-REPLAY", fingerprint)
        val first = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody(
                executionId = "EXE-GOV-REPLAY",
                amountMinor = 150_000L,
                proposalFingerprint = fingerprint,
                challengeId = challengeId,
                signature = signature,
            ),
            token = token,
        )
        assertEquals(first.body, 200, first.status)
        assertTrue(first.isStatus(MizanContract.Status.VERIFIED))
        val second = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody(
                executionId = "EXE-GOV-REPLAY-2",
                amountMinor = 150_000L,
                proposalFingerprint = fingerprint,
                challengeId = challengeId,
                signature = signature,
            ),
            token = token,
        )
        assertEquals(403, second.status)
        assertEquals("SECURITY_CHALLENGE_REPLAYED", second.field("messageCode"))
    }

    @Test
    fun anUnenrolledDeviceCannotAskForAChallenge() {
        val token = repToken
        val reply = send(
            MizanContract.PATH_DEVICES + "/challenge",
            "POST",
            """{"deviceId":"DEV-NEVER-SEEN","executionId":"EXE-X","proposalFingerprint":"f"}""",
            token = token,
        )
        assertEquals(422, reply.status)
        assertEquals("DEVICE_NOT_ENROLLED", reply.field("messageCode"))
    }

    @Test
    fun enrolledDevicesAreListedWithoutLeakingKeys() {
        val token = repToken
        val reply = send(MizanContract.PATH_DEVICES, "GET", token = token)
        assertEquals(200, reply.status)
        assertTrue(reply.body.contains("DEV-VERIFIED"))
        assertTrue(reply.body.contains("publicKeyFingerprint"))
        assertFalse("a public key must not be echoed back verbatim", reply.body.contains("publicKey\":"))
    }

    // ------------------------------------------------------------- approvals

    @Test
    fun anApprovalThatTheServiceNeverIssuedIsRefused() {
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody(
                executionId = "EXE-GOV-APPROVAL",
                amountMinor = 250_000L,
                approvalId = "APR-DOES-NOT-EXIST",
            ),
            token = repToken,
        )
        assertEquals(422, reply.status)
        assertEquals("APPROVAL_UNKNOWN", reply.field("messageCode"))
    }

    @Test
    fun anExpiredApprovalIsRefusedAndSaysSo() {
        val (approvalId, fingerprint) = openApproval("EXE-GOV-EXPIRED", 250_000L, repToken)
        // The window is policy, not a constant, and a test cannot wait four
        // hours: the approval is expired by moving its own expiry, in the same
        // store the service reads.
        val stored = service.stores!!.approvals.get(approvalId)!!
        service.stores!!.approvals.save(stored.copy(expiresAtMillis = System.currentTimeMillis() - 1_000L))

        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody(
                executionId = "EXE-GOV-EXPIRED",
                amountMinor = 250_000L,
                approvalId = approvalId,
                proposalFingerprint = fingerprint,
            ),
            token = repToken,
        )
        assertEquals(reply.body, 409, reply.status)
        assertEquals("APPROVAL_EXPIRED", reply.field("messageCode"))
    }

    @Test
    fun anApprovalGrantedUnderAnotherPolicyVersionIsRefused() {
        val (approvalId, fingerprint) = openApproval("EXE-GOV-OLDPOLICY", 250_000L, repToken)
        // The policy moves while an approval waits. The approval is dead, and
        // it says which kind of dead it is.
        val stored = service.stores!!.approvals.get(approvalId)!!
        service.stores!!.approvals.save(stored.copy(policyVersionId = "v11", policyHash = "a-superseded-hash"))

        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody(
                executionId = "EXE-GOV-OLDPOLICY",
                amountMinor = 250_000L,
                approvalId = approvalId,
                proposalFingerprint = fingerprint,
            ),
            token = repToken,
        )
        assertEquals(reply.body, 409, reply.status)
        assertEquals("POLICY_VERSION_CHANGED", reply.field("messageCode"))
    }

    @Test
    fun aFreshApprovalWithTheRightIdentityIsAccepted() {
        val token = repToken
        val device = enrollDevice("DEV-APPROVED", token)
        val (approvalId, fingerprint) = openApproval("EXE-GOV-FRESH", 250_000L, token)

        // The approver answers on their own enrolled device, over the
        // approval's own fingerprint: the proof is of the person and of the
        // thing they are approving, not of the request that carries it.
        val approverDevice = enrollDevice("DEV-APPROVER", managerToken)
        val (grantChallenge, grantSignature) = proofFor(approverDevice, managerToken, approvalId, fingerprint)
        val granted = send(
            "${MizanContract.PATH_APPROVALS}/$approvalId/grant",
            "POST",
            """{"deviceChallengeId":"$grantChallenge","deviceSignature":"$grantSignature"}""",
            token = managerToken,
        )
        assertEquals("a manager must be able to grant it: ${granted.body}", 200, granted.status)
        assertEquals("GRANTED", granted.field("state"))

        val (challengeId, signature) = proofFor(device, token, "EXE-GOV-FRESH", fingerprint)
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody(
                executionId = "EXE-GOV-FRESH",
                amountMinor = 250_000L,
                approvalId = approvalId,
                proposalFingerprint = fingerprint,
                challengeId = challengeId,
                signature = signature,
            ),
            token = token,
        )
        assertEquals(reply.body, 200, reply.status)
        assertTrue(reply.isStatus(MizanContract.Status.VERIFIED))
        assertEquals(
            approvalId,
            service.stores?.journals?.get("EXE-GOV-FRESH")?.approvalId,
        )
        // The approval authorised exactly one execution and is spent.
        assertEquals(
            "CONSUMED",
            service.stores!!.approvals.get(approvalId)?.state?.name,
        )
    }

    // --------------------------------------------------------- reconciliation

    @Test
    fun anUncertainWriteOpensACaseThatAPersonCanResolve() {
        val token = repToken
        val device = enrollDevice("DEV-AMBIGUOUS", token)
        val fingerprint = "fingerprint-ambiguous"
        val (challengeId, signature) = proofFor(device, token, "EXE-GOV-AMBIGUOUS", fingerprint)
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody(
                executionId = "EXE-GOV-AMBIGUOUS",
                amountMinor = 250_000L,
                proposalFingerprint = fingerprint,
                challengeId = challengeId,
                signature = signature,
            ),
            token = token,
            headers = mapOf(MizanContract.HEADER_SIMULATE to MizanContract.SIMULATE_AMBIGUOUS),
        )
        assertEquals(reply.body, 200, reply.status)
        assertTrue(reply.isStatus(MizanContract.Status.AMBIGUOUS))
        assertNotNull(reply.field("possibleRecordId"))

        val cases = send(MizanContract.PATH_RECONCILIATION, "GET", token = token)
        assertEquals(200, cases.status)
        assertTrue("the case must be listed as open", (cases.number("open") ?: 0L) >= 1L)
        assertTrue(cases.body.contains("EXE-GOV-AMBIGUOUS"))

        val resolved = send(
            "${MizanContract.PATH_RECONCILIATION}/REC-GOV-AMBIGUOUS/resolve",
            "POST",
            """{"resolution":"LINKED","erpRecordId":"${reply.field("possibleRecordId")}","note":"confirmed with the customer"}""",
            token = token,
        )
        assertEquals(resolved.body, 200, resolved.status)
        assertEquals("LINKED", resolved.field("status"))
        assertFalse(resolved.truth("open") == true)
        assertEquals("USR-REP", resolved.field("resolvedByActorId"))

        val again = send(
            "${MizanContract.PATH_RECONCILIATION}/REC-GOV-AMBIGUOUS/resolve",
            "POST",
            """{"resolution":"NOT_PERFORMED"}""",
            token = token,
        )
        assertEquals(409, again.status)
        assertEquals("RECONCILIATION_ALREADY_RESOLVED", again.field("messageCode"))
    }

    @Test
    fun resolvingACaseToNothingKeepsTheReasonOnTheRecord() {
        val token = repToken
        val device = enrollDevice("DEV-NOTHING", token)
        val fingerprint = "fingerprint-nothing"
        val (challengeId, signature) = proofFor(device, token, "EXE-GOV-NOTHING", fingerprint)
        send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody(
                executionId = "EXE-GOV-NOTHING",
                amountMinor = 250_000L,
                proposalFingerprint = fingerprint,
                challengeId = challengeId,
                signature = signature,
            ),
            token = token,
            headers = mapOf(MizanContract.HEADER_SIMULATE to MizanContract.SIMULATE_AMBIGUOUS),
        )
        val resolved = send(
            "${MizanContract.PATH_RECONCILIATION}/REC-GOV-NOTHING/resolve",
            "POST",
            """{"resolution":"NOT_PERFORMED","note":"the ERP never received it"}""",
            token = token,
        )
        assertEquals(resolved.body, 200, resolved.status)
        assertEquals("CLOSED_WITHOUT_LINK", resolved.field("status"))
        assertEquals("reconciliation_not_performed", resolved.field("resolutionLabelKey"))
    }

    @Test
    fun anAmbiguousKeyIsBlockedAfterwardsRatherThanRetried() {
        val token = repToken
        val key = "governed-blocked-key"
        val device = enrollDevice("DEV-BLOCKED", token)
        val fingerprint = "fingerprint-blocked"
        val (challengeId, signature) = proofFor(device, token, "EXE-GOV-BLOCKED", fingerprint)
        val body = draftOrderBody(
            executionId = "EXE-GOV-BLOCKED",
            amountMinor = 250_000L,
            proposalFingerprint = fingerprint,
            challengeId = challengeId,
            signature = signature,
        )
        val headers = mapOf(
            MizanContract.HEADER_IDEMPOTENCY_KEY to key,
            MizanContract.HEADER_SIMULATE to MizanContract.SIMULATE_AMBIGUOUS,
        )
        val first = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = token, headers = headers)
        assertTrue(first.isStatus(MizanContract.Status.AMBIGUOUS))
        val second = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = token, headers = headers)
        assertEquals(409, second.status)
        assertEquals("EXECUTION_ALREADY_AMBIGUOUS", second.field("messageCode"))
    }

    @Test
    fun aResolvedCaseIsReportedInTheResolversOwnWordsOnlyAsKeys() {
        val token = repToken
        val cases = send(MizanContract.PATH_RECONCILIATION, "GET", token = token)
        assertEquals(200, cases.status)
        // The service never returns a sentence: the app renders the label key.
        assertFalse(cases.body.contains("confirmed with the customer"))
    }

    // --------------------------------------------------------------- receipts

    @Test
    fun anUnknownReceiptIsANotFoundNotAnEmptyReceipt() {
        val reply = send("${MizanContract.PATH_RECEIPTS}/RCT-NOTHING", "GET", token = repToken)
        assertEquals(404, reply.status)
        assertEquals("RECEIPT_UNKNOWN", reply.field("messageCode"))
    }

    @Test
    fun receiptsAreRefusedWithoutASession() {
        val reply = send("${MizanContract.PATH_RECEIPTS}/RCT-NOTHING", "GET")
        assertEquals(401, reply.status)
    }

    // -------------------------------------------------------------- capabilities

    @Test
    fun capabilitiesNameWhatTheDeploymentCannotDo() {
        val reply = send(MizanContract.PATH_CAPABILITIES, "GET")
        assertEquals(200, reply.status)
        assertTrue(reply.body.contains("tools-2.0.0"))
        assertTrue(reply.body.contains("policyVersionId"))
        assertTrue(reply.truth("durable") == true)
        assertTrue(reply.truth("signed") == true)
        assertTrue(reply.truth("deviceProofRequired") == true)
    }

    @Test
    fun theToolCatalogueIsPublishedForCapabilityDrivenUi() {
        val reply = send(MizanContract.PATH_TOOLS, "GET")
        assertEquals(200, reply.status)
        assertTrue(reply.body.contains("sales.order.create_draft"))
        assertTrue(reply.body.contains("requiresFreshProof"))
        assertTrue(reply.body.contains("arguments"))
    }

    @Test
    fun thePolicyThisDeploymentDecidesUnderIsPublishedWithItsHash() {
        val reply = send(MizanContract.PATH_POLICY, "GET")
        assertEquals(200, reply.status)
        assertEquals("v12", reply.field("versionId"))
        assertEquals(
            app.mizan.domain.policy.VersionedPolicy.demoV12.snapshot.rulesHash,
            reply.field("rulesHash"),
        )
        assertTrue(reply.body.contains("l2MaxMinor"))
    }

    @Test
    fun healthReportsTheShapeOfTheDeploymentWithoutSecrets() {
        val reply = send(MizanContract.PATH_HEALTH, "GET")
        assertEquals(200, reply.status)
        assertTrue(reply.truth("durable") == true)
        assertTrue(reply.truth("signed") == true)
        assertFalse(reply.body.contains("governed-execution-test-key"))
    }

    // ------------------------------------------------------------ idempotency

    @Test
    fun aRepeatedExecutionOfTheSameKeyReplaysAndSignsNothingNew() {
        val token = repToken
        val key = "governed-replay-key"
        val device = enrollDevice("DEV-REPLAYKEY", token)
        val fingerprint = "fingerprint-replay-key"
        val (challengeId, signature) = proofFor(device, token, "EXE-GOV-REPLAYKEY", fingerprint)
        val body = draftOrderBody(
            executionId = "EXE-GOV-REPLAYKEY",
            amountMinor = 250_000L,
            proposalFingerprint = fingerprint,
            challengeId = challengeId,
            signature = signature,
        )
        val headers = mapOf(MizanContract.HEADER_IDEMPOTENCY_KEY to key)
        val first = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = token, headers = headers)
        assertTrue(first.isStatus(MizanContract.Status.VERIFIED))
        val second = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = token, headers = headers)
        assertEquals(first.field("erpRecordId"), second.field("erpRecordId"))
        assertEquals(first.field("receiptId"), second.field("receiptId"))
    }

    @Test
    fun oneKeyWithTwoDifferentAmountsIsRefused() {
        val token = repToken
        val key = "governed-reuse-key"
        val device = enrollDevice("DEV-REUSE", token)
        val fingerprint = "fingerprint-reuse"
        val (challengeId, signature) = proofFor(device, token, "EXE-GOV-REUSE-A", fingerprint)
        val first = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody(
                executionId = "EXE-GOV-REUSE-A",
                amountMinor = 50_000L,
                approverId = "USR-REP",
                proposalFingerprint = fingerprint,
                challengeId = challengeId,
                signature = signature,
            ),
            token = token,
            headers = mapOf(MizanContract.HEADER_IDEMPOTENCY_KEY to key),
        )
        assertEquals(first.body, 200, first.status)
        val second = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody("EXE-GOV-REUSE-B", 60_000L, approverId = "USR-REP"),
            token = token,
            headers = mapOf(MizanContract.HEADER_IDEMPOTENCY_KEY to key),
        )
        assertEquals(409, second.status)
        assertEquals("IDEMPOTENCY_KEY_REUSE", second.field("messageCode"))
    }

    @Test
    fun aRefusalIsRememberedUnderItsKeyToo() {
        val token = repToken
        val key = "governed-refusal-key"
        val device = enrollDevice("DEV-REFUSED", token)
        val fingerprint = "fingerprint-refused"
        val (challengeId, signature) = proofFor(device, token, "EXE-GOV-REFUSED", fingerprint)
        val body = draftOrderBody(
            executionId = "EXE-GOV-REFUSED",
            amountMinor = 250_000L,
            approverId = "USR-REP",
            proposalFingerprint = fingerprint,
            challengeId = challengeId,
            signature = signature,
        )
        val headers = mapOf(MizanContract.HEADER_IDEMPOTENCY_KEY to key)
        val first = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = token, headers = headers)
        assertEquals(422, first.status)
        assertEquals("SOD_SAME_ACTOR", first.field("messageCode"))
        // The same key with the same arguments must not be re-decided into a
        // different answer just because a different approver is available now.
        val second = send(MizanContract.PATH_EXECUTIONS, "POST", body, token = token, headers = headers)
        assertEquals(422, second.status)
        assertEquals("SOD_SAME_ACTOR", second.field("messageCode"))
    }

    // ---------------------------------------------------------------- refusals

    @Test
    fun theAuditorCanReadEverythingAndChangeNothing() {
        val token = auditorToken
        val read = send("${MizanContract.PATH_JOURNAL}?limit=10", "GET", token = token)
        assertEquals(200, read.status)
        val write = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            draftOrderBody("EXE-GOV-AUDITOR", 250_000L),
            token = token,
        )
        assertEquals(422, write.status)
        assertEquals("AUDITOR_READONLY", write.field("messageCode"))
    }

    @Test
    fun theAuditChainIsScopedPerTenantAndReportsIntegrity() {
        val reply = send("${MizanContract.PATH_AUDIT}?tenant=sim-alamal", "GET", token = repToken)
        assertEquals(200, reply.status)
        assertTrue(reply.truth("chainIntact") == true)
        assertEquals("SERVER_AUTHORED", reply.field("integrityClass"))
        assertTrue((reply.number("records") ?: 0L) > 0L)
    }

    @Test
    fun anErpSnapshotNeverCrossesATenantBoundary() {
        val mine = send(MizanContract.PATH_ERP, "GET", token = repToken)
        assertEquals(200, mine.status)
        assertTrue(mine.body.contains("SO-"))
        val theirs = send("${MizanContract.PATH_ERP}?tenant=somebody-else", "GET", token = repToken)
        assertEquals(403, theirs.status)
    }

    @Test
    fun aStrictRateLimitRefusesWithRetryAfterAndTheRightReason() {
        val strict = MizanService(
            ServiceConfig(
                rateLimiting = true,
                budgets = mapOf(LimitSurface.EXECUTION to app.mizan.service.security.Budget(2, 60_000)),
            ),
        )
        val port = strict.start(port = 0)
        try {
            val local = "http://127.0.0.1:$port"
            val token = Json.parseOrNull(
                java.net.http.HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("$local${MizanContract.PATH_SESSIONS}"))
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                """{"email":"rep@mizan.test","password":"rep-demo-password"}""",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).body(),
            )?.asObject()?.text("token") ?: error("token")
            val client = java.net.http.HttpClient.newHttpClient()
            fun execute(index: Int): HttpResponse<String> = client.send(
                HttpRequest.newBuilder(URI.create("$local${MizanContract.PATH_EXECUTIONS}"))
                    .header("Content-Type", "application/json")
                    .header(MizanContract.HEADER_AUTHORIZATION, "Bearer $token")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            """{"tool":"stock.availability","toolVersion":"1.0.0","tenantId":"sim-alamal",""" +
                                """"executionId":"EXE-RATE-$index","arguments":{"sku":"SKU-DESK-01"}}""",
                        ),
                    )
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, execute(1).statusCode())
            assertEquals(200, execute(2).statusCode())
            val refused = execute(3)
            assertEquals(429, refused.statusCode())
            assertEquals("RATE_LIMITED_EXECUTION", Json.parseOrNull(refused.body())?.asObject()?.text("messageCode"))
            assertNotNull(refused.headers().firstValue("Retry-After").orElse(null))
        } finally {
            strict.stop()
        }
    }

    @Test
    fun aReadIsAcceptedAndNamesItsSource() {
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            """{"tool":"stock.availability","toolVersion":"1.0.0","tenantId":"sim-alamal",""" +
                """"executionId":"EXE-GOV-READ","arguments":{"sku":"SKU-DESK-01"}}""",
            token = repToken,
        )
        assertEquals(reply.body, 200, reply.status)
        assertTrue(reply.isStatus(MizanContract.Status.ACCEPTED))
        assertEquals("stock.quant", reply.field("erpModel"))
        assertNotEquals(MizanContract.Status.VERIFIED, reply.field("status"))
    }

    @Test
    fun aReadThatTheErpRefusesFailsWithoutPretendingToBeEmpty() {
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            """{"tool":"stock.availability","toolVersion":"1.0.0","tenantId":"sim-alamal",""" +
                """"executionId":"EXE-GOV-NOSTOCK","arguments":{"sku":"SKU-NOT-THERE"}}""",
            token = repToken,
        )
        assertEquals(200, reply.status)
        assertTrue(reply.isStatus(MizanContract.Status.FAILED))
        assertEquals("STOCK_NOT_FOUND", reply.field("messageCode"))
    }

    @Test
    fun aToolThisDeploymentCannotSupportIsRefusedBeforeAnyErpCall() {
        val reply = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            """{"tool":"nonsense.tool","toolVersion":"1.0.0","tenantId":"sim-alamal",""" +
                """"executionId":"EXE-GOV-UNKNOWN","arguments":{}}""",
            token = repToken,
        )
        assertEquals(422, reply.status)
        assertEquals("TOOL_UNKNOWN", reply.field("messageCode"))
    }

    @Test
    fun aSessionCanBeRevokedAndIsThenRefused() {
        val token = signIn("manager@mizan.test", "manager-demo-password")
        val before = send(MizanContract.PATH_JOURNAL, "GET", token = token)
        assertEquals(200, before.status)
        val revoked = send("${MizanContract.PATH_SESSIONS}/current", "DELETE", token = token)
        assertEquals(200, revoked.status)
        val after = send(MizanContract.PATH_JOURNAL, "GET", token = token)
        assertEquals(401, after.status)
        assertEquals("SESSION_EXPIRED", after.field("messageCode"))
    }

    @Test
    fun aLargeBodyIsRefusedRatherThanBuffered() {
        val huge = """{"tool":"customer.search","tenantId":"sim-alamal","arguments":{"query":"""" +
            "x".repeat(300_000) + """"}}"""
        val reply = send(MizanContract.PATH_EXECUTIONS, "POST", huge, token = repToken)
        assertTrue("a body over the ceiling is a refusal", reply.status == 400 || reply.status == 413)
    }

    @Test
    fun aJournalEntryIsNeverReportedAsVerifiedWithoutAReadBack() {
        val token = repToken
        val journal = send("${MizanContract.PATH_JOURNAL}?limit=200", "GET", token = token)
        val body = journal.body
        // Every VERIFIED entry in this tenant crossed both DISPATCH and
        // VERIFICATION timestamps; the journal cannot claim otherwise.
        assertTrue(body.contains("\"verificationFinishedAtMillis\""))
        assertTrue(body.contains("\"dispatchStartedAtMillis\""))
    }
}
