package com.example.control

import com.example.model.ApprovalLevel
import com.example.model.BoundedProposal
import com.example.model.IdentityPrincipal
import com.example.model.RiskTier
import com.example.model.ToolContract
import com.example.model.UserRole

data class PolicyEvaluationResult(
    val allowed: Boolean,
    val requiredApprovalLevel: ApprovalLevel,
    val riskTier: RiskTier,
    val ruleId: String,
    val reasonEn: String,
    val reasonAr: String,
    val requiresSeparationOfDuties: Boolean
)

data class BlastRadiusPreview(
    val toolName: String,
    val affectedCustomer: String,
    val affectedUnits: Int,
    val estimatedFinancialExposure: Double,
    val approvalTier: ApprovalLevel,
    val requiresManagerSignoff: Boolean,
    val riskSummaryEn: String,
    val riskSummaryAr: String
)

class PolicyEngine {

    companion object {
        val TOOL_REGISTRY = listOf(
            ToolContract(
                name = "stock.availability",
                version = "1.0.0",
                purposeEn = "Check on-hand warehouse inventory and reservation status",
                purposeAr = "فحص المخزون الفعلي والمحجوز بالمستودع",
                isReadOnly = true,
                isDestructive = false,
                defaultApproval = ApprovalLevel.L0_NONE,
                risk = RiskTier.R0_READ,
                requiredScopes = listOf("stock.read")
            ),
            ToolContract(
                name = "customer.search",
                version = "1.0.0",
                purposeEn = "Search customer master data and credit limits",
                purposeAr = "البحث في بيانات العملاء والحدود الائتمانية",
                isReadOnly = true,
                isDestructive = false,
                defaultApproval = ApprovalLevel.L0_NONE,
                risk = RiskTier.R0_READ,
                requiredScopes = listOf("customer.read")
            ),
            ToolContract(
                name = "sales.order.create_draft",
                version = "2.1.0",
                purposeEn = "Create a bounded sales order draft in Odoo/ERP",
                purposeAr = "إنشاء مسودة أمر بيع في أودو / نظام تخطيط الموارد",
                isReadOnly = false,
                isDestructive = false,
                defaultApproval = ApprovalLevel.L1_USER_CONFIRMATION,
                risk = RiskTier.R1_LOW,
                requiredScopes = listOf("sales.order.write")
            ),
            ToolContract(
                name = "sales.order.cancel",
                version = "1.2.0",
                purposeEn = "Cancel an authoritative sales order in ERP",
                purposeAr = "إلغاء أمر بيع معتمد في نظام ERP",
                isReadOnly = false,
                isDestructive = true,
                defaultApproval = ApprovalLevel.L3_MANAGER,
                risk = RiskTier.R3_HIGH,
                requiredScopes = listOf("sales.order.cancel")
            ),
            ToolContract(
                name = "invoice.create_from_order",
                version = "1.0.0",
                purposeEn = "Generate customer invoice from confirmed order",
                purposeAr = "توليد فاتورة العميل من أمر البيع المؤكد",
                isReadOnly = false,
                isDestructive = false,
                defaultApproval = ApprovalLevel.L2_PRIVILEGED,
                risk = RiskTier.R2_MEDIUM,
                requiredScopes = listOf("invoice.write")
            ),
            ToolContract(
                name = "payment.register",
                version = "1.1.0",
                purposeEn = "Register a payment settlement against an invoice",
                purposeAr = "تسجيل سداد مالي مرتبط بفاتورة",
                isReadOnly = false,
                isDestructive = false,
                defaultApproval = ApprovalLevel.L4_DUAL_APPROVAL_SOD,
                risk = RiskTier.R3_HIGH,
                requiredScopes = listOf("payment.write")
            ),
            ToolContract(
                name = "analytics.sales_summary",
                version = "1.0.0",
                purposeEn = "Bounded analytical query for revenue and trends",
                purposeAr = "استعلام تحليلي مقيد لإيرادات المبيعات والاتجاهات",
                isReadOnly = true,
                isDestructive = false,
                defaultApproval = ApprovalLevel.L0_NONE,
                risk = RiskTier.R0_READ,
                requiredScopes = listOf("analytics.read")
            )
        )
    }

