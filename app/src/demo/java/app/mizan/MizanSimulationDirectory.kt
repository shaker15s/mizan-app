package app.mizan

import app.mizan.domain.audit.AuditAppend
import app.mizan.domain.audit.IntegrityClass
import app.mizan.domain.error.DispatchState
import app.mizan.domain.execution.ExecutionPhase
import app.mizan.domain.model.Actor
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.CachedCustomer
import app.mizan.domain.model.CachedOrder
import app.mizan.domain.model.CachedStock
import app.mizan.domain.model.EvidenceOrigin
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.ExecutionRecord
import app.mizan.domain.model.IdempotencyKey
import app.mizan.domain.model.Money
import app.mizan.domain.model.ProposalId
import app.mizan.domain.model.ReceiptId
import app.mizan.domain.model.RiskTier
import app.mizan.domain.model.SyncSnapshot
import app.mizan.domain.model.SyncState
import app.mizan.domain.model.TenantContext
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.ToolName
import app.mizan.domain.model.TraceId
import app.mizan.domain.model.TrustReceipt
import app.mizan.domain.model.VerificationKind
import app.mizan.graph.AppGraph
import java.time.Instant

object MizanSimulationDirectory : SimulationDirectory {

    override val isAvailable: Boolean get() = true

    override fun tenants(): List<TenantContext> = listOf(DemoDirectory.alamal, DemoDirectory.nile)

    override fun actors(tenantId: TenantId): List<Actor> = DemoDirectory.actors(tenantId)

    override fun entry(): Pair<Actor, TenantContext>? {
        val tenant = DemoDirectory.alamal
        val actor = DemoDirectory.actors(tenant.id).firstOrNull() ?: return null
        return actor to tenant
    }

