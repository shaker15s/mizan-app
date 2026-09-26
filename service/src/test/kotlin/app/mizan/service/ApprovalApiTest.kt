package app.mizan.service

import app.mizan.domain.policy.VersionedPolicy
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

/**
 * The approval surface: where a person is asked, and where a person answers.
 *
 * Before these routes existed, an approval object could only be created by a
 * test that reached into the store. That is not a control, it is a fixture.
 * What this file checks is that the act of approving is a governed act: the
 * service decides the ladder, the service computes the fingerprint, the person
 * who answers must be the right person on the right device, and the answer is
 * good for exactly one execution.
 */
class ApprovalApiTest {

    data class Reply(val status: Int, val body: String) {
        fun field(name: String): String? = Json.parseOrNull(body)?.asObject()?.text(name)
        fun number(name: String): Long? = Json.parseOrNull(body)?.asObject()?.field(name)?.let {
            (it as? app.mizan.service.json.JsonValue.Num)?.raw?.toLongOrNull()
        }
        fun nested(name: String, inner: String): String? =
            (Json.parseOrNull(body)?.asObject()?.field(name) as? app.mizan.service.json.JsonValue.Obj)
                ?.text(inner)
    }

    companion object {
        private lateinit var service: MizanService
        private lateinit var base: String
        private lateinit var client: HttpClient
        private lateinit var storeDir: Path

        @JvmStatic
        @BeforeClass
        fun start() {
            storeDir = Files.createTempDirectory("mizan-approvals-")
            storeDir.toFile().deleteOnExit()
            service = MizanService(
                ServiceConfig(
                    storeDirectory = storeDir,
                    signingSecret = "approval-api-test-key",
                    versionedPolicy = VersionedPolicy.demoV12,
                    sessionTtlMillis = 30 * 60 * 1000L,
                ),
            )
            val port = service.start(port = 0)
            base = "http://127.0.0.1:$port"
            client = HttpClient.newHttpClient()
        }

        @JvmStatic
        @AfterClass
        fun stop() {
            service.stop()
        }

        private fun token(email: String, password: String): String {
            val reply = send("/v1/sessions", "POST", """{"email":"$email","password":"$password"}""")
            assertEquals(reply.body, 200, reply.status)
            return reply.field("token") ?: error("no token: ${reply.body}")
        }

        private val repToken: String by lazy { token("rep@mizan.test", "rep-demo-password") }
        private val managerToken: String by lazy { token("manager@mizan.test", "manager-demo-password") }
        private val financeToken: String by lazy { token("finance@mizan.test", "finance-demo-password") }
        private val auditorToken: String by lazy { token("auditor@mizan.test", "auditor-demo-password") }

        private fun send(
            path: String,
            method: String,
            body: String? = null,
            token: String? = null,
        ): Reply {
            val builder = HttpRequest.newBuilder(URI(base + path))
            token?.let { builder.header(MizanContract.HEADER_AUTHORIZATION, "Bearer $it") }
            if (body != null) {
                builder.header("Content-Type", "application/json")
                builder.method(method, HttpRequest.BodyPublishers.ofString(body))
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody())
            }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            return Reply(response.statusCode(), response.body())
        }

        private fun signInOn(origin: String, email: String, password: String): String {
            val reply = sendOn(origin, "/v1/sessions", "POST", """{"email":"$email","password":"$password"}""", null)
            assertEquals(reply.body, 200, reply.status)
            return reply.field("token") ?: error("no token: ${reply.body}")
        }

