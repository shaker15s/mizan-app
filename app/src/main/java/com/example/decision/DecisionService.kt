package com.example.decision

import com.example.model.ApprovalLevel
import com.example.model.DecisionSignals
import com.example.model.RiskTier

class DecisionService {

    /**
     * Evaluates secondary decision signals on human intent & proposed tool.
     * Adheres to invariant: Secondary signals can escalate or warn, but cannot authorize.
     */
    fun evaluateIntent(
        rawIntent: String,
        targetTool: String,
        amount: Double,
        policyRequiredLevel: ApprovalLevel
    ): DecisionSignals {
        val lowerText = rawIntent.lowercase()

        // 1. Prompt Injection & Adversarial Attack Detection
        val injectionKeywords = listOf(
            "ignore previous", "تجاهل التعليمات",
            "bypass approval", "تجاوز الاعتماد",
            "set balance to 0", "اجعل الرصيد صفر",
            "override policy", "تخطي السياسة",
            "grant admin", "صلاحية مسؤول",
            "drop table", "delete all", "امسح الكل",
            "system prompt", "leak credentials", "سرقة المفتاح"
        )

        val isAdversarial = injectionKeywords.any { lowerText.contains(it) }
        val injectionScore = if (isAdversarial) 0.96 else 0.04

        // 2. Ambiguity Detection (e.g. unspecified quantity, vague customer, missing prices)
        val ambiguityKeywords = listOf("some", "maybe", "approx", "بعض", "تقريبا", "حوالي", "أي كمية", "any order")
        val hasAmbiguity = ambiguityKeywords.any { lowerText.contains(it) } || rawIntent.trim().length < 8
        val ambiguityScore = if (hasAmbiguity) 0.78 else 0.12

        // 3. Semantic Risk computation
        val semanticRisk = when {
            isAdversarial -> RiskTier.R4_CRITICAL
            amount > 20000.0 || targetTool == "sales.order.cancel" -> RiskTier.R3_HIGH
            amount > 5000.0 -> RiskTier.R2_MEDIUM
            amount > 0.0 -> RiskTier.R1_LOW
            else -> RiskTier.R0_READ
        }

        val escalationRequired = isAdversarial || ambiguityScore > 0.6 || semanticRisk == RiskTier.R4_CRITICAL

        val advisoryNotesEn = when {
            isAdversarial -> "CRITICAL ADVERSARIAL ALERT: Prompt injection patterns detected. Tool execution blocked from auto-approval. Escalation enforced."
            hasAmbiguity -> "HIGH AMBIGUITY: Intent lacks precise ERP line-item specifications. Confirmation requires explicit review."
            semanticRisk == RiskTier.R3_HIGH -> "HIGH VALUE EXPOSURE: Financial threshold triggers heightened managerial surveillance."
            else -> "NOMINAL ADVISORY: Signals conform to standard operating boundaries."
        }

        val advisoryNotesAr = when {
            isAdversarial -> "تنبيه هجومي حرج: تم رصد نمط محاولة اختراق سياقي (Prompt Injection). تم حظر التنفيذ التلقائي وتفعيل التصعيد الإلزامي."
            hasAmbiguity -> "غموض مرتفع: الطلب يفتقر لتفاصيل دقيقة لبنود الفاتورة. يتطلب مراجعة بشرية مؤكدة."
            semanticRisk == RiskTier.R3_HIGH -> "تعرّض مالي مرتفع: يتطلب تدقيقًا إداريًا مشددًا."
            else -> "تقييم اعتيادي: الإشارات مطابقة للحدود التشغيلية الآمنة."
        }

        return DecisionSignals(
            routeRecommendation = targetTool,
            confidence = if (isAdversarial) 0.32 else 0.95,
            ambiguityScore = ambiguityScore,
            injectionSuspicion = injectionScore,
            semanticRisk = semanticRisk,
            escalationRequired = escalationRequired,
            advisoryNotesEn = advisoryNotesEn,
            advisoryNotesAr = advisoryNotesAr
        )
    }
}