    fun getToolContract(toolName: String): ToolContract? {
        return TOOL_REGISTRY.find { it.name == toolName }
    }

    /**
     * Deterministic evaluation of proposal based on user role, tool, and financial exposure.
     */
    fun evaluate(
        actor: IdentityPrincipal,
        toolName: String,
        financialAmount: Double,
        isDestructive: Boolean
    ): PolicyEvaluationResult {
        val tool = getToolContract(toolName)
            ?: return PolicyEvaluationResult(
                allowed = false,
                requiredApprovalLevel = ApprovalLevel.L5_MULTI_PARTY,
                riskTier = RiskTier.R4_CRITICAL,
                ruleId = "POL-UNKNOWN-TOOL",
                reasonEn = "Tool '$toolName' is not in the server-owned tool registry.",
                reasonAr = "الأداة '$toolName' غير مسجلة في سجل الأدوات المعتمدة بالخادم.",
                requiresSeparationOfDuties = false
            )

        // Read-only tools pass with L0
        if (tool.isReadOnly) {
            return PolicyEvaluationResult(
                allowed = true,
                requiredApprovalLevel = ApprovalLevel.L0_NONE,
                riskTier = RiskTier.R0_READ,
                ruleId = "POL-SAFE-READ",
                reasonEn = "Read-only query allowed without explicit approval.",
                reasonAr = "استعلام قراءة آمن مسموح به دون الحاجة لاعتماد صريح.",
                requiresSeparationOfDuties = false
            )
        }

        // Compliance check: Auditors cannot trigger write mutations
        if (actor.role == UserRole.AUDITOR) {
            return PolicyEvaluationResult(
                allowed = false,
                requiredApprovalLevel = ApprovalLevel.L5_MULTI_PARTY,
                riskTier = RiskTier.R4_CRITICAL,
                ruleId = "POL-AUDITOR-READONLY",
                reasonEn = "Auditor role has strictly read-only access by policy.",
                reasonAr = "دور مدقق الامتثال مقيد بصلاحيات القراءة فقط بموجب السياسة.",
                requiresSeparationOfDuties = false
            )
        }

        // Destructive actions (like order cancellation)
        if (isDestructive || tool.isDestructive) {
            return PolicyEvaluationResult(
                allowed = true,
                requiredApprovalLevel = ApprovalLevel.L3_MANAGER,
                riskTier = RiskTier.R3_HIGH,
                ruleId = "POL-DESTRUCTIVE-CANCEL",
                reasonEn = "Destructive ERP mutations require Sales Manager (L3) approval.",
                reasonAr = "العمليات الإتلافية على نظام ERP تتطلب اعتماد مدير المبيعات (L3).",
                requiresSeparationOfDuties = true
            )
        }

        // Financial Threshold ladder
        return when {
            financialAmount <= 1000.0 -> {
                PolicyEvaluationResult(
                    allowed = true,
                    requiredApprovalLevel = ApprovalLevel.L1_USER_CONFIRMATION,
                    riskTier = RiskTier.R1_LOW,
                    ruleId = "POL-THRESHOLD-L1",
                    reasonEn = "Amount ($${financialAmount}) <= $1,000: Standard user confirmation (L1).",
                    reasonAr = "المبلغ ($${financialAmount}) <= 1,000$: تأكيد المستخدم المعتاد (L1).",
                    requiresSeparationOfDuties = false
                )
            }
            financialAmount <= 10000.0 -> {
                PolicyEvaluationResult(
                    allowed = true,
                    requiredApprovalLevel = ApprovalLevel.L2_PRIVILEGED,
                    riskTier = RiskTier.R2_MEDIUM,
                    ruleId = "POL-THRESHOLD-L2",
                    reasonEn = "Amount ($${financialAmount}) between $1,000 and $10,000: Privileged sign-off (L2).",
                    reasonAr = "المبلغ ($${financialAmount}) بين 1,000$ و 10,000$: توقيع بصلاحية مميزة (L2).",
                    requiresSeparationOfDuties = false
                )
            }
            financialAmount <= 25000.0 -> {
                PolicyEvaluationResult(
                    allowed = true,
                    requiredApprovalLevel = ApprovalLevel.L3_MANAGER,
                    riskTier = RiskTier.R3_HIGH,
                    ruleId = "POL-THRESHOLD-L3",
                    reasonEn = "Amount ($${financialAmount}) > $10,000: Sales Manager approval required (L3).",
                    reasonAr = "المبلغ ($${financialAmount}) > 10,000$: اعتماد مدير المبيعات إلزامي (L3).",
                    requiresSeparationOfDuties = true
                )
            }
            else -> {
                PolicyEvaluationResult(
                    allowed = true,
                    requiredApprovalLevel = ApprovalLevel.L4_DUAL_APPROVAL_SOD,
                    riskTier = RiskTier.R4_CRITICAL,
                    ruleId = "POL-THRESHOLD-L4-SOD",
                    reasonEn = "Amount ($${financialAmount}) > $25,000: Dual Approval + Separation of Duties required (L4).",
                    reasonAr = "المبلغ ($${financialAmount}) > 25,000$: اعتماد ثنائي مع فصل إلزامي للمهام (L4).",
                    requiresSeparationOfDuties = true
                )
            }
        }
    }