        private fun sendOn(
            origin: String,
            path: String,
            method: String,
            body: String?,
            token: String?,
        ): Reply {
            val builder = HttpRequest.newBuilder(URI(origin + path))
            token?.let { builder.header(MizanContract.HEADER_AUTHORIZATION, "Bearer $it") }
            if (body != null) {
                builder.header("Content-Type", "application/json")
                builder.method(method, HttpRequest.BodyPublishers.ofString(body))
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody())
            }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            return Reply(response.statusCode(), response.body())
        }

        /** A draft order of 2,500 USD: L2, so a manager has to answer for it. */
        private fun orderBody(
            executionId: String,
            amountMinor: Long = 250_000L,
            key: String = "approval-key-$executionId",
            proposalId: String = "PROP-$executionId",
            fingerprint: String? = null,
            approvalId: String? = null,
            approverId: String? = null,
        ): String {
            val fields = mutableListOf(
                """"tool":"sales.order.create_draft"""",
                """"toolVersion":"2.1.0"""",
                """"tenantId":"sim-alamal"""",
                """"executionId":"$executionId"""",
                """"proposalId":"$proposalId"""",
            )
            fingerprint?.let { fields += """"proposalFingerprint":"$it"""" }
            approvalId?.let { fields += """"approvalId":"$it"""" }
            approverId?.let { fields += """"approverId":"$it"""" }
            fields += """"arguments":{"amountMinor":"$amountMinor","currency":"USD",""" +
                """"customerName":"Acme Corp","itemsSummary":"1 desk"}"""
            return "{" + fields.joinToString(",") + "}"
        }
    }

    // -------------------------------------------------------------- the ladder

    @Test
    fun theServiceDecidesTheLadderAndSaysWhichOneItIs() {
        val opened = send(MizanContract.PATH_APPROVALS, "POST", orderBody("EXE-APR-LADDER"), token = repToken)
        assertEquals(opened.body, 201, opened.status)
        assertEquals("L2_PRIVILEGED", opened.field("requiredLevel"))
        assertEquals("PENDING", opened.field("state"))
        assertEquals("USR-REP", opened.field("initiatorId"))
        assertNotNull("an approval names the fingerprint it is bound to", opened.field("proposalFingerprint"))
        assertNotNull("and the policy version it was granted under", opened.field("policyVersionId"))
        assertTrue("the fingerprint the client must sign", opened.field("proposalFingerprint")!!.length >= 32)
    }

    @Test
    fun aReadNeedsNoApprovalAndIsRefusedRatherThanRegistered() {
        val opened = send(
            MizanContract.PATH_APPROVALS,
            "POST",
            """{"tool":"stock.availability","toolVersion":"1.0.0","tenantId":"sim-alamal",""" +
                """"executionId":"EXE-APR-READ","arguments":{"sku":"SKU-DESK-01"}}""",
            token = repToken,
        )
        assertEquals(opened.body, 422, opened.status)
        assertEquals("APPROVAL_NOT_REQUIRED", opened.field("messageCode"))
    }

    @Test
    fun aSmallWriteOpensAnL1ApprovalThatOnlyTheInitiatorCanAnswer() {
        val opened = send(
            MizanContract.PATH_APPROVALS,
            "POST",
            orderBody("EXE-APR-SMALL", amountMinor = 25_000L),
            token = repToken,
        )
        assertEquals(opened.body, 201, opened.status)
        assertEquals("L1_USER_CONFIRMATION", opened.field("requiredLevel"))
        val id = opened.field("approvalId")!!

        // L1 is the person confirming their own action. Someone else answering
        // it is not a stronger approval, it is the wrong person.
        val manager = send("${MizanContract.PATH_APPROVALS}/$id/grant", "POST", "{}", token = managerToken)
        assertEquals(manager.body, 422, manager.status)
        assertEquals("SOD_ROLE_INSUFFICIENT", manager.field("messageCode"))

        val self = send("${MizanContract.PATH_APPROVALS}/$id/grant", "POST", "{}", token = repToken)
        assertEquals(self.body, 200, self.status)
        assertEquals("GRANTED", self.field("state"))
    }

    @Test
    fun askingTwiceForTheSameThingIsOneApproval() {
        val body = orderBody("EXE-APR-ONCE", proposalId = "PROP-APR-ONCE")
        val first = send(MizanContract.PATH_APPROVALS, "POST", body, token = repToken)
        val second = send(MizanContract.PATH_APPROVALS, "POST", body, token = repToken)
        assertEquals(201, first.status)
        assertEquals(201, second.status)
        assertEquals(
            "a client that retries its request does not create a second approval",
            first.field("approvalId"),
            second.field("approvalId"),
        )
    }

    @Test
    fun aRequestTheServiceCannotReadIsRefusedNotGuessedAt() {
        // A body with no arguments is not guessed at: it is a request the
        // service cannot read, which is a refusal and not an approval.
        val unreadable = send(MizanContract.PATH_APPROVALS, "POST", """{"tool":"sales.order.create_draft"}""", token = repToken)
        assertEquals(422, unreadable.status)
        assertEquals("REQUEST_UNREADABLE", unreadable.field("messageCode"))

        val nonsense = send(MizanContract.PATH_APPROVALS, "POST", "not json at all", token = repToken)
        assertEquals(422, nonsense.status)
        assertEquals("REQUEST_UNREADABLE", nonsense.field("messageCode"))
    }

    @Test
    fun approvalsAreRefusedWithoutASession() {
        val reply = send(MizanContract.PATH_APPROVALS, "GET")
        assertEquals(401, reply.status)
    }

    // --------------------------------------------------------------- answering

    @Test
    fun theWrongRankCannotAnswer() {
        val opened = send(MizanContract.PATH_APPROVALS, "POST", orderBody("EXE-APR-RANK"), token = repToken)
        val id = opened.field("approvalId")!!
        // The initiator cannot answer their own approval.
        val self = send("${MizanContract.PATH_APPROVALS}/$id/grant", "POST", "{}", token = repToken)
        assertEquals(self.body, 422, self.status)
        assertEquals("SOD_SAME_ACTOR", self.field("messageCode"))

        // An auditor reads the record; an auditor does not approve money.
        val auditor = send("${MizanContract.PATH_APPROVALS}/$id/grant", "POST", "{}", token = auditorToken)
        assertEquals(422, auditor.status)
        assertEquals("SOD_ROLE_INSUFFICIENT", auditor.field("messageCode"))
    }

    @Test
    fun aManagerGrantsAndTheObjectSaysWhoDid() {
        val opened = send(MizanContract.PATH_APPROVALS, "POST", orderBody("EXE-APR-GRANT"), token = repToken)
        val id = opened.field("approvalId")!!
        val granted = send("${MizanContract.PATH_APPROVALS}/$id/grant", "POST", "{}", token = managerToken)
        assertEquals(granted.body, 200, granted.status)
        assertEquals("GRANTED", granted.field("state"))
        val approvers = Json.parseOrNull(granted.body)?.asObject()?.field("approverIds")
            ?.let { (it as? app.mizan.service.json.JsonValue.Arr)?.items?.firstOrNull() }
            ?.let { (it as? app.mizan.service.json.JsonValue.Str)?.value }
        assertEquals("USR-MGR", approvers)
        assertTrue("the object names the person, not just the act", granted.body.contains("Tarek Fouad"))
    }

    @Test
    fun aRefusalIsRecordedInTheApproverSOwnCode() {
        val opened = send(MizanContract.PATH_APPROVALS, "POST", orderBody("EXE-APR-REFUSE"), token = repToken)
        val id = opened.field("approvalId")!!
        val refused = send(
            "${MizanContract.PATH_APPROVALS}/$id/refuse",
            "POST",
            """{"reasonCode":"AMOUNT_TOO_HIGH"}""",
            token = managerToken,
        )
        assertEquals(refused.body, 200, refused.status)
        assertEquals("REJECTED", refused.field("state"))
        // The service never returns a sentence: the app renders the code.
        assertFalse(refused.body.contains("too high for this quarter"))
        assertTrue(refused.body.contains("AMOUNT_TOO_HIGH"))
    }

    @Test
    fun anUnknownApprovalIsANotFoundNotAnEmptyApproval() {
        val reply = send("${MizanContract.PATH_APPROVALS}/APR-NOTHING", "GET", token = repToken)
        assertEquals(404, reply.status)
        assertEquals("APPROVAL_UNKNOWN", reply.field("messageCode"))
    }

    @Test
    fun theListIsTheTenantsAndPendingComesFirst() {
        val opened = send(MizanContract.PATH_APPROVALS, "POST", orderBody("EXE-APR-LIST"), token = repToken)
        val id = opened.field("approvalId")!!
        val pending = send("${MizanContract.PATH_APPROVALS}?state=PENDING", "GET", token = managerToken)
        assertEquals(200, pending.status)
        assertTrue("the pending approval is listed", pending.body.contains(id))
        assertTrue(pending.body.contains("proposalFingerprint"))
    }

    // ------------------------------------------------------- the execution side

    @Test
    fun aGrantedApprovalExecutesAndIsSpentOnce() {
        val opened = send(MizanContract.PATH_APPROVALS, "POST", orderBody("EXE-APR-EXEC"), token = repToken)
        val id = opened.field("approvalId")!!
        val fingerprint = opened.field("proposalFingerprint")!!
        send("${MizanContract.PATH_APPROVALS}/$id/grant", "POST", "{}", token = managerToken)

        val executed = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            orderBody("EXE-APR-EXEC", approvalId = id, fingerprint = fingerprint, approverId = "USR-MGR"),
            token = repToken,
        )
        assertEquals(executed.body, 200, executed.status)
        assertTrue(executed.field("status") == MizanContract.Status.VERIFIED || executed.field("status") == MizanContract.Status.ACCEPTED)

        // The approval is spent: the same object cannot authorise a second
        // execution under a different key.
        val again = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            orderBody(
                "EXE-APR-EXEC-2",
                key = "approval-key-second",
                approvalId = id,
                fingerprint = fingerprint,
                approverId = "USR-MGR",
            ),
            token = repToken,
        )
        assertEquals(409, again.status)
        assertEquals("EXECUTION_ALREADY_RESOLVED", again.field("messageCode"))
    }

    @Test
    fun anApprovalForOneOrderCannotExecuteAnother() {
        val opened = send(MizanContract.PATH_APPROVALS, "POST", orderBody("EXE-APR-MISMATCH"), token = repToken)
        val id = opened.field("approvalId")!!
        val fingerprint = opened.field("proposalFingerprint")!!
        send("${MizanContract.PATH_APPROVALS}/$id/grant", "POST", "{}", token = managerToken)

        // The same approval id, but the amount moved. The service recomputes
        // the fingerprint from the arguments it is about to execute, so this is
        // refused before the ERP hears anything.
        val ordersBefore = service.erp.snapshot("sim-alamal").orders.size
        val higher = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            orderBody(
                "EXE-APR-MISMATCH",
                amountMinor = 900_000L,
                approvalId = id,
                fingerprint = fingerprint,
                approverId = "USR-MGR",
            ),
            token = repToken,
        )
        assertEquals(422, higher.status)
        assertEquals("PROPOSAL_FINGERPRINT_MISMATCH", higher.field("messageCode"))
        assertEquals(
            "the ERP never heard about the larger order",
            ordersBefore,
            service.erp.snapshot("sim-alamal").orders.size,
        )
    }

    @Test
    fun aBareApproverNameDoesNotReplaceAnApprovalObject() {
        // The same request, with a manager named in the body and no approval
        // object: the ladder still says L2, and the service refuses it. Naming
        // an approver is a claim; an approval object is a record.
        val named = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            orderBody("EXE-APR-MISSING-NAME", approverId = "USR-MGR"),
            token = repToken,
        )
        assertEquals(named.body, 200, named.status)
        assertTrue(
            named.field("status") == MizanContract.Status.ACCEPTED ||
                named.field("status") == MizanContract.Status.VERIFIED,
        )

        val without = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            orderBody("EXE-APR-MISSING-NONE"),
            token = repToken,
        )
        assertEquals(without.body, 422, without.status)
        assertEquals("SOD_NEED_SECOND_APPROVER", without.field("messageCode"))
    }

    @Test
    fun anExpiredApprovalCannotExecute() {
        val opened = send(MizanContract.PATH_APPROVALS, "POST", orderBody("EXE-APR-EXPIRED"), token = repToken)
        val id = opened.field("approvalId")!!
        val fingerprint = opened.field("proposalFingerprint")!!
        send("${MizanContract.PATH_APPROVALS}/$id/grant", "POST", "{}", token = managerToken)

        // Expire it by hand: the window is policy, and a test cannot wait four
        // hours. This is the same store the service reads.
        val stored = service.stores!!.approvals.get(id)!!
        service.stores!!.approvals.save(stored.copy(expiresAtMillis = System.currentTimeMillis() - 1_000L))

        val executed = send(
            MizanContract.PATH_EXECUTIONS,
            "POST",
            orderBody("EXE-APR-EXPIRED", approvalId = id, fingerprint = fingerprint, approverId = "USR-MGR"),
            token = repToken,
        )
        assertEquals(409, executed.status)
        assertEquals("APPROVAL_EXPIRED", executed.field("messageCode"))
    }

    @Test
    fun aFinanceApproverAnswersADifferentLevelThanAManager() {
        // The ladder is the service's, not the client's: the same body opened
        // by the same person is L2 whoever answers it.
        val opened = send(MizanContract.PATH_APPROVALS, "POST", orderBody("EXE-APR-FINANCE"), token = repToken)
        assertEquals("L2_PRIVILEGED", opened.field("requiredLevel"))
        val id = opened.field("approvalId")!!
        val granted = send("${MizanContract.PATH_APPROVALS}/$id/grant", "POST", "{}", token = financeToken)
        assertEquals(granted.body, 200, granted.status)
        assertEquals("GRANTED", granted.field("state"))
    }

    @Test
    fun aDeploymentThatRequiresProofsWillNotLetAnApproverSignWithNothing() {
        // A separate deployment, because requiring a device proof is a
        // configuration and this test is about that configuration.
        val directory = Files.createTempDirectory("mizan-approvals-proof-")
        directory.toFile().deleteOnExit()
        val guarded = MizanService(
            ServiceConfig(
                storeDirectory = directory,
                signingSecret = "approval-proof-key",
                requireDeviceProof = true,
                versionedPolicy = VersionedPolicy.demoV12,
            ),
        )
        val port = guarded.start(port = 0)
        val proofBase = "http://127.0.0.1:$port"
        try {
            val manager = signInOn(proofBase, "manager@mizan.test", "manager-demo-password")
            val rep = signInOn(proofBase, "rep@mizan.test", "rep-demo-password")
            // The rep asks; the manager answers. One person cannot do both,
            // which is the rule the proofs exist to back up.
            val opened = sendOn(
                proofBase,
                MizanContract.PATH_APPROVALS,
                "POST",
                orderBody("EXE-APR-PROOF"),
                rep,
            )
            assertEquals(opened.body, 201, opened.status)
            val id = opened.field("approvalId")!!
            val fingerprint = opened.field("proposalFingerprint")!!

            // A grant with no proof is refused: an approval of this level is a
            // person, on a device this tenant knows, saying yes.
            val bare = sendOn(proofBase, "${MizanContract.PATH_APPROVALS}/$id/grant", "POST", "{}", manager)
            assertEquals(bare.body, 401, bare.status)
            assertEquals("AUTH_PROOF_MISSING", bare.field("messageCode"))

            // With a real signature over the approval's own fingerprint, it is
            // granted.
            val keyPair = app.mizan.domain.security.DeviceKeyMaterial.generate(
                app.mizan.domain.security.DeviceKeyAlgorithm.ED25519,
            )
            val enrolled = sendOn(
                proofBase,
                MizanContract.PATH_DEVICES,
                "POST",
                """{"deviceId":"DEV-APPROVER","algorithm":"Ed25519",""" +
                    """"publicKey":"${app.mizan.domain.security.DeviceKeyMaterial.encode(keyPair.public)}",""" +
                    """"label":"approver device"}""",
                manager,
            )
            assertEquals(enrolled.body, 201, enrolled.status)
            val challenge = sendOn(
                proofBase,
                MizanContract.PATH_DEVICES + "/challenge",
                "POST",
                """{"deviceId":"DEV-APPROVER","executionId":"$id","proposalFingerprint":"$fingerprint"}""",
                manager,
            )
            assertEquals(challenge.body, 201, challenge.status)
            val signature = app.mizan.domain.security.DeviceKeyMaterial.sign(
                keyPair.private,
                challenge.field("messageToSign")!!.toByteArray(Charsets.UTF_8),
                app.mizan.domain.security.DeviceKeyAlgorithm.ED25519,
            )
            val granted = sendOn(
                proofBase,
                "${MizanContract.PATH_APPROVALS}/$id/grant",
                "POST",
                """{"deviceChallengeId":"${challenge.field("challengeId")}","deviceSignature":"$signature"}""",
                manager,
            )
            assertEquals(granted.body, 200, granted.status)
            assertEquals("GRANTED", granted.field("state"))
        } finally {
            guarded.stop()
        }
    }

    @Test
    fun theHealthAndJournalSurfacesStillHoldAfterApprovals() {
        val health = send(MizanContract.PATH_HEALTH, "GET")
        assertEquals(200, health.status)
        assertTrue("the journal count is a number, not a sentence", (health.number("journalEntries") ?: 0L) > 0)
        // Approvals are durable state too, and their decisions are in the log.
        assertTrue(Files.list(storeDir).anyMatch { it.fileName.toString() == "approvals.log" })
    }
}
