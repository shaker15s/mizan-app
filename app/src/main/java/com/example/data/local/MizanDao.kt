package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MizanDao {

    // --- Audit Records ---
    @Query("SELECT * FROM audit_records ORDER BY chainIndex DESC")
    fun getAllAuditRecords(): Flow<List<AuditRecordEntity>>

    @Query("SELECT * FROM audit_records WHERE tenantId = :tenantId ORDER BY chainIndex DESC")
    fun getAuditRecordsByTenant(tenantId: String): Flow<List<AuditRecordEntity>>

    @Query("SELECT * FROM audit_records ORDER BY chainIndex ASC")
    suspend fun getAuditLedgerAsc(): List<AuditRecordEntity>

    @Query("SELECT * FROM audit_records ORDER BY chainIndex DESC LIMIT 1")
    suspend fun getLatestAuditRecord(): AuditRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditRecord(record: AuditRecordEntity): Long

    // --- Executions ---
    @Query("SELECT * FROM execution_records ORDER BY timestamp DESC")
    fun getAllExecutions(): Flow<List<ExecutionRecordEntity>>

    @Query("SELECT * FROM execution_records WHERE tenantId = :tenantId ORDER BY timestamp DESC")
    fun getExecutionsByTenant(tenantId: String): Flow<List<ExecutionRecordEntity>>

    @Query("SELECT * FROM execution_records WHERE executionId = :id")
    suspend fun getExecutionById(id: String): ExecutionRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExecution(execution: ExecutionRecordEntity)

    @Update
    suspend fun updateExecution(execution: ExecutionRecordEntity)

    // --- Trust Receipts ---
    @Query("SELECT * FROM trust_receipts ORDER BY timestamp DESC")
    fun getAllTrustReceipts(): Flow<List<TrustReceiptEntity>>

    @Query("SELECT * FROM trust_receipts WHERE tenantId = :tenantId ORDER BY timestamp DESC")
    fun getTrustReceiptsByTenant(tenantId: String): Flow<List<TrustReceiptEntity>>

    @Query("SELECT * FROM trust_receipts WHERE receiptId = :receiptId")
    suspend fun getTrustReceiptById(receiptId: String): TrustReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrustReceipt(receipt: TrustReceiptEntity)

    // --- ERP Orders ---
    @Query("SELECT * FROM erp_orders WHERE tenantId = :tenantId ORDER BY dateCreated DESC")
    fun getOrdersByTenant(tenantId: String): Flow<List<ErpOrderEntity>>

    @Query("SELECT * FROM erp_orders WHERE orderId = :orderId")
    suspend fun getOrderById(orderId: String): ErpOrderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: ErpOrderEntity)

    @Update
    suspend fun updateOrder(order: ErpOrderEntity)

    // --- Stock & Inventory ---
    @Query("SELECT * FROM erp_stocks WHERE tenantId = :tenantId ORDER BY availableQty ASC")
    fun getStockByTenant(tenantId: String): Flow<List<ErpStockEntity>>

    @Query("SELECT * FROM erp_stocks WHERE productSku = :sku AND tenantId = :tenantId")
    suspend fun getStockBySku(sku: String, tenantId: String): ErpStockEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStock(stock: ErpStockEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStocks(stocks: List<ErpStockEntity>)

    @Update
    suspend fun updateStock(stock: ErpStockEntity)

    // --- Customers ---
    @Query("SELECT * FROM erp_customers WHERE tenantId = :tenantId")
    fun getCustomersByTenant(tenantId: String): Flow<List<ErpCustomerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomers(customers: List<ErpCustomerEntity>)

    // --- Reconciliation Items ---
    @Query("SELECT * FROM reconciliation_items WHERE tenantId = :tenantId ORDER BY timestamp DESC")
    fun getReconciliationItemsByTenant(tenantId: String): Flow<List<ReconciliationItemEntity>>

    @Query("SELECT * FROM reconciliation_items ORDER BY timestamp DESC")
    fun getAllReconciliationItems(): Flow<List<ReconciliationItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReconciliationItem(item: ReconciliationItemEntity)

    @Update
    suspend fun updateReconciliationItem(item: ReconciliationItemEntity)
}
