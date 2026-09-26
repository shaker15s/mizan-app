package app.mizan.integration

import app.mizan.domain.approval.ApprovalState
import app.mizan.domain.authority.ApprovalReference
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.CreateDraftOrderArgs
import app.mizan.domain.model.Money
import app.mizan.integration.api.GovernanceApiClient
import app.mizan.integration.api.JsonText
import app.mizan.integration.api.MizanApiClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device side of the governance contract.
 *
 * Two things are locked here. First, the requests the app builds: the paths,
 * the methods, the session header, and the exact field names the service
 * parses -- a rename on either side fails one of these before a device sends
 * anything. Second, the reader: a reply the client cannot understand must be a
 * refusal, never a half-filled value that a screen renders as fact.
 */
class GovernanceContractTest {

    private val order = CreateDraftOrderArgs("Acme Corp", Money(250_000L, "USD"), "1 desk")

    private fun bodyOf(request: okhttp3.Request): String {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun client(token: String? = "tok-123") =
        GovernanceApiClient("https://api.example", { token })

    // ------------------------------------------------------------- the requests

    @Test
    fun openingAnApprovalPostsToTheApprovalsPathWithTheSessionHeader() {
        val built = client().request("POST", "/v1/approvals", "{}")
        assertEquals("POST", built.method)
        assertEquals("https://api.example/v1/approvals", built.url.toString())
        assertEquals("Bearer tok-123", built.header("Authorization"))
        assertEquals("application/json; charset=utf-8", built.header("Content-Type"))
    }

    @Test
    fun theApprovalBodyCarriesTheSameArgumentNamesTheServiceParses() {
        val request = client().request(
            "POST",
            "/v1/approvals",
            app.mizan.domain.model.CanonicalJson.write(
                app.mizan.domain.model.CanonicalValue.Obj(
                    listOf(
                        "tool" to app.mizan.domain.model.CanonicalValue.Str(order.tool.wire),
                        "arguments" to order.canonical(),
                    ),
                ),
            ),
        )
        val body = bodyOf(request)
        assertTrue(body.contains("\"tool\":\"sales.order.create_draft\""))
        assertTrue(body.contains("\"amountMinor\":250000"))
        assertTrue(body.contains("\"currency\":\"USD\""))
        assertTrue(body.contains("\"customerName\":\"Acme Corp\""))
        // The device never sends a fingerprint it computed itself: the service
        // computes the one the person is approving.
        assertTrue(!body.contains("proposalFingerprint"))
    }

    @Test
    fun theExecutionRequestCarriesTheApprovalAndTheProofWhenThereIsOne() {
        val approval = ApprovalReference(
            approvalId = "APR-1",
            proposalFingerprint = "fp-1",
            deviceChallengeId = "CHL-1",
            deviceSignature = "sig-1",
        )
        val proposal = ProposalFixture.proposal()
        val built = MizanApiClient("https://api.example", { "tok" })
            .executionRequest(proposal, "USR-MGR", "tok", approval)
        val body = bodyOf(built)
        assertTrue(body.contains("\"approvalId\":\"APR-1\""))
        assertTrue(body.contains("\"proposalFingerprint\":\"fp-1\""))
        assertTrue(body.contains("\"deviceChallengeId\":\"CHL-1\""))
        assertTrue(body.contains("\"deviceSignature\":\"sig-1\""))
    }

    @Test
    fun anExecutionWithoutAnApprovalIsByteForByteWhatItWas() {
        val proposal = ProposalFixture.proposal()
        val built = MizanApiClient("https://api.example", { "tok" })
            .executionRequest(proposal, "USR-MGR", "tok", null)
        val body = bodyOf(built)
        assertTrue(!body.contains("approvalId"))
        assertTrue(!body.contains("deviceSignature"))
        assertTrue(!body.contains("proposalFingerprint"))
    }

    @Test
    fun aCleartextOrTokenlessGovernanceCallIsRefusedBeforeAnyRequest() {
        val cleartext = GovernanceApiClient("http://api.example", { "tok" }).reconciliationCases()
        assertTrue(cleartext is app.mizan.integration.api.RemoteResult.Refused)
        assertEquals("API_URL_NOT_HTTPS", (cleartext as app.mizan.integration.api.RemoteResult.Refused).code)

        val anonymous = GovernanceApiClient("https://api.example", { null }).reconciliationCases()
        assertEquals(
            "SESSION_MISSING",
            (anonymous as app.mizan.integration.api.RemoteResult.Refused).code,
        )
    }

    @Test
    fun resolvingACaseSendsACodeAndNeverASentence() {
        val request = client().request(
            "POST",
            "/v1/reconciliation/REC-1/resolve",
            app.mizan.domain.model.CanonicalJson.write(
                app.mizan.domain.model.CanonicalValue.Obj(
                    listOf(
                        "resolution" to app.mizan.domain.model.CanonicalValue.Str("LINKED"),
                        "note" to app.mizan.domain.model.CanonicalValue.Str(""),
                    ),
                ),
            ),
        )
        assertEquals("POST", request.method)
        assertTrue(request.url.toString().endsWith("/v1/reconciliation/REC-1/resolve"))
    }

    // --------------------------------------------------------------- the reader

    @Test
    fun theReaderReadsTheApprovalsTheServiceWrites() {
        val body = """
            {"durable":true,"approvals":[
              {"approvalId":"APR-1","state":"GRANTED","requiredLevel":"L2_PRIVILEGED",
               "proposalFingerprint":"fp-1","policyVersionId":"v12","initiatorId":"USR-REP",
               "createdAtMillis":1790000000000,"expiresAtMillis":1790003600000,
               "approverIds":["USR-MGR"],"approverLabels":["Tarek Fouad"],
               "decisions":[{"approverId":"USR-MGR","state":"GRANTED","atEpochMillis":1790000000000,
                             "reasonCode":null}]}
            ]}
        """.trimIndent()
        val document = JsonText.parse(body) as? JsonText.Obj
        assertTrue(document != null)
        assertEquals(true, document!!.flag("durable"))
        val approvals = document.array("approvals")
        assertEquals(1, approvals.size)
        val approval = approvals.first() as JsonText.Obj
        assertEquals("APR-1", approval.text("approvalId"))
        assertEquals("GRANTED", approval.text("state"))
        assertEquals(1790000000000L, approval.whole("createdAtMillis"))
        assertEquals(listOf("USR-MGR"), approval.strings("approverIds"))
        val decision = approval.array("decisions").first() as JsonText.Obj
        assertEquals("USR-MGR", decision.text("approverId"))
        assertNull("a granted decision carries no refusal code", decision.text("reasonCode"))
    }

    @Test
    fun aGrantThatStillNeedsASecondApproverIsReadAsAPartialAnswer() {
        val body = """
            {"approval":{"approvalId":"APR-2","state":"PENDING","requiredLevel":"L4_DUAL",
             "proposalFingerprint":"fp-2","policyVersionId":"v12","initiatorId":"USR-REP",
             "createdAtMillis":1,"expiresAtMillis":2,"approverIds":["USR-MGR"]},
             "messageCode":"APPROVAL_NEEDS_SECOND_APPROVER"}
        """.trimIndent()
        val document = JsonText.parse(body) as? JsonText.Obj
        assertEquals("APPROVAL_NEEDS_SECOND_APPROVER", document!!.text("messageCode"))
        val approval = document.obj("approval")!!
        assertEquals(ApprovalState.PENDING.name, approval.text("state"))
        assertEquals(ApprovalLevel.L4_DUAL.name, approval.text("requiredLevel"))
    }

    @Test
    fun theReaderFailsClosedOnAnythingItWasNotWrittenFor() {
        assertNull(JsonText.parse(""))
        assertNull(JsonText.parse("not json"))
        assertNull(JsonText.parse("{\"a\":}"))
        assertNull(JsonText.parse("{\"a\":1,}"))
        assertNull(JsonText.parse("[1,2"))
        assertNull(JsonText.parse("{'a':1}"))
        assertNull(JsonText.parse("{\"a\":01}"))
        assertNull(JsonText.parse("{\"a\":1} trailing"))
        // A reply with nothing in it is not an empty approval.
        val empty = JsonText.parse("{}") as JsonText.Obj
        assertNull(empty.text("approvalId"))
        assertEquals(emptyList<String>(), empty.strings("approverIds"))
    }

    @Test
    fun theReaderHandlesTheEscapesAReceiptActuallyContains() {
        val document = JsonText.parse("""{"note":"line\nbreak \"quoted\" \\ slash \u0623"}""") as JsonText.Obj
        assertEquals("line\nbreak \"quoted\" \\ slash أ", document.text("note"))
    }
}

/** One proposal, built the way the app builds one, for the request tests. */
private object ProposalFixture {
    fun proposal(): app.mizan.domain.model.Proposal {
        val tenant = app.mizan.domain.model.TenantId("sim-alamal")
        val actor = app.mizan.domain.model.Actor(
            app.mizan.domain.model.ActorId("USR-REP"),
            "Amr Kamel",
            app.mizan.domain.model.Role.SALES_REP,
            tenant,
        )
        val args = CreateDraftOrderArgs("Acme Corp", Money(250_000L, "USD"), "1 desk")
        return app.mizan.domain.model.Proposal(
            id = app.mizan.domain.model.ProposalId("PRP-1"),
            traceId = app.mizan.domain.model.TraceId("TRC-1"),
            executionId = app.mizan.domain.model.ExecutionId("EXE-1"),
            tenantId = tenant,
            initiator = actor,
            intent = "create a draft order",
            args = args,
            amount = args.amount,
            policy = app.mizan.domain.policy.PolicyDecision(
                allowed = true,
                approval = ApprovalLevel.L2_PRIVILEGED,
                riskTier = app.mizan.domain.model.RiskTier.R2_MEDIUM,
                ruleId = "POL-THRESHOLD-L2",
                reasonCode = "THRESHOLD_L2",
                requiresSeparationOfDuties = true,
            ),
            risk = app.mizan.domain.risk.RiskAssessment(
                app.mizan.domain.model.RiskTier.R2_MEDIUM,
                emptyList(),
            ),
            idempotencyKey = app.mizan.domain.model.Idempotency.key(
                tenant,
                app.mizan.domain.model.ToolName.CREATE_DRAFT_ORDER,
                args,
            ),
            createdAt = java.time.Instant.parse("2026-09-25T12:00:00Z"),
            policyIsPreview = true,
        )
    }
}
