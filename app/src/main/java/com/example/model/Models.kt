package com.example.model

import java.security.MessageDigest

enum class ExecutionState(val displayNameEn: String, val displayNameAr: String) {
    PROPOSED("Proposed", "تم الاقتراح"),
    VALIDATED("Validated", "تم التحقق من المعايير"),
    RISK_EVALUATED("Risk Evaluated", "تم تقييم المخاطر"),
    AWAITING_APPROVAL("Awaiting Approval", "في انتظار الاعتماد"),
    LEASE_ACQUIRED("Lease Acquired", "حجز التنفيذ الحصري"),
    EXECUTING("Executing ERP Action", "جاري التنفيذ بالـ ERP"),
    VERIFIED("Authoritatively Verified", "مُتحقق قطعيًا"),
    AMBIGUOUS("Ambiguous ERP State", "حالة غير محسومة"),
    RECONCILIATION_REQUIRED("Reconciliation Required", "يتطلب تسوية يدوية"),
    FAILED("Execution Failed", "فشل التنفيذ"),
    CANCELLED("Cancelled", "ملغى")
}

enum class ApprovalLevel(val code: String, val titleEn: String, val titleAr: String, val levelNumber: Int) {
    L0_NONE("L0", "No Approval (Safe Read)", "بدون اعتماد (قراءة آمنة)", 0),
    L1_USER_CONFIRMATION("L1", "User Confirmation", "تأكيد المستخدم", 1),
    L2_PRIVILEGED("L2", "Privileged Confirmation", "تأكيد بصلاحية خاصة", 2),
    L3_MANAGER("L3", "Manager Approval", "اعتماد مدير المبيعات", 3),
    L4_DUAL_APPROVAL_SOD("L4", "Dual Approval (SoD)", "اعتماد ثنائي (فصل المهام)", 4),
    L5_MULTI_PARTY("L5", "Multi-Party Sign-off", "اعتماد متعدد الأطراف", 5)
}

enum class RiskTier(val code: String, val labelEn: String, val labelAr: String) {
    R0_READ("R0", "Read Only (Safe)", "قراءة فقط (آمن)"),
    R1_LOW("R1", "Low Risk", "مخاطر منخفضة"),
    R2_MEDIUM("R2", "Medium Risk", "مخاطر متوسطة"),
    R3_HIGH("R3", "High Financial Risk", "مخاطر مالية عالية"),
    R4_CRITICAL("R4", "Critical / Destructive", "عملية حرجة / إتلافية")
}

enum class UserRole(val roleNameEn: String, val roleNameAr: String) {
    OPERATOR("Operator", "مشغل نظام"),
    SALES_REP("Sales Representative", "مندوب مبيعات"),
    SALES_MANAGER("Sales Manager", "مدير مبيعات"),
    FINANCE_APPROVER("Finance Approver", "معتمد مالي"),
    AUDITOR("Compliance Auditor", "مدقق امتثال")
}

data class IdentityPrincipal(
    val userId: String,
    val fullName: String,
    val email: String,
    val role: UserRole,
    val tenantId: String
)

data class TenantInfo(
    val tenantId: String,
    val tenantNameEn: String,
    val tenantNameAr: String,
    val erpSystem: String, // "Odoo 19 (JSON-2)" or "ERPNext (REST)"
    val baseUrl: String,
    val isSandbox: Boolean
)

data class BoundedProposal(
    val proposalId: String,
    val traceId: String,
    val timestamp: Long,
    val tenantId: String,
    val initiatorId: String,
    val rawIntent: String,
    val targetTool: String,
    val toolVersion: String,
    val argumentsJson: String,
    val argumentsMap: Map<String, String>,
    val estimatedFinancialValue: Double,
    val requiredApprovalLevel: ApprovalLevel,
    val riskTier: RiskTier,
    val policyRuleId: String,
    val policyExplanationEn: String,
    val policyExplanationAr: String
)

data class DecisionSignals(
    val routeRecommendation: String,
    val confidence: Double,
    val ambiguityScore: Double, // 0.0 to 1.0
    val injectionSuspicion: Double, // 0.0 to 1.0 (prompt injection defense)
    val semanticRisk: RiskTier,
    val escalationRequired: Boolean,
    val advisoryNotesEn: String,
    val advisoryNotesAr: String
)

data class ExecutionLease(
    val leaseId: String,
    val executionId: String,
    val ownerPrincipalId: String,
    val acquiredAt: Long,
    val expiresAt: Long,
    val isExpired: Boolean = false
)

data class TrustReceipt(
    val receiptId: String,
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
    val sodProof: String, // "Initiator != Approver Verified"
    val canonicalArgumentsJson: String,
    val idempotencyKey: String,
    val erpRecordId: String,
    val erpModel: String,
    val verificationHash: String,
    val auditChainIndex: Long,
    val tamperProofToken: String
)

data class ToolContract(
    val name: String,
    val version: String,
    val purposeEn: String,
    val purposeAr: String,
    val isReadOnly: Boolean,
    val isDestructive: Boolean,
    val defaultApproval: ApprovalLevel,
    val risk: RiskTier,
    val requiredScopes: List<String>
)

object CryptoUtil {
    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    fun computeIdempotencyKey(tenantId: String, tool: String, argsCanonical: String): String {
        return "IDEM-${sha256("$tenantId:$tool:$argsCanonical").take(16).uppercase()}"
    }

    fun computeTamperProofToken(receiptId: String, traceId: String, erpId: String, timestamp: Long): String {
        return "MIZAN-${sha256("$receiptId:$traceId:$erpId:$timestamp").take(24).uppercase()}"
    }
}
