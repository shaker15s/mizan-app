package com.example.data

import com.example.connectors.Odoo19Json2Connector
import com.example.connectors.ErpNextConnector
import com.example.connectors.odoo.OdooXmlRpcRepository
import com.example.control.PolicyEngine
import com.example.data.local.AuditRecordEntity
import com.example.data.local.ErpCustomerEntity
import com.example.data.local.ErpOrderEntity
import com.example.data.local.ErpStockEntity
import com.example.data.local.ExecutionRecordEntity
import com.example.data.local.MizanDao
import com.example.data.local.ReconciliationItemEntity
import com.example.data.local.TrustReceiptEntity
import com.example.decision.DecisionService
import com.example.evidence.AuditChainManager
import com.example.evidence.ChainVerificationReport
import com.example.execution.ExecutionGateway
import com.example.execution.GatewayExecutionOutcome
import com.example.model.ApprovalLevel
import com.example.model.BoundedProposal
import com.example.model.IdentityPrincipal
import com.example.model.TenantInfo
import com.example.model.TrustReceipt
import com.example.model.UserRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class MizanRepository(
    private val dao: MizanDao
) {
    val policyEngine = PolicyEngine()
    val decisionService = DecisionService()
    val auditChainManager = AuditChainManager(dao)
    val odooXmlRpcRepository = OdooXmlRpcRepository.create(
        baseUrl = "https://alamal.odoo.com",
        defaultDatabase = "odoo_alamal_prod"
    )
    val odooConnector = Odoo19Json2Connector(dao = dao, xmlRpcRepository = odooXmlRpcRepository)
    val erpNextConnector = ErpNextConnector(dao)

    val gateway = ExecutionGateway(
        dao = dao,
        policyEngine = policyEngine,
        decisionService = decisionService,
        auditChainManager = auditChainManager,
        odooConnector = odooConnector,
        erpNextConnector = erpNextConnector
    )

    // Predefined Tenants
    val tenantA = TenantInfo(
        tenantId = "tenant-a",
        tenantNameEn = "Al-Amal Trading Co.",
        tenantNameAr = "شركة الأمل للتجارة",
        erpSystem = "Odoo 19 (JSON-2 API)",
        baseUrl = "https://alamal.odoo.com/jsonrpc/2",
        isSandbox = false
    )

    val tenantB = TenantInfo(
        tenantId = "tenant-b",
        tenantNameEn = "Nile Industrial Group",
        tenantNameAr = "مجموعة النيل الصناعية",
        erpSystem = "ERPNext (REST API)",
        baseUrl = "https://nile-ind.erpnext.com/api/v2",
        isSandbox = false
    )

    // Predefined Users
    val userSalesRep = IdentityPrincipal("USR-01", "Amr Kamel", "amr.kamel@alamal.com", UserRole.SALES_REP, "tenant-a")
    val userSalesManager = IdentityPrincipal("MGR-01", "Tarek El-Sayed", "tarek.m@alamal.com", UserRole.SALES_MANAGER, "tenant-a")
    val userFinanceApprover = IdentityPrincipal("FIN-01", "Noha Mansour", "noha.fin@alamal.com", UserRole.FINANCE_APPROVER, "tenant-a")
    val userAuditor = IdentityPrincipal("AUD-01", "Dr. Hisham Zaki", "auditor@compliance.org", UserRole.AUDITOR, "tenant-a")

    // Reactive streams
    fun getAuditRecords(tenantId: String): Flow<List<AuditRecordEntity>> = dao.getAuditRecordsByTenant(tenantId)
    fun getAllAuditRecords(): Flow<List<AuditRecordEntity>> = dao.getAllAuditRecords()
    fun getExecutions(tenantId: String): Flow<List<ExecutionRecordEntity>> = dao.getExecutionsByTenant(tenantId)
    fun getTrustReceipts(tenantId: String): Flow<List<TrustReceiptEntity>> = dao.getTrustReceiptsByTenant(tenantId)
    fun getAllTrustReceipts(): Flow<List<TrustReceiptEntity>> = dao.getAllTrustReceipts()
    fun getOrders(tenantId: String): Flow<List<ErpOrderEntity>> = dao.getOrdersByTenant(tenantId)
    fun getStock(tenantId: String): Flow<List<ErpStockEntity>> = dao.getStockByTenant(tenantId)
    fun getCustomers(tenantId: String): Flow<List<ErpCustomerEntity>> = dao.getCustomersByTenant(tenantId)
    fun getReconciliationItems(tenantId: String): Flow<List<ReconciliationItemEntity>> = dao.getReconciliationItemsByTenant(tenantId)

    suspend fun verifyChain(): ChainVerificationReport = auditChainManager.verifyChainIntegrity()

    suspend fun verifyChainWithProgress(
        onStep: suspend (progress: Float, inspectedHash: String, isValidSoFar: Boolean) -> Unit
    ): ChainVerificationReport = auditChainManager.verifyChainIntegrityWithProgress(onStep)

    fun simulateTamper(): ChainVerificationReport = auditChainManager.simulateTamperScenario()

    suspend fun createQuickErpOrder(
        tenantId: String,
        customerName: String,
        amount: Double,
        itemsSummary: String,
        actorId: String
    ): ErpOrderEntity {
        val nextNum = (100..999).random()
        val orderId = "SO-2026-$nextNum"
        val timestamp = System.currentTimeMillis()
        val order = ErpOrderEntity(
            orderId = orderId,
            tenantId = tenantId,
            customerName = customerName,
            dateCreated = timestamp,
            totalAmount = amount,
            currency = "USD",
            status = "sale",
            erpSystem = if (tenantId == "tenant-a") "Odoo 19 (JSON-2)" else "ERPNext (REST)",
            itemsSummary = itemsSummary,
            verifiedAt = timestamp
        )
        dao.insertOrder(order)

        auditChainManager.appendAuditRecord(
            traceId = "TRC-QUICK-${System.currentTimeMillis().toString().takeLast(6)}",
            tenantId = tenantId,
            actorId = actorId,
            action = "ODOO_QUICK_SALE_CONFIRMED",
            stateBefore = "draft",
            stateAfter = "sale",
            detailsJson = """{"orderId":"$orderId","customer":"$customerName","amount":$amount,"items":"$itemsSummary"}"""
        )
        return order
    }

    suspend fun resolveReconciliationItem(
        item: ReconciliationItemEntity,
        matchedErpId: String?,
        action: String // "MATCHED" or "ABANDONED"
    ) {
        val newStatus = if (action == "MATCHED") "MATCHED_AND_CLOSED" else "ABANDONED"
        val notes = if (action == "MATCHED") "Manually linked to confirmed ERP record $matchedErpId" else "Closed without mutation. Safe to retry."
        dao.updateReconciliationItem(
            item.copy(
                resolutionStatus = newStatus,
                resolutionNotes = notes
            )
        )
        auditChainManager.appendAuditRecord(
            traceId = item.traceId,
            tenantId = item.tenantId,
            actorId = "RECON_OPERATOR",
            action = "RECONCILIATION_RESOLVED",
            stateBefore = "RECONCILIATION_REQUIRED",
            stateAfter = newStatus,
            detailsJson = """{"reconId":"${item.reconciliationId}","action":"$action","matchedId":"$matchedErpId"}"""
        )
    }

    /**
     * Seeds realistic ERP enterprise data on initial startup
     */
    suspend fun seedInitialDataIfNeeded() {
        val existingOrders = dao.getOrdersByTenant("tenant-a").first()
        if (existingOrders.isNotEmpty()) return

        // Seed Customers for Tenant A
        val customersA = listOf(
            ErpCustomerEntity("CUST-001", "tenant-a", "Cairo Tech Solutions", "حلول القاهرة التقنية", 50000.0, 12400.0, "Active"),
            ErpCustomerEntity("CUST-002", "tenant-a", "Alexandria Logistics", "لوجستيات الإسكندرية", 100000.0, 38000.0, "Active"),
            ErpCustomerEntity("CUST-003", "tenant-a", "Delta Distribution Ltd", "دلتا للتوزيع المحدودة", 25000.0, 0.0, "Active")
        )
        dao.insertCustomers(customersA)

        // Seed Customers for Tenant B
        val customersB = listOf(
            ErpCustomerEntity("CUST-101", "tenant-b", "Red Sea Heavy Metals", "معادن البحر الأحمر الثقيلة", 200000.0, 85000.0, "Active"),
            ErpCustomerEntity("CUST-102", "tenant-b", "Sinai Concrete Corp", "خرسانة سيناء للصناعة", 150000.0, 12000.0, "Active")
        )
        dao.insertCustomers(customersB)

        // Seed Inventory for Tenant A
        val stocksA = listOf(
            ErpStockEntity("SKU-SRV-01", "tenant-a", "Enterprise Server Blade 2U", "خادم مؤسسي Blade 2U", 18, 4, 3200.0, "Cairo Central WH"),
            ErpStockEntity("SKU-SW-PRO", "tenant-a", "Managed Core Switch 48P", "مقسم شبكة رئيسي 48 منفذ", 35, 10, 1450.0, "Cairo Central WH"),
            ErpStockEntity("SKU-UPS-10K", "tenant-a", "Smart UPS Online 10kVA", "وحدة طاقة احتياطية 10kVA", 12, 2, 2800.0, "Alexandria WH"),
            ErpStockEntity("SKU-CBL-FBR", "tenant-a", "Armored Fiber Cable 500m", "كابل ألياف ضوئية مدرع 500م", 80, 0, 420.0, "Cairo Central WH")
        )
        dao.insertStocks(stocksA)

        // Seed Inventory for Tenant B
        val stocksB = listOf(
            ErpStockEntity("SKU-STL-20MM", "tenant-b", "High-Tensile Steel Rebar 20mm", "حديد تسليح عالي المقاومة 20مم", 450, 50, 840.0, "Suez Warehouse"),
            ErpStockEntity("SKU-ALUM-PLT", "tenant-b", "Structural Aluminum Plate 6mm", "ألواح ألومنيوم هيكلية 6مم", 120, 20, 1150.0, "10th Ramadan Plant")
        )
        dao.insertStocks(stocksB)

        // Seed Initial Orders for Tenant A
        val initialOrdersA = listOf(
            ErpOrderEntity(
                orderId = "SO-2026-088",
                tenantId = "tenant-a",
                customerName = "Cairo Tech Solutions",
                dateCreated = System.currentTimeMillis() - 86400000L * 2,
                totalAmount = 6400.0,
                currency = "USD",
                status = "sale",
                erpSystem = "Odoo 19 (JSON-2)",
                itemsSummary = "Enterprise Server Blade 2U x 2",
                verifiedAt = System.currentTimeMillis() - 86400000L * 2
            ),
            ErpOrderEntity(
                orderId = "SO-2026-094",
                tenantId = "tenant-a",
                customerName = "Alexandria Logistics",
                dateCreated = System.currentTimeMillis() - 86400000L,
                totalAmount = 14500.0,
                currency = "USD",
                status = "draft",
                erpSystem = "Odoo 19 (JSON-2)",
                itemsSummary = "Managed Core Switch 48P x 10",
                verifiedAt = System.currentTimeMillis() - 86400000L
            )
        )
        initialOrdersA.forEach { dao.insertOrder(it) }

        // Genesis block in audit chain
        auditChainManager.appendAuditRecord(
            traceId = "TRC-GENESIS",
            tenantId = "system",
            actorId = "SYSTEM_INITIALIZER",
            action = "GENESIS_LEDGER_MINT",
            stateBefore = "EMPTY",
            stateAfter = "ACTIVE_CHAIN",
            detailsJson = """{"version":"MIZAN-2026-BLUEPRINT","engine":"SHA-256"}"""
        )

        // Seed initial audit trail for tenant A demonstrating Odoo connection & operations
        auditChainManager.appendAuditRecord(
            traceId = "TRC-ODOO-INIT-01",
            tenantId = "tenant-a",
            actorId = "USR-01",
            action = "ODOO_SESSION_AUTHENTICATE",
            stateBefore = "DISCONNECTED",
            stateAfter = "AUTHENTICATED",
            detailsJson = """{"database":"odoo_alamal_prod","protocol":"XML-RPC 2.0","uid":2,"serverVersion":"19.0+e"}"""
        )
        auditChainManager.appendAuditRecord(
            traceId = "TRC-ORDER-SYNC-02",
            tenantId = "tenant-a",
            actorId = "USR-01",
            action = "ERP_ORDER_SYNCED",
            stateBefore = "LOCAL_DRAFT",
            stateAfter = "CONFIRMED_IN_ODOO",
            detailsJson = """{"orderId":"SO-2026-088","amount":6400.0,"customer":"Cairo Tech Solutions","status":"sale"}"""
        )
        auditChainManager.appendAuditRecord(
            traceId = "TRC-POLICY-EVAL-03",
            tenantId = "tenant-a",
            actorId = "MGR-01",
            action = "POLICY_EVALUATION_PASS",
            stateBefore = "PENDING_APPROVAL",
            stateAfter = "APPROVED_BY_MANAGER",
            detailsJson = """{"proposalId":"PROP-9912","rule":"RULE_DUAL_APPROVAL_GT_5K","amount":14500.0}"""
        )
        auditChainManager.appendAuditRecord(
            traceId = "TRC-STOCK-VERIFY-04",
            tenantId = "tenant-a",
            actorId = "SYSTEM_WATCHDOG",
            action = "INVENTORY_LEVEL_RECONCILED",
            stateBefore = "VERIFYING",
            stateAfter = "BALANCED",
            detailsJson = """{"sku":"SKU-SRV-01","warehouse":"Cairo Central WH","qtyAvailable":18}"""
        )

        // Seed default active Odoo XML-RPC session
        odooXmlRpcRepository.restoreSession(
            com.example.connectors.odoo.OdooSession(
                database = "odoo_alamal_prod",
                username = "admin@alamal.com",
                apiKeyOrPassword = "odoo_sec_key_449102830192_tenant_a",
                uid = 2,
                serverVersion = "19.0+e (Enterprise)",
                authenticatedAt = System.currentTimeMillis() - 7200000L
            )
        )
    }
}
