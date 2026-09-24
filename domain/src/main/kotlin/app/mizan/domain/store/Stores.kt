package app.mizan.domain.store

import app.mizan.domain.audit.AuditAppend
import app.mizan.domain.audit.AuditEvent
import app.mizan.domain.model.CachedCustomer
import app.mizan.domain.model.CachedOrder
import app.mizan.domain.model.CachedStock
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.ExecutionRecord
import app.mizan.domain.model.IdempotencyKey
import app.mizan.domain.model.ReceiptId
import app.mizan.domain.model.ReconciliationCase
import app.mizan.domain.model.SyncSnapshot
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.TrustReceipt
import kotlinx.coroutines.flow.Flow

/**
 * Every read and write that a user can reach takes a tenant.
 * There is no unscoped "list everything" on these interfaces.
 */
interface ExecutionStore {
    fun observe(tenantId: TenantId, limit: Int = 50): Flow<List<ExecutionRecord>>
    suspend fun page(tenantId: TenantId, limit: Int, offset: Int): List<ExecutionRecord>
    suspend fun get(tenantId: TenantId, id: ExecutionId): ExecutionRecord?
    suspend fun findByIdempotency(tenantId: TenantId, key: IdempotencyKey): ExecutionRecord?
    suspend fun upsert(record: ExecutionRecord)
}

interface ReceiptStore {
    fun observe(tenantId: TenantId, limit: Int = 40): Flow<List<TrustReceipt>>
    suspend fun page(tenantId: TenantId, limit: Int, offset: Int): List<TrustReceipt>
    suspend fun get(tenantId: TenantId, id: ReceiptId): TrustReceipt?
    suspend fun insert(receipt: TrustReceipt)
}

interface AuditStore {
    suspend fun latest(tenantId: TenantId): AuditEvent?
    suspend fun page(tenantId: TenantId, limit: Int, offset: Int): List<AuditEvent>
    suspend fun ledger(tenantId: TenantId): List<AuditEvent>
    suspend fun append(event: AuditAppend): AuditEvent
    suspend fun search(tenantId: TenantId, query: String, limit: Int): List<AuditEvent>
}

interface ReconciliationStore {
    fun observe(tenantId: TenantId): Flow<List<ReconciliationCase>>
    suspend fun get(tenantId: TenantId, id: String): ReconciliationCase?
    suspend fun upsert(case: ReconciliationCase)
}

interface ReadModelStore {
    fun orders(tenantId: TenantId): Flow<List<CachedOrder>>
    fun customers(tenantId: TenantId): Flow<List<CachedCustomer>>
    fun stock(tenantId: TenantId): Flow<List<CachedStock>>
    suspend fun search(tenantId: TenantId, query: String, limit: Int): LocalSearchHits
    suspend fun order(tenantId: TenantId, id: String): CachedOrder?
    suspend fun upsertOrder(order: CachedOrder)
    suspend fun upsertCustomers(customers: List<CachedCustomer>)
    suspend fun upsertStock(stock: List<CachedStock>)
}

data class LocalSearchHits(
    val orders: List<CachedOrder>,
    val customers: List<CachedCustomer>,
    val stock: List<CachedStock>,
    val receipts: List<TrustReceipt>,
    val executions: List<ExecutionRecord>,
    val cases: List<ReconciliationCase>,
)

interface SyncStore {
    suspend fun get(tenantId: TenantId): SyncSnapshot?
    suspend fun save(snapshot: SyncSnapshot)
}

/**
 * Cross-tenant reads are not on the store interfaces.
 * A future server-issued auditor grant would be a separate type,
 * checked before any unscoped query is even added.
 */
object TenantBoundary {
    const val DEFAULT_DENY_CROSS_TENANT = true
}
