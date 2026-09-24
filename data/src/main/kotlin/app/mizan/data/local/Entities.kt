package app.mizan.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "executions",
    indices = [
        Index("tenantId"),
        Index("updatedAt"),
        Index("phase"),
        Index("traceId"),
        Index("idempotencyKey"),
    ],
)
data class ExecutionEntity(
    @PrimaryKey val executionId: String,
    val traceId: String,
    val proposalId: String,
    val tenantId: String,
    val initiatorId: String,
    val toolName: String,
    val toolVersion: String,
    val intent: String,
    val phase: String,
    val idempotencyKey: String,
    val canonicalArgs: String,
    val amountMinor: Long?,
    val currency: String?,
    val approval: String,
    val riskTier: String,
    val policyRuleId: String,
    val approverIds: String,
    val erpRecordId: String?,
    val erpModel: String?,
    val dispatch: String,
    val leaseExpiresAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val errorCode: String?,
    val origin: String,
)

@Entity(
    tableName = "receipts",
    indices = [Index("tenantId"), Index("traceId"), Index("executionId"), Index("createdAt")],
)
data class ReceiptEntity(
    @PrimaryKey val receiptId: String,
    val traceId: String,
    val executionId: String,
    val tenantId: String,
    val tenantLabel: String,
    val initiatorId: String,
    val initiatorLabel: String,
    val approverIds: String,
    val approverLabels: String,
    val toolName: String,
    val toolVersion: String,
    val policyRuleId: String,
    val approval: String,
    val riskTier: String,
    val canonicalArgs: String,
    val idempotencyKey: String,
    val erpRecordId: String?,
    val erpModel: String?,
    val verification: String,
    val integrityClass: String,
    val origin: String,
    val createdAt: Long,
    val auditChainIndex: Long?,
)

@Entity(
    tableName = "audit_events",
    indices = [Index("tenantId"), Index("timestampMillis"), Index("traceId"), Index("chainIndex")],
)
data class AuditEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chainIndex: Long,
    val timestampMillis: Long,
    val traceId: String,
    val tenantId: String,
    val actorId: String,
    val action: String,
    val stateBefore: String,
    val stateAfter: String,
    val details: String,
    val previousHash: String,
    val currentHash: String,
    val integrityClass: String,
)

@Entity(
    tableName = "reconciliation_cases",
    indices = [Index("tenantId"), Index("status"), Index("executionId")],
)
data class ReconciliationEntity(
    @PrimaryKey val caseId: String,
    val executionId: String,
    val traceId: String,
    val tenantId: String,
    val toolName: String,
    val intent: String,
    val idempotencyKey: String,
    val candidateRecordIds: String,
    val status: String,
    val notes: String?,
    val openedAt: Long,
)

@Entity(
    tableName = "cached_orders",
    primaryKeys = ["orderId", "tenantId"],
    indices = [Index("tenantId"), Index("updatedAt")],
)
data class OrderEntity(
    val orderId: String,
    val tenantId: String,
    val customerName: String,
    val amountMinor: Long,
    val currency: String,
    val status: String,
    val summary: String,
    val origin: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "cached_customers",
    primaryKeys = ["customerId", "tenantId"],
    indices = [Index("tenantId")],
)
data class CustomerEntity(
    val customerId: String,
    val tenantId: String,
    val name: String,
    val creditMinor: Long?,
    val balanceMinor: Long?,
    val currency: String?,
    val status: String,
    val origin: String,
)

@Entity(
    tableName = "cached_stock",
    primaryKeys = ["sku", "tenantId"],
    indices = [Index("tenantId")],
)
data class StockEntity(
    val sku: String,
    val tenantId: String,
    val name: String,
    val availableQty: Int,
    val reservedQty: Int,
    val priceMinor: Long?,
    val currency: String?,
    val location: String,
    val origin: String,
)

@Entity(tableName = "sync_meta")
data class SyncEntity(
    @PrimaryKey val tenantId: String,
    val lastSuccessfulSync: Long?,
    val lastAttempt: Long?,
    val state: String,
    val pendingChanges: Int,
    val failedChanges: Int,
    val conflicts: Int,
    val serverCursor: String?,
)
