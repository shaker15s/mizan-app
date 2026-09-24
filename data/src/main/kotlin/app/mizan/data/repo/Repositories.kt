package app.mizan.data.repo

import app.mizan.data.local.CustomerEntity
import app.mizan.data.local.MizanDao
import app.mizan.data.local.OrderEntity
import app.mizan.data.local.StockEntity
import app.mizan.data.local.SyncEntity
import app.mizan.domain.audit.AuditAppend
import app.mizan.domain.audit.AuditEvent
import app.mizan.domain.audit.AuditHasher
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
import app.mizan.domain.store.AuditStore
import app.mizan.domain.store.ExecutionStore
import app.mizan.domain.store.LocalSearchHits
import app.mizan.domain.store.ReadModelStore
import app.mizan.domain.store.ReceiptStore
import app.mizan.domain.store.ReconciliationStore
import app.mizan.domain.store.SyncStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomExecutionStore(private val dao: MizanDao) : ExecutionStore {
    override fun observe(tenantId: TenantId, limit: Int): Flow<List<ExecutionRecord>> =
        dao.observeExecutions(tenantId.value, limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun page(tenantId: TenantId, limit: Int, offset: Int): List<ExecutionRecord> =
        dao.pageExecutions(tenantId.value, limit, offset).map { it.toDomain() }

    override suspend fun get(tenantId: TenantId, id: ExecutionId): ExecutionRecord? =
        dao.execution(tenantId.value, id.value)?.toDomain()

    override suspend fun findByIdempotency(tenantId: TenantId, key: IdempotencyKey): ExecutionRecord? =
        dao.executionByKey(tenantId.value, key.value)?.toDomain()

    override suspend fun upsert(record: ExecutionRecord) {
        require(record.tenantId.value.isNotBlank())
        dao.upsertExecution(record.toEntity())
    }
}

class RoomReceiptStore(private val dao: MizanDao) : ReceiptStore {
    override fun observe(tenantId: TenantId, limit: Int): Flow<List<TrustReceipt>> =
        dao.observeReceipts(tenantId.value, limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun page(tenantId: TenantId, limit: Int, offset: Int): List<TrustReceipt> =
        dao.pageReceipts(tenantId.value, limit, offset).map { it.toDomain() }

    override suspend fun get(tenantId: TenantId, id: ReceiptId): TrustReceipt? =
        dao.receipt(tenantId.value, id.value)?.toDomain()

    override suspend fun insert(receipt: TrustReceipt) {
        dao.insertReceipt(receipt.toEntity())
    }
}

class RoomAuditStore(private val dao: MizanDao) : AuditStore {
    override suspend fun latest(tenantId: TenantId): AuditEvent? =
        dao.latestAudit(tenantId.value)?.toDomain()

    override suspend fun page(tenantId: TenantId, limit: Int, offset: Int): List<AuditEvent> =
        dao.pageAudit(tenantId.value, limit, offset).map { it.toDomain() }

    override suspend fun ledger(tenantId: TenantId): List<AuditEvent> =
        dao.ledger(tenantId.value).map { it.toDomain() }

    override suspend fun append(event: AuditAppend): AuditEvent {
        val latest = dao.latestAudit(event.tenantId.value)
        val index = (latest?.chainIndex ?: 0L) + 1L
        val previous = latest?.currentHash ?: AuditHasher.GENESIS
        val shell = AuditEvent(
            chainIndex = index,
            timestampMillis = event.timestampMillis,
            traceId = event.traceId,
            tenantId = event.tenantId.value,
            actorId = event.actorId,
            action = event.action,
            stateBefore = event.stateBefore,
            stateAfter = event.stateAfter,
            details = event.details,
            previousHash = previous,
            currentHash = "",
        )
        val hashed = shell.copy(currentHash = AuditHasher.hash(shell, previous))
        dao.insertAudit(
            app.mizan.data.local.AuditEventEntity(
                chainIndex = hashed.chainIndex,
                timestampMillis = hashed.timestampMillis,
                traceId = hashed.traceId,
                tenantId = hashed.tenantId,
                actorId = hashed.actorId,
                action = hashed.action,
                stateBefore = hashed.stateBefore,
                stateAfter = hashed.stateAfter,
                details = hashed.details,
                previousHash = hashed.previousHash,
                currentHash = hashed.currentHash,
                integrityClass = hashed.integrityClass.name,
            ),
        )
        return hashed
    }

    override suspend fun search(tenantId: TenantId, query: String, limit: Int): List<AuditEvent> =
        dao.searchAudit(tenantId.value, query.take(80), limit).map { it.toDomain() }
}

class RoomReconciliationStore(private val dao: MizanDao) : ReconciliationStore {
    override fun observe(tenantId: TenantId): Flow<List<ReconciliationCase>> =
        dao.observeCases(tenantId.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun get(tenantId: TenantId, id: String): ReconciliationCase? =
        dao.caseById(tenantId.value, id)?.toDomain()

    override suspend fun upsert(case: ReconciliationCase) {
        dao.upsertCase(case.toEntity())
    }
}

class RoomReadModelStore(
    private val dao: MizanDao,
    private val receipts: ReceiptStore,
    private val executions: ExecutionStore,
    private val cases: ReconciliationStore,
) : ReadModelStore {
    override fun orders(tenantId: TenantId): Flow<List<CachedOrder>> =
        dao.observeOrders(tenantId.value).map { rows -> rows.map { it.toDomain() } }

    override fun customers(tenantId: TenantId): Flow<List<CachedCustomer>> =
        dao.observeCustomers(tenantId.value).map { rows -> rows.map { it.toDomain() } }

    override fun stock(tenantId: TenantId): Flow<List<CachedStock>> =
        dao.observeStock(tenantId.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun search(tenantId: TenantId, query: String, limit: Int): LocalSearchHits {
        val needle = query.trim()
        if (needle.length < 2) {
            return LocalSearchHits(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        return LocalSearchHits(
            orders = dao.searchOrders(tenantId.value, needle, limit).map { it.toDomain() },
            customers = dao.searchCustomers(tenantId.value, needle, limit).map { it.toDomain() },
            stock = dao.searchStock(tenantId.value, needle, limit).map { it.toDomain() },
            receipts = receipts.page(tenantId, 40, 0).filter {
                it.id.value.contains(needle, true) || it.erpRecordId?.contains(needle, true) == true
            },
            executions = executions.page(tenantId, 40, 0).filter {
                it.intent.contains(needle, true) || it.id.value.contains(needle, true)
            },
            cases = dao.searchCases(tenantId.value, needle, limit).map { it.toDomain() },
        )
    }

    override suspend fun order(tenantId: TenantId, id: String): CachedOrder? =
        dao.order(tenantId.value, id)?.toDomain()

    override suspend fun upsertOrder(order: CachedOrder) {
        dao.upsertOrder(
            OrderEntity(
                orderId = order.id,
                tenantId = order.tenantId.value,
                customerName = order.customerName,
                amountMinor = order.amount.minorUnits,
                currency = order.amount.currency,
                status = order.status,
                summary = order.summary,
                origin = order.origin.name,
                updatedAt = order.updatedAt.toEpochMilli(),
            ),
        )
    }

    override suspend fun upsertCustomers(customers: List<CachedCustomer>) {
        dao.upsertCustomers(
            customers.map { customer ->
                CustomerEntity(
                    customerId = customer.id,
                    tenantId = customer.tenantId.value,
                    name = customer.name,
                    creditMinor = customer.creditLimit?.minorUnits,
                    balanceMinor = customer.balance?.minorUnits,
                    currency = customer.creditLimit?.currency ?: customer.balance?.currency,
                    status = customer.status,
                    origin = customer.origin.name,
                )
            },
        )
    }

    override suspend fun upsertStock(stock: List<CachedStock>) {
        dao.upsertStock(
            stock.map { item ->
                StockEntity(
                    sku = item.sku,
                    tenantId = item.tenantId.value,
                    name = item.name,
                    availableQty = item.availableQty,
                    reservedQty = item.reservedQty,
                    priceMinor = item.unitPrice?.minorUnits,
                    currency = item.unitPrice?.currency,
                    location = item.location,
                    origin = item.origin.name,
                )
            },
        )
    }
}

class RoomSyncStore(private val dao: MizanDao) : SyncStore {
    override suspend fun get(tenantId: TenantId): SyncSnapshot? = dao.sync(tenantId.value)?.toDomain()

    override suspend fun save(snapshot: SyncSnapshot) {
        dao.upsertSync(
            SyncEntity(
                tenantId = snapshot.tenantId.value,
                lastSuccessfulSync = snapshot.lastSuccessfulSync?.toEpochMilli(),
                lastAttempt = snapshot.lastAttempt?.toEpochMilli(),
                state = snapshot.state.name,
                pendingChanges = snapshot.pendingChanges,
                failedChanges = snapshot.failedChanges,
                conflicts = snapshot.conflicts,
                serverCursor = snapshot.serverCursor,
            ),
        )
    }
}
