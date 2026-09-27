package app.mizan.domain.execution

import app.mizan.domain.error.DispatchState
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.CreateDraftOrderArgs
import app.mizan.domain.model.Digests
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.IdempotencyKey
import app.mizan.domain.model.Money
import app.mizan.domain.model.ProposalId
import app.mizan.domain.model.RiskTier
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.ToolName
import app.mizan.domain.tool.ToolCatalog
import java.time.Instant

/**
 * One journal entry, built the way the authority builds it, so a test that
 * changes the machine cannot be "fixed" by quietly changing the entry too.
 */
object ExecutionJournalTestFactory {

    const val TOOL_VERSION = "2.1.0"
    const val POLICY_HASH =
        "9f2c1e3a4b5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f7"

    fun journal(
        stage: JournalStage = JournalStage.PROPOSED,
        revision: Long = 0L,
        at: Instant = Instant.parse("2026-09-26T09:00:00Z"),
    ): ExecutionJournal {
        val args = CreateDraftOrderArgs(
            customerName = "Acme Corp",
            amount = Money(250_000, "USD"),
            itemsSummary = "10 laptops",
        )
        return ExecutionJournal(
            executionId = ExecutionId("EXE-TEST-0001"),
            tenantId = TenantId("sim-alamal"),
            actorId = ActorId("USR-REP"),
            proposalId = ProposalId("PRP-TEST-0001"),
            proposalFingerprint = "fingerprint-of-the-proposal",
            tool = ToolName.CREATE_DRAFT_ORDER,
            toolVersion = TOOL_VERSION,
            schemaVersion = "$TOOL_VERSION.s1",
            catalogVersion = ToolCatalog.VERSION,
            canonicalInputHash = Digests.sha256(args.canonical().toString()),
            idempotencyKey = IdempotencyKey("idem-test-0001"),
            policyVersionId = "12",
            policyHash = POLICY_HASH,
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
            traceId = "TRC-TEST-0001",
            revision = revision,
            createdAt = at,
            updatedAt = at,
        )
    }
}