    override suspend fun seed(graph: AppGraph, tenantId: TenantId) {
        if (graph.audit.latest(tenantId) != null) return
        val now = Instant.now()
        val egp = tenantId.value == "sim-alamal"
        val currency = if (egp) "EGP" else "USD"
        graph.readModels.upsertCustomers(
            listOf(
                CachedCustomer(
                    "SIM-C-1",
                    tenantId,
                    if (egp) "Cairo Tech" else "Red Sea Metals",
                    Money(if (egp) 50_000_00 else 20_000_00, currency),
                    Money(if (egp) 12_400_00 else 8_000_00, currency),
                    "Active",
                    EvidenceOrigin.SIMULATION,
                ),
            ),
        )
        graph.readModels.upsertStock(
            listOf(
                CachedStock(
                    "SKU-SRV-01",
                    tenantId,
                    if (egp) "Server blade" else "Steel rebar 20mm",
                    18,
                    4,
                    Money(if (egp) 320_000 else 84_000, currency),
                    if (egp) "Cairo" else "Suez",
                    EvidenceOrigin.SIMULATION,
                ),
            ),
        )
        graph.readModels.upsertOrder(
            CachedOrder(
                "SIM-SO-1",
                tenantId,
                if (egp) "Cairo Tech" else "Red Sea Metals",
                Money(if (egp) 640_000 else 84_000, currency),
                "simulated",
                "Sample, not an ERP record",
                EvidenceOrigin.SIMULATION,
                now,
            ),
        )
        graph.audit.append(
            AuditAppend(
                traceId = "TRC-LOCAL-INIT",
                tenantId = tenantId,
                actorId = "system",
                action = "LOCAL_LEDGER_INITIALIZED",
                stateBefore = "EMPTY",
                stateAfter = "LOCAL",
                details = "simulation ledger started; not an ERP authentication",
                timestampMillis = now.minusSeconds(3600).toEpochMilli(),
            ),
        )

        // Seed historical executed commands with cryptographic audit trails
        val exec1Time = now.minusSeconds(1800)
        val traceId1 = "TRC-PO-9021"
        val execId1 = "EXE-PO-9021"
        graph.executions.upsert(
            ExecutionRecord(
                id = ExecutionId(execId1),
                traceId = TraceId(traceId1),
                proposalId = ProposalId("PROP-PO-9021"),
                tenantId = tenantId,
                initiatorId = ActorId("sim-rep"),
                tool = ToolName.CREATE_DRAFT_ORDER,
                toolVersion = "2.1.0",
                intent = "Confirm PO-9021 for Cairo Tech (50,000 EGP)",
                phase = ExecutionPhase.VERIFIED,
                idempotencyKey = IdempotencyKey("IDEM-PO-9021"),
                canonicalArgs = "{\"customer\":\"Cairo Tech\",\"amount\":50000,\"currency\":\"$currency\"}",
                amount = Money(if (egp) 50_000_00 else 20_000_00, currency),
                approval = ApprovalLevel.L2_PRIVILEGED,
                riskTier = RiskTier.R2_MEDIUM,
                policyRuleId = "POL-COMMERCIAL-01",
                approverIds = listOf(ActorId("sim-mgr")),
                erpRecordId = "PO-2026-9021",
                erpModel = "purchase.order",
                dispatch = DispatchState.SENT,
                leaseExpiresAt = exec1Time.plusSeconds(300),
                createdAt = exec1Time,
                updatedAt = exec1Time,
                errorCode = null,
                origin = EvidenceOrigin.SIMULATION,
            ),
        )
        graph.audit.append(
            AuditAppend(
                traceId = traceId1,
                tenantId = tenantId,
                actorId = "sim-mgr",
                action = "CONFIRM_PURCHASE_ORDER_VERIFIED",
                stateBefore = "AWAITING_APPROVAL",
                stateAfter = "VERIFIED",
                details = "Order PO-9021 read-back verified against simulated ERP ledger",
                timestampMillis = exec1Time.toEpochMilli(),
            ),
        )
        graph.receipts.insert(
            TrustReceipt(
                id = ReceiptId("RCP-PO-9021"),
                traceId = TraceId(traceId1),
                executionId = ExecutionId(execId1),
                tenantId = tenantId,
                tenantLabel = tenantId.value,
                initiatorId = ActorId("sim-rep"),
                initiatorLabel = "Amr Kamel",
                approverIds = listOf(ActorId("sim-mgr")),
                approverLabels = listOf("Tarek El-Sayed"),
                tool = ToolName.CREATE_DRAFT_ORDER,
                toolVersion = "2.1.0",
                policyRuleId = "POL-COMMERCIAL-01",
                approval = ApprovalLevel.L2_PRIVILEGED,
                riskTier = RiskTier.R2_MEDIUM,
                canonicalArgs = "{\"customer\":\"Cairo Tech\",\"amount\":50000}",
                idempotencyKey = IdempotencyKey("IDEM-PO-9021"),
                erpRecordId = "PO-2026-9021",
                erpModel = "purchase.order",
                verification = VerificationKind.SIMULATED_READ_BACK,
                integrityClass = IntegrityClass.LOCAL_ONLY,
                origin = EvidenceOrigin.SIMULATION,
                createdAt = exec1Time,
                auditChainIndex = 1L,
            ),
        )

        val exec2Time = now.minusSeconds(900)
        val traceId2 = "TRC-STOCK-01"
        val execId2 = "EXE-STOCK-01"
        graph.executions.upsert(
            ExecutionRecord(
                id = ExecutionId(execId2),
                traceId = TraceId(traceId2),
                proposalId = ProposalId("PROP-STOCK-01"),
                tenantId = tenantId,
                initiatorId = ActorId("sim-rep"),
                tool = ToolName.STOCK_AVAILABILITY,
                toolVersion = "1.0.0",
                intent = "Inspect SKU-SRV-01 stock availability in Cairo",
                phase = ExecutionPhase.VERIFIED,
                idempotencyKey = IdempotencyKey("IDEM-STOCK-01"),
                canonicalArgs = "{\"sku\":\"SKU-SRV-01\",\"location\":\"Cairo\"}",
                amount = null,
                approval = ApprovalLevel.L0_NONE,
                riskTier = RiskTier.R0_READ,
                policyRuleId = "POL-READ-00",
                approverIds = emptyList(),
                erpRecordId = "STK-2026-018",
                erpModel = "stock.picking",
                dispatch = DispatchState.SENT,
                leaseExpiresAt = null,
                createdAt = exec2Time,
                updatedAt = exec2Time,
                errorCode = null,
                origin = EvidenceOrigin.SIMULATION,
            ),
        )
        graph.audit.append(
            AuditAppend(
                traceId = traceId2,
                tenantId = tenantId,
                actorId = "sim-rep",
                action = "STOCK_AVAILABILITY_CHECKED",
                stateBefore = "READY",
                stateAfter = "VERIFIED",
                details = "SKU-SRV-01 stock level confirmed: 18 available, 4 reserved",
                timestampMillis = exec2Time.toEpochMilli(),
            ),
        )

        val exec3Time = now.minusSeconds(300)
        val traceId3 = "TRC-PAY-4402"
        val execId3 = "EXE-PAY-4402"
        graph.executions.upsert(
            ExecutionRecord(
                id = ExecutionId(execId3),
                traceId = TraceId(traceId3),
                proposalId = ProposalId("PROP-PAY-4402"),
                tenantId = tenantId,
                initiatorId = ActorId("sim-fin"),
                tool = ToolName.REGISTER_PAYMENT,
                toolVersion = "1.1.0",
                intent = "Register vendor payment for INV-4402 (15,000 EGP)",
                phase = ExecutionPhase.AWAITING_APPROVAL,
                idempotencyKey = IdempotencyKey("IDEM-PAY-4402"),
                canonicalArgs = "{\"invoice\":\"INV-4402\",\"amount\":15000,\"currency\":\"$currency\"}",
                amount = Money(if (egp) 15_000_00 else 6_000_00, currency),
                approval = ApprovalLevel.L3_MANAGER,
                riskTier = RiskTier.R3_HIGH,
                policyRuleId = "POL-FINANCE-02",
                approverIds = emptyList(),
                erpRecordId = null,
                erpModel = "account.payment",
                dispatch = DispatchState.NOT_SENT,
                leaseExpiresAt = null,
                createdAt = exec3Time,
                updatedAt = exec3Time,
                errorCode = null,
                origin = EvidenceOrigin.SIMULATION,
            ),
        )
        graph.audit.append(
            AuditAppend(
                traceId = traceId3,
                tenantId = tenantId,
                actorId = "sim-fin",
                action = "PAYMENT_AUTHORIZATION_REQUESTED",
                stateBefore = "DRAFT",
                stateAfter = "AWAITING_APPROVAL",
                details = "Payment of 15,000 EGP awaiting secondary finance officer sign-off",
                timestampMillis = exec3Time.toEpochMilli(),
            ),
        )

        // Save initial sync snapshot to local storage
        graph.sync.save(
            SyncSnapshot(
                tenantId = tenantId,
                lastSuccessfulSync = now.minusSeconds(120),
                lastAttempt = now.minusSeconds(120),
                state = SyncState.IDLE,
                pendingChanges = 0,
                failedChanges = 0,
                conflicts = 0,
                serverCursor = "cursor-${now.toEpochMilli()}",
            ),
        )
        graph.preferences.lastSyncTimestampMillis = now.minusSeconds(120).toEpochMilli()
        graph.preferences.isServiceOnline = true
    }
}
