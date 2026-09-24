package app.mizan.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MizanDao {
    @Query("SELECT * FROM executions WHERE tenantId = :tenantId ORDER BY updatedAt DESC LIMIT :limit")
    fun observeExecutions(tenantId: String, limit: Int): Flow<List<ExecutionEntity>>

    @Query(
        "SELECT * FROM executions WHERE tenantId = :tenantId ORDER BY updatedAt DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun pageExecutions(tenantId: String, limit: Int, offset: Int): List<ExecutionEntity>

    @Query("SELECT * FROM executions WHERE tenantId = :tenantId AND executionId = :id")
    suspend fun execution(tenantId: String, id: String): ExecutionEntity?

    @Query("SELECT * FROM executions WHERE tenantId = :tenantId AND idempotencyKey = :key LIMIT 1")
    suspend fun executionByKey(tenantId: String, key: String): ExecutionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExecution(entity: ExecutionEntity)

    @Query("SELECT * FROM receipts WHERE tenantId = :tenantId ORDER BY createdAt DESC LIMIT :limit")
    fun observeReceipts(tenantId: String, limit: Int): Flow<List<ReceiptEntity>>

    @Query(
        "SELECT * FROM receipts WHERE tenantId = :tenantId ORDER BY createdAt DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun pageReceipts(tenantId: String, limit: Int, offset: Int): List<ReceiptEntity>

    @Query("SELECT * FROM receipts WHERE tenantId = :tenantId AND receiptId = :id")
    suspend fun receipt(tenantId: String, id: String): ReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceipt(entity: ReceiptEntity)

    @Query("SELECT * FROM audit_events WHERE tenantId = :tenantId ORDER BY chainIndex DESC LIMIT 1")
    suspend fun latestAudit(tenantId: String): AuditEventEntity?

    @Query(
        "SELECT * FROM audit_events WHERE tenantId = :tenantId ORDER BY chainIndex DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun pageAudit(tenantId: String, limit: Int, offset: Int): List<AuditEventEntity>

    @Query("SELECT * FROM audit_events WHERE tenantId = :tenantId ORDER BY chainIndex ASC")
    suspend fun ledger(tenantId: String): List<AuditEventEntity>

    @Query(
        """
        SELECT * FROM audit_events
        WHERE tenantId = :tenantId AND (
            traceId LIKE '%' || :query || '%' OR
            action LIKE '%' || :query || '%' OR
            actorId LIKE '%' || :query || '%'
        )
        ORDER BY chainIndex DESC LIMIT :limit
        """,
    )
    suspend fun searchAudit(tenantId: String, query: String, limit: Int): List<AuditEventEntity>

    @Insert
    suspend fun insertAudit(entity: AuditEventEntity): Long

    @Query("SELECT * FROM reconciliation_cases WHERE tenantId = :tenantId ORDER BY openedAt DESC")
    fun observeCases(tenantId: String): Flow<List<ReconciliationEntity>>

    @Query("SELECT * FROM reconciliation_cases WHERE tenantId = :tenantId AND caseId = :id")
    suspend fun caseById(tenantId: String, id: String): ReconciliationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCase(entity: ReconciliationEntity)

    @Query("SELECT * FROM cached_orders WHERE tenantId = :tenantId ORDER BY updatedAt DESC")
    fun observeOrders(tenantId: String): Flow<List<OrderEntity>>

    @Query("SELECT * FROM cached_orders WHERE tenantId = :tenantId AND orderId = :id")
    suspend fun order(tenantId: String, id: String): OrderEntity?

    @Query("SELECT * FROM cached_customers WHERE tenantId = :tenantId ORDER BY name")
    fun observeCustomers(tenantId: String): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM cached_stock WHERE tenantId = :tenantId ORDER BY sku")
    fun observeStock(tenantId: String): Flow<List<StockEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrder(entity: OrderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomers(entities: List<CustomerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStock(entities: List<StockEntity>)

    @Query(
        """
        SELECT * FROM cached_orders WHERE tenantId = :tenantId AND
        (customerName LIKE '%' || :query || '%' OR orderId LIKE '%' || :query || '%')
        LIMIT :limit
        """,
    )
    suspend fun searchOrders(tenantId: String, query: String, limit: Int): List<OrderEntity>

    @Query(
        """
        SELECT * FROM cached_customers WHERE tenantId = :tenantId AND
        (name LIKE '%' || :query || '%' OR customerId LIKE '%' || :query || '%')
        LIMIT :limit
        """,
    )
    suspend fun searchCustomers(tenantId: String, query: String, limit: Int): List<CustomerEntity>

    @Query(
        """
        SELECT * FROM cached_stock WHERE tenantId = :tenantId AND
        (name LIKE '%' || :query || '%' OR sku LIKE '%' || :query || '%')
        LIMIT :limit
        """,
    )
    suspend fun searchStock(tenantId: String, query: String, limit: Int): List<StockEntity>

    @Query(
        """
        SELECT * FROM reconciliation_cases WHERE tenantId = :tenantId AND
        (intent LIKE '%' || :query || '%' OR caseId LIKE '%' || :query || '%' OR executionId LIKE '%' || :query || '%')
        LIMIT :limit
        """,
    )
    suspend fun searchCases(tenantId: String, query: String, limit: Int): List<ReconciliationEntity>

    @Query("SELECT * FROM sync_meta WHERE tenantId = :tenantId")
    suspend fun sync(tenantId: String): SyncEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSync(entity: SyncEntity)

    @Query("SELECT COUNT(*) FROM cached_orders WHERE tenantId = :tenantId")
    suspend fun orderCount(tenantId: String): Int
}