    /**
     * Separation of Duties (SoD) enforcement:
     * When required, the approver must not be the proposal initiator!
     */
    fun validateSeparationOfDuties(
        initiatorId: String,
        approver: IdentityPrincipal,
        requiredLevel: ApprovalLevel
    ): Boolean {
        // L1 can be approved by initiator (self-confirmation)
        if (requiredLevel == ApprovalLevel.L1_USER_CONFIRMATION) {
            return true
        }

        // L2 and above: Initiator != Approver
        if (initiatorId == approver.userId) {
            return false // Violates Separation of Duties!
        }

        // Role authorization check
        return when (requiredLevel) {
            ApprovalLevel.L0_NONE -> true
            ApprovalLevel.L1_USER_CONFIRMATION -> true
            ApprovalLevel.L2_PRIVILEGED -> approver.role in listOf(UserRole.SALES_MANAGER, UserRole.FINANCE_APPROVER)
            ApprovalLevel.L3_MANAGER -> approver.role == UserRole.SALES_MANAGER
            ApprovalLevel.L4_DUAL_APPROVAL_SOD -> approver.role in listOf(UserRole.SALES_MANAGER, UserRole.FINANCE_APPROVER)
            ApprovalLevel.L5_MULTI_PARTY -> approver.role in listOf(UserRole.SALES_MANAGER, UserRole.FINANCE_APPROVER)
        }
    }

    /**
     * Blast Radius Preview
     */
    fun computeBlastRadius(
        toolName: String,
        customerName: String,
        units: Int,
        amount: Double
    ): BlastRadiusPreview {
        val evaluation = evaluate(
            actor = IdentityPrincipal("sim-user", "Simulator", "sim@mizan.org", UserRole.SALES_REP, "tenant-a"),
            toolName = toolName,
            financialAmount = amount,
            isDestructive = toolName == "sales.order.cancel"
        )

        return BlastRadiusPreview(
            toolName = toolName,
            affectedCustomer = customerName.ifBlank { "Unspecified Enterprise Customer" },
            affectedUnits = units,
            estimatedFinancialExposure = amount,
            approvalTier = evaluation.requiredApprovalLevel,
            requiresManagerSignoff = evaluation.requiredApprovalLevel.levelNumber >= ApprovalLevel.L3_MANAGER.levelNumber,
            riskSummaryEn = "Scope: 1 Customer, ${units} units, $$amount exposure. Tier: ${evaluation.requiredApprovalLevel.code}.",
            riskSummaryAr = "النطاق: 1 عميل، $units وحدات، تعرّض مالي $$amount. المستوى: ${evaluation.requiredApprovalLevel.code}."
        )
    }

    /**
     * Policy Simulator ("Would today's policy allow this?")
     */
    fun simulatePolicy(
        role: UserRole,
        toolName: String,
        amount: Double
    ): PolicyEvaluationResult {
        val simulatedActor = IdentityPrincipal(
            userId = "sim-actor-01",
            fullName = "Simulated ${role.roleNameEn}",
            email = "sim@mizan.internal",
            role = role,
            tenantId = "tenant-a"
        )
        return evaluate(
            actor = simulatedActor,
            toolName = toolName,
            financialAmount = amount,
            isDestructive = toolName == "sales.order.cancel"
        )
    }
}
