package app.mizan

import app.mizan.domain.audit.AuditAppend
import app.mizan.domain.model.Actor
import app.mizan.domain.model.CachedCustomer
import app.mizan.domain.model.CachedOrder
import app.mizan.domain.model.CachedStock
import app.mizan.domain.model.EvidenceOrigin
import app.mizan.domain.model.Money
import app.mizan.domain.model.TenantId
import app.mizan.graph.AppGraph
import java.time.Instant

fun simulationActors(tenantId: TenantId): List<Actor> = DemoDirectory.actors(tenantId)

fun simulationEntry(): Pair<Actor, app.mizan.domain.model.TenantContext>? {
    val tenant = DemoDirectory.alamal
    val actor = DemoDirectory.actors(tenant.id).firstOrNull() ?: return null
    return actor to tenant
}

suspend fun onSimulationEntered(graph: AppGraph, tenantId: TenantId) {
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
        app.mizan.domain.model.ExecutionRecord(
            id = app.mizan.domain.model.ExecutionId(execId1),
            traceId = app.mizan.domain.model.TraceId(traceId1),
            proposalId = app.mizan.domain.model.ProposalId("PROP-PO-9021"),
            tenantId = tenantId,
            initiatorId = app.mizan.domain.model.ActorId("sim-rep"),
            tool = app.mizan.domain.model.ToolName.CREATE_DRAFT_ORDER,
            toolVersion = "2.1.0",
            intent = "Confirm PO-9021 for Cairo Tech (50,000 EGP)",
            phase = app.mizan.domain.execution.ExecutionPhase.VERIFIED,
            idempotencyKey = app.mizan.domain.model.IdempotencyKey("IDEM-PO-9021"),
            canonicalArgs = "{\"customer\":\"Cairo Tech\",\"amount\":50000,\"currency\":\"$currency\"}",
            amount = Money(if (egp) 50_000_00 else 20_000_00, currency),
            approval = app.mizan.domain.model.ApprovalLevel.L2_PRIVILEGED,
            riskTier = app.mizan.domain.model.RiskTier.R2_MEDIUM,
            policyRuleId = "POL-COMMERCIAL-01",
            approverIds = listOf(app.mizan.domain.model.ActorId("sim-mgr")),
            erpRecordId = "PO-2026-9021",
            erpModel = "purchase.order",
            dispatch = app.mizan.domain.error.DispatchState.SENT,
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
        app.mizan.domain.model.TrustReceipt(
            id = app.mizan.domain.model.ReceiptId("RCP-PO-9021"),
            traceId = app.mizan.domain.model.TraceId(traceId1),
            executionId = app.mizan.domain.model.ExecutionId(execId1),
            tenantId = tenantId,
            tenantLabel = tenantId.value,
            initiatorId = app.mizan.domain.model.ActorId("sim-rep"),
            initiatorLabel = "Amr Kamel",
            approverIds = listOf(app.mizan.domain.model.ActorId("sim-mgr")),
            approverLabels = listOf("Tarek El-Sayed"),
            tool = app.mizan.domain.model.ToolName.CREATE_DRAFT_ORDER,
            toolVersion = "2.1.0",
            policyRuleId = "POL-COMMERCIAL-01",
            approval = app.mizan.domain.model.ApprovalLevel.L2_PRIVILEGED,
            riskTier = app.mizan.domain.model.RiskTier.R2_MEDIUM,
            canonicalArgs = "{\"customer\":\"Cairo Tech\",\"amount\":50000}",
            idempotencyKey = app.mizan.domain.model.IdempotencyKey("IDEM-PO-9021"),
            erpRecordId = "PO-2026-9021",
            erpModel = "purchase.order",
            verification = app.mizan.domain.model.VerificationKind.SIMULATED_READ_BACK,
            integrityClass = app.mizan.domain.audit.IntegrityClass.LOCAL_ONLY,
            origin = EvidenceOrigin.SIMULATION,
            createdAt = exec1Time,
            auditChainIndex = 1L,
        ),
    )

    val exec2Time = now.minusSeconds(900)
    val traceId2 = "TRC-STOCK-01"
    val execId2 = "EXE-STOCK-01"
    graph.executions.upsert(
        app.mizan.domain.model.ExecutionRecord(
            id = app.mizan.domain.model.ExecutionId(execId2),
            traceId = app.mizan.domain.model.TraceId(traceId2),
            proposalId = app.mizan.domain.model.ProposalId("PROP-STOCK-01"),
            tenantId = tenantId,
            initiatorId = app.mizan.domain.model.ActorId("sim-rep"),
            tool = app.mizan.domain.model.ToolName.STOCK_AVAILABILITY,
            toolVersion = "1.0.0",
            intent = "Inspect SKU-SRV-01 stock availability in Cairo",
            phase = app.mizan.domain.execution.ExecutionPhase.VERIFIED,
            idempotencyKey = app.mizan.domain.model.IdempotencyKey("IDEM-STOCK-01"),
            canonicalArgs = "{\"sku\":\"SKU-SRV-01\",\"location\":\"Cairo\"}",
            amount = null,
            approval = app.mizan.domain.model.ApprovalLevel.L0_NONE,
            riskTier = app.mizan.domain.model.RiskTier.R0_READ,
            policyRuleId = "POL-READ-00",
            approverIds = emptyList(),
            erpRecordId = "STK-2026-018",
            erpModel = "stock.picking",
            dispatch = app.mizan.domain.error.DispatchState.SENT,
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
        app.mizan.domain.model.ExecutionRecord(
            id = app.mizan.domain.model.ExecutionId(execId3),
            traceId = app.mizan.domain.model.TraceId(traceId3),
            proposalId = app.mizan.domain.model.ProposalId("PROP-PAY-4402"),
            tenantId = tenantId,
            initiatorId = app.mizan.domain.model.ActorId("sim-fin"),
            tool = app.mizan.domain.model.ToolName.REGISTER_PAYMENT,
            toolVersion = "1.1.0",
            intent = "Register vendor payment for INV-4402 (15,000 EGP)",
            phase = app.mizan.domain.execution.ExecutionPhase.AWAITING_APPROVAL,
            idempotencyKey = app.mizan.domain.model.IdempotencyKey("IDEM-PAY-4402"),
            canonicalArgs = "{\"invoice\":\"INV-4402\",\"amount\":15000,\"currency\":\"$currency\"}",
            amount = Money(if (egp) 15_000_00 else 6_000_00, currency),
            approval = app.mizan.domain.model.ApprovalLevel.L3_MANAGER,
            riskTier = app.mizan.domain.model.RiskTier.R3_HIGH,
            policyRuleId = "POL-FINANCE-02",
            approverIds = emptyList(),
            erpRecordId = null,
            erpModel = "account.payment",
            dispatch = app.mizan.domain.error.DispatchState.NOT_SENT,
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
        app.mizan.domain.model.SyncSnapshot(
            tenantId = tenantId,
            lastSuccessfulSync = now.minusSeconds(120),
            lastAttempt = now.minusSeconds(120),
            state = app.mizan.domain.model.SyncState.IDLE,
            pendingChanges = 0,
            failedChanges = 0,
            conflicts = 0,
            serverCursor = "cursor-${now.toEpochMilli()}",
        ),
    )
    graph.preferences.lastSyncTimestampMillis = now.minusSeconds(120).toEpochMilli()
    graph.preferences.isServiceOnline = true
}
