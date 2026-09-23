package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audit_records")
data class AuditRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chainIndex: Long,
    val timestamp: Long,
    val traceId: String,
    val tenantId: String,
    val actorId: String,
    val action: String,
    val stateBefore: String,
    val stateAfter: String,
    val detailsJson: String,
    val previousHash: String,
    val currentHash: String
)

@Entity(tableName = "execution_records")
data class ExecutionRecordEntity(
    @PrimaryKey val executionId: String,
    val traceId: String,
    val timestamp: Long,
    val tenantId: String,
    val initiatorId: String,
    val toolName: String,
    val rawIntent: String,
    val currentState: String, // ExecutionState enum name
    val idempotencyKey: String,
    val leaseId: String?,
    val leaseExpiresAt: Long?,
    val approverId: String?,
    val erpRecordId: String?,
    val financialAmount: Double,
    val riskTier: String,
    val approvalLevel: String,
    val errorMessage: String? = null
)

@Entity(tableName = "trust_receipts")
data class TrustReceiptEntity(
    @PrimaryKey val receiptId: String,
    val traceId: String,
    val executionId: String,
    val timestamp: Long,
    val tenantId: String,
    val tenantName: String,
    val initiatorId: String,
    val initiatorName: String,
    val initiatorRole: String,
    val intent: String,
    val toolName: String,
    val toolVersion: String,
    val policyRuleId: String,
    val approvalLevel: String,
    val riskTier: String,
    val approverId: String,
    val approverName: String,
    val sodProof: String,
    val canonicalArgumentsJson: String,
    val idempotencyKey: String,
    val erpRecordId: String,
    val erpModel: String,
    val verificationHash: String,
    val auditChainIndex: Long,
    val tamperProofToken: String
)

@Entity(tableName = "erp_orders")
data class ErpOrderEntity(
    @PrimaryKey val orderId: String,
    val tenantId: String,
    val customerName: String,
    val dateCreated: Long,
    val totalAmount: Double,
    val currency: String,
    val status: String, // "draft", "sale", "cancelled"
    val erpSystem: String, // "Odoo 19" or "ERPNext"
    val itemsSummary: String,
    val verifiedAt: Long?
)

@Entity(tableName = "erp_stocks")
data class ErpStockEntity(
    @PrimaryKey val productSku: String,
    val tenantId: String,
    val productNameEn: String,
    val productNameAr: String,
    val availableQty: Int,
    val reservedQty: Int,
    val unitPrice: Double,
    val location: String
)

@Entity(tableName = "erp_customers")
data class ErpCustomerEntity(
    @PrimaryKey val customerId: String,
    val tenantId: String,
    val nameEn: String,
    val nameAr: String,
    val creditLimit: Double,
    val currentBalance: Double,
    val status: String
)

@Entity(tableName = "reconciliation_items")
data class ReconciliationItemEntity(
    @PrimaryKey val reconciliationId: String,
    val executionId: String,
    val traceId: String,
    val tenantId: String,
    val toolName: String,
    val timestamp: Long,
    val idempotencyKey: String,
    val intent: String,
    val payloadJson: String,
    val candidateErpIds: String, // Comma-separated possible ERP matches
    val resolutionStatus: String, // "PENDING", "MATCHED_AND_CLOSED", "ABANDONED"
    val resolutionNotes: String? = null
)
