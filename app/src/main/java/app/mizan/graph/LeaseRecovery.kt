package app.mizan.graph

import app.mizan.domain.audit.AuditAppend
import app.mizan.domain.error.DispatchState
import app.mizan.domain.execution.ExecutionPhase
import app.mizan.domain.execution.RecoverableExecution
import app.mizan.domain.execution.RecoveryAction
import app.mizan.domain.model.ReconciliationCase
import app.mizan.domain.model.ReconciliationStatus
import app.mizan.domain.model.TenantId
import java.util.UUID

/**
 * Moves expired in-flight writes to reconciliation. Does not send them again.
 */
suspend fun recoverExpiredLeases(graph: AppGraph, tenantId: TenantId) {
    val now = graph.time.now()
    val records = graph.executions.page(tenantId, limit = 100, offset = 0)
    records.forEach { record ->
        val action = graph.recovery.decide(
            RecoverableExecution(record.phase, record.dispatch, record.leaseExpiresAt),
            now,
        )
        if (action is RecoveryAction.MoveTo) {
            graph.executions.upsert(
                record.copy(
                    phase = action.phase,
                    dispatch = if (record.dispatch == DispatchState.NOT_SENT) record.dispatch else DispatchState.UNKNOWN,
                    updatedAt = now,
                    errorCode = action.reasonCode,
                ),
            )
            if (action.phase == ExecutionPhase.RECONCILIATION_REQUIRED) {
                graph.cases.upsert(
                    ReconciliationCase(
                        id = "REC-" + UUID.randomUUID().toString().take(8).uppercase(),
                        executionId = record.id,
                        traceId = record.traceId,
                        tenantId = record.tenantId,
                        tool = record.tool,
                        intent = record.intent,
                        idempotencyKey = record.idempotencyKey,
                        candidateRecordIds = listOfNotNull(record.erpRecordId),
                        status = ReconciliationStatus.OPEN,
                        notes = action.reasonCode,
                        openedAt = now,
                    ),
                )
            }
            graph.audit.append(
                AuditAppend(
                    traceId = record.traceId.value,
                    tenantId = record.tenantId,
                    actorId = "system",
                    action = "LEASE_RECOVERY",
                    stateBefore = record.phase.name,
                    stateAfter = action.phase.name,
                    details = action.reasonCode,
                    timestampMillis = now.toEpochMilli(),
                ),
            )
        }
    }
}
