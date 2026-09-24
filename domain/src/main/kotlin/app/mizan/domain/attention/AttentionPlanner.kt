package app.mizan.domain.attention

import app.mizan.domain.execution.ExecutionPhase
import app.mizan.domain.model.ExecutionRecord
import app.mizan.domain.model.HealthStatus
import app.mizan.domain.model.ReconciliationCase
import app.mizan.domain.model.ReconciliationStatus
import app.mizan.domain.model.SyncState
import app.mizan.domain.model.SyncSnapshot
import app.mizan.domain.model.SystemHealth

enum class AttentionKind {
    APPROVAL,
    RECONCILIATION,
    FAILURE,
    ERP_UNAVAILABLE,
    SYNC_STALE,
    BACKEND_UNKNOWN,
}

data class AttentionItem(
    val kind: AttentionKind,
    val referenceId: String?,
    val rank: Int,
)

class AttentionPlanner {
    fun plan(
        executions: List<ExecutionRecord>,
        cases: List<ReconciliationCase>,
        health: SystemHealth,
        sync: SyncSnapshot?,
    ): List<AttentionItem> = buildList {
        cases.filter { it.status == ReconciliationStatus.OPEN }.forEach { case ->
            add(AttentionItem(AttentionKind.RECONCILIATION, case.id, 0))
        }
        executions.filter { it.phase == ExecutionPhase.AWAITING_APPROVAL }.forEach { execution ->
            add(AttentionItem(AttentionKind.APPROVAL, execution.id.value, 1))
        }
        executions.filter {
            it.phase == ExecutionPhase.ERP_FAILURE || it.phase == ExecutionPhase.REJECTED
        }.take(3).forEach { execution ->
            add(AttentionItem(AttentionKind.FAILURE, execution.id.value, 2))
        }
        if (health.erp == HealthStatus.UNAVAILABLE) {
            add(AttentionItem(AttentionKind.ERP_UNAVAILABLE, null, 3))
        }
        if (sync?.state == SyncState.STALE || sync?.state == SyncState.FAILED || sync?.state == SyncState.OFFLINE) {
            add(AttentionItem(AttentionKind.SYNC_STALE, null, 4))
        }
        if (health.backend == HealthStatus.UNKNOWN && health.authentication != HealthStatus.HEALTHY) {
            add(AttentionItem(AttentionKind.BACKEND_UNKNOWN, null, 5))
        }
    }.sortedBy { it.rank }
}
