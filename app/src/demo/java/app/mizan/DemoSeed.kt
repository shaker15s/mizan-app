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
            timestampMillis = now.toEpochMilli(),
        ),
    )
}
