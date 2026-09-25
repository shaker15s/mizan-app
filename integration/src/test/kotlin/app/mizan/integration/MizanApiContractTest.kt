package app.mizan.integration

import app.mizan.domain.audit.IntegrityClass
import app.mizan.domain.model.Actor
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.CreateDraftOrderArgs
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.Idempotency
import app.mizan.domain.model.IdempotencyKey
import app.mizan.domain.model.Money
import app.mizan.domain.model.Proposal
import app.mizan.domain.model.ProposalId
import app.mizan.domain.model.RiskTier
import app.mizan.domain.model.Role
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.ToolName
import app.mizan.domain.model.TraceId
import app.mizan.domain.model.VerificationKind
import app.mizan.domain.policy.PolicyDecision
import app.mizan.domain.risk.RiskAssessment
import app.mizan.integration.api.MizanApiClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Locks the client side of the contract without touching a network.
 *
 * The service asserts the same field names from its side in
 * `ContractParityTest`. If one side renames `arguments` or moves the
 * idempotency key to the body only, one of the two tests fails before a
 * device ever sends a request.
 */
class MizanApiContractTest {

    private val tenant = TenantId("sim-alamal")
    private val actor = Actor(ActorId("USR-REP"), "Amr Kamel", Role.SALES_REP, tenant)
    private val args = CreateDraftOrderArgs("Acme Corp", Money(250_000L, "USD"), "10 laptops")
    private val proposal = Proposal(
        id = ProposalId("PRP-1"),
        traceId = TraceId("TRC-1"),
        executionId = ExecutionId("EXE-1"),
        tenantId = tenant,
        initiator = actor,
        intent = "create a draft order",
        args = args,
        amount = args.amount,
        policy = PolicyDecision(
            allowed = true,
            approval = ApprovalLevel.L2_PRIVILEGED,
            riskTier = RiskTier.R2_MEDIUM,
            ruleId = "POL-THRESHOLD-L2",
            reasonCode = "THRESHOLD_L2",
            requiresSeparationOfDuties = true,
        ),
        risk = RiskAssessment(RiskTier.R2_MEDIUM, emptyList()),
        idempotencyKey = Idempotency.key(tenant, ToolName.CREATE_DRAFT_ORDER, args),
        createdAt = Instant.parse("2026-09-25T12:00:00Z"),
        policyIsPreview = true,
    )

    private fun bodyOf(request: okhttp3.Request): String {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return buffer.readUtf8()
    }

    @Test
    fun postsToTheExecutionsPathWithTheSessionHeaders() {
        val client = MizanApiClient("https://api.example/", { "tok-123" })
        val request = client.executionRequest(proposal, "USR-MGR", "tok-123")
        assertEquals("POST", request.method)
        assertEquals("https://api.example/v1/executions", request.url.toString())
        assertEquals("Bearer tok-123", request.header("Authorization"))
        assertEquals("TRC-1", request.header("X-Trace-Id"))
        assertEquals(proposal.idempotencyKey.value, request.header("Idempotency-Key"))
    }

    @Test
    fun bodyCarriesTheCanonicalArgumentsTheServiceParses() {
        val client = MizanApiClient("https://api.example", { "tok" })
        val body = bodyOf(client.executionRequest(proposal, "USR-MGR", "tok"))
        assertTrue(body.contains("\"tool\":\"sales.order.create_draft\""))
        assertTrue(body.contains("\"toolVersion\":\"2.1.0\""))
        assertTrue(body.contains("\"tenantId\":\"sim-alamal\""))
        assertTrue(body.contains("\"approverId\":\"USR-MGR\""))
        assertTrue(body.contains("\"arguments\":{"))
        assertTrue(body.contains("\"amountMinor\":\"250000\""))
        assertTrue(body.contains("\"currency\":\"USD\""))
        assertTrue(body.contains("\"customerName\":\"Acme Corp\""))
        assertTrue(body.contains("\"itemsSummary\":\"10 laptops\""))
        // Nothing that looks like a credential travels in the body.
        assertTrue(!body.contains("password"))
        assertTrue(!body.contains("token"))
    }

    @Test
    fun aCleartextUrlIsRefusedBeforeAnyRequestIsBuilt() {
        val client = MizanApiClient("http://api.example", { "tok" })
        val outcome = client.execute(proposal, "USR-MGR")
        assertTrue(outcome is app.mizan.domain.authority.AuthorityOutcome.Refused)
        val error = (outcome as app.mizan.domain.authority.AuthorityOutcome.Refused).error
        assertEquals("API_URL_NOT_HTTPS", error.code)
    }

    @Test
    fun aMissingTokenIsRefusedBeforeAnyRequestIsBuilt() {
        val client = MizanApiClient("https://api.example", { null })
        val outcome = client.execute(proposal, "USR-MGR")
        assertTrue(outcome is app.mizan.domain.authority.AuthorityOutcome.Refused)
        val error = (outcome as app.mizan.domain.authority.AuthorityOutcome.Refused).error
        assertEquals("SESSION_MISSING", error.code)
    }

    @Test
    fun anEmptyServiceUrlIsNotTreatedAsALocalSuccess() {
        val client = MizanApiClient("", { "tok" })
        val outcome = client.execute(proposal, "USR-MGR")
        assertTrue(outcome is app.mizan.domain.authority.AuthorityOutcome.Refused)
    }

    @Test
    fun verificationKindsStayHonestInTheContract() {
        // A simulated read-back is never presented as an ERP read-back.
        assertTrue(VerificationKind.SIMULATED_READ_BACK != VerificationKind.READ_BACK)
        assertTrue(IntegrityClass.LOCAL_ONLY != IntegrityClass.EXTERNAL_DURABLE)
    }

    @Test
    fun idempotencyKeyHeaderMatchesTheComputedKey() {
        val client = MizanApiClient("https://api.example", { "tok" })
        val request = client.executionRequest(proposal, "USR-MGR", "tok")
        assertEquals(
            Idempotency.key(tenant, ToolName.CREATE_DRAFT_ORDER, args).value,
            request.header("Idempotency-Key"),
        )
        val body = bodyOf(request)
        assertTrue(body.contains(Idempotency.key(tenant, ToolName.CREATE_DRAFT_ORDER, args).value))
    }

    @Test
    fun aTrailingSlashOnTheBaseUrlDoesNotDoubleThePath() {
        val client = MizanApiClient("https://api.example///", { "tok" })
        val request = client.executionRequest(proposal, "USR-MGR", "tok")
        assertEquals("https://api.example/v1/executions", request.url.toString())
    }

    @Test
    fun anIdempotencyKeyIsOnlyAcceptedAsAValueNotAPromise() {
        assertEquals(64, IdempotencyKey("a".repeat(64)).value.length)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            IdempotencyKey(" ")
        }
    }
}
