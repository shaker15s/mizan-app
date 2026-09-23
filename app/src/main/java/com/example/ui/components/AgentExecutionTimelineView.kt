package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.ExecutionRecordEntity
import com.example.model.BoundedProposal
import com.example.model.TrustReceipt
import com.example.ui.theme.LocalLiquidGlass
import com.example.viewmodel.AgentTimelineItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ErpExecutionStepStatus(
    val labelEn: String,
    val labelAr: String,
    val icon: ImageVector,
    val isPending: Boolean
) {
    PENDING_APPROVAL("Pending Approval", "بانتظار الاعتماد", Icons.Default.HourglassTop, true),
    ACTIVE_REASONING("Agent Reasoning", "جاري التحليل والتحقق", Icons.Default.AutoAwesome, true),
    EXECUTING_MUTATION("Executing ERP RPC", "جاري إرسال الأمر لـ Odoo", Icons.Default.Sync, true),
    COMPLETED_VERIFIED("Completed & Authoritative", "مكتمل وموثق قطعي", Icons.Default.CheckCircle, false),
    AMBIGUOUS_RECONCILE("Ambiguous / Reconcile Req", "حالة غير محسومة / تتطلب تسوية", Icons.Default.ErrorOutline, false),
    POLICY_REJECTED("Rejected by Policy", "مرفوض بموجب السياسة", Icons.Default.Shield, false)
}

data class DashboardTimelineStep(
    val id: String,
    val titleEn: String,
    val titleAr: String,
    val detailEn: String,
    val detailAr: String,
    val status: ErpExecutionStepStatus,
    val timestamp: Long,
    val rawErpId: String? = null,
    val proposal: BoundedProposal? = null,
    val receipt: TrustReceipt? = null
)

/**
 * Modern Apple Liquid Glass Agent-Native Execution Timeline
 */
@Composable
fun AgentExecutionTimelineView(
    timelineItems: List<AgentTimelineItem>,
    pendingProposal: BoundedProposal?,
    executions: List<ExecutionRecordEntity>,
    isArabic: Boolean,
    isReasoning: Boolean,
    onNavigateToAgent: () -> Unit,
    onViewReceipt: ((TrustReceipt) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val glass = LocalLiquidGlass.current

    // Transform raw state items into unified timeline visualization steps
    val steps = remember(timelineItems, pendingProposal, executions, isReasoning) {
        val list = mutableListOf<DashboardTimelineStep>()

        // 1. Active Reasoning step if agent is currently thinking
        if (isReasoning) {
            list.add(
                DashboardTimelineStep(
                    id = "step-reasoning-${System.currentTimeMillis()}",
                    titleEn = "Agent Bound Verification",
                    titleAr = "فحص قيود الوكيل وسياسات الحوكمة",
                    detailEn = "Evaluating Odoo blast radius and role permissions in real-time...",
                    detailAr = "يتم حالياً تقييم نطاق التأثير على أودو وصلاحيات الدور بشكل فوري...",
                    status = ErpExecutionStepStatus.ACTIVE_REASONING,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        // 2. Pending Proposal Step
        if (pendingProposal != null) {
            list.add(
                DashboardTimelineStep(
                    id = "step-proposal-${pendingProposal.proposalId}",
                    titleEn = "Awaiting Sign-Off: ${pendingProposal.targetTool}",
                    titleAr = "بانتظار التوقيع: ${pendingProposal.targetTool}",
                    detailEn = "Proposed Amount: $${String.format(Locale.US, "%,.2f", pendingProposal.estimatedFinancialValue)} • Rule: ${pendingProposal.policyRuleId}",
                    detailAr = "القيمة: ${String.format(Locale.US, "%,.2f", pendingProposal.estimatedFinancialValue)}$ • القاعدة: ${pendingProposal.policyRuleId}",
                    status = ErpExecutionStepStatus.PENDING_APPROVAL,
                    timestamp = pendingProposal.timestamp,
                    proposal = pendingProposal
                )
            )
        }

        // 3. From Agent Timeline (Verified results, Ambiguity, or Rejections)
        for (item in timelineItems.reversed()) {
            when (item.type) {
                "VERIFIED_RESULT" -> {
                    list.add(
                        DashboardTimelineStep(
                            id = item.id,
                            titleEn = "Verified ERP Mutation",
                            titleAr = "عملية ERP موثقة قطعية",
                            detailEn = item.textEn,
                            detailAr = item.textAr,
                            status = ErpExecutionStepStatus.COMPLETED_VERIFIED,
                            timestamp = item.timestamp,
                            rawErpId = item.receipt?.erpRecordId,
                            receipt = item.receipt
                        )
                    )
                }
                "AMBIGUOUS_ALERT" -> {
                    list.add(
                        DashboardTimelineStep(
                            id = item.id,
                            titleEn = "Ambiguous Execution Flagged",
                            titleAr = "تنبيه: حالة غير محسومة معلقة",
                            detailEn = item.textEn,
                            detailAr = item.textAr,
                            status = ErpExecutionStepStatus.AMBIGUOUS_RECONCILE,
                            timestamp = item.timestamp,
                            rawErpId = item.candidateErpIds?.firstOrNull()
                        )
                    )
                }
                "ERROR" -> {
                    list.add(
                        DashboardTimelineStep(
                            id = item.id,
                            titleEn = "Policy Guard Halt",
                            titleAr = "إيقاف بموجب حوكمة السياسات",
                            detailEn = item.textEn,
                            detailAr = item.textAr,
                            status = ErpExecutionStepStatus.POLICY_REJECTED,
                            timestamp = item.timestamp
                        )
                    )
                }
            }
        }

        // 4. Fallback execution records from DB if timeline is empty
        if (list.isEmpty()) {
            for (exec in executions.take(4)) {
                val status = when (exec.currentState) {
                    "VERIFIED", "COMPLETED" -> ErpExecutionStepStatus.COMPLETED_VERIFIED
                    "AMBIGUOUS", "PENDING_RECONCILIATION" -> ErpExecutionStepStatus.AMBIGUOUS_RECONCILE
                    else -> ErpExecutionStepStatus.POLICY_REJECTED
                }
                list.add(
                    DashboardTimelineStep(
                        id = exec.executionId,
                        titleEn = "${exec.toolName} (DB Execution)",
                        titleAr = "${exec.toolName} (سجل النظام)",
                        detailEn = "ERP Record: ${exec.erpRecordId ?: "N/A"} • Intent: ${exec.rawIntent}",
                        detailAr = "سجل أودو: ${exec.erpRecordId ?: "لا يوجد"} • القصد: ${exec.rawIntent}",
                        status = status,
                        timestamp = exec.timestamp,
                        rawErpId = exec.erpRecordId
                    )
                )
            }
        }

        list.take(5) // show top 5 relevant steps
    }

    LiquidGlassCard(
        shape = RoundedCornerShape(28.dp),
        elevation = 10.dp,
        accentBorder = if (pendingProposal != null) glass.accentOrange.copy(alpha = 0.6f) else glass.borderGlass,
        modifier = modifier
            .fillMaxWidth()
            .testTag("agent_execution_timeline_card")
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Header: Title + Open Agent Console Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(glass.accentTeal.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Execution Timeline",
                            tint = glass.accentTeal,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (isArabic) "مسار تنفيذ عمليات الوكيل (ERP Pipeline)" else "Agent Execution Timeline",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = glass.textPrimary
                        )
                        Text(
                            text = if (isArabic) "متابعة فورية للعمليات المعلقة والمكتملة" else "Live tracking of pending & completed Odoo operations",
                            fontSize = 11.sp,
                            color = glass.textMuted
                        )
                    }
                }

                // ChatGPT-style pill button
                LiquidGlassPill(
                    isSelected = true,
                    selectedColor = glass.accentTeal,
                    onClick = onNavigateToAgent,
                    modifier = Modifier.testTag("btn_timeline_open_agent")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isArabic) "الوكيل" else "Agent Chat",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = glass.accentTeal
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = glass.accentTeal,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Thinking Bar if active
            if (isReasoning) {
                ThinkingIndicator(
                    isArabic = isArabic,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                )
            }

            // Timeline Steps
            if (steps.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = glass.surfaceElevated.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, glass.borderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = glass.textMuted,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isArabic) "لا توجد عمليات ERP جارية حالياً" else "No active or recent ERP operations yet",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = glass.textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isArabic) "أرسل أمراً إلى الوكيل الذكي للبدء (مثل: إنشاء أمر بيع أو فحص مخزون)" else "Submit an intent in Agent Chat to start an execution pipeline",
                            fontSize = 11.sp,
                            color = glass.textMuted
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    steps.forEachIndexed { index, step ->
                        TimelineStepRow(
                            step = step,
                            isLast = index == steps.size - 1,
                            isArabic = isArabic,
                            onViewReceipt = onViewReceipt
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineStepRow(
    step: DashboardTimelineStep,
    isLast: Boolean,
    isArabic: Boolean,
    onViewReceipt: ((TrustReceipt) -> Unit)?
) {
    val glass = LocalLiquidGlass.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val formattedTime = remember(step.timestamp) { timeFormat.format(Date(step.timestamp)) }

    val stepColor = when (step.status) {
        ErpExecutionStepStatus.PENDING_APPROVAL -> glass.accentOrange
        ErpExecutionStepStatus.ACTIVE_REASONING -> glass.accentTeal
        ErpExecutionStepStatus.EXECUTING_MUTATION -> glass.accentBlue
        ErpExecutionStepStatus.COMPLETED_VERIFIED -> glass.accentGreen
        ErpExecutionStepStatus.AMBIGUOUS_RECONCILE -> glass.accentCoral
        ErpExecutionStepStatus.POLICY_REJECTED -> glass.accentCoral
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Timeline Dot & Connecting Line
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(stepColor.copy(alpha = 0.18f))
                    .padding(5.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = step.status.icon,
                    contentDescription = null,
                    tint = stepColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(36.dp)
                        .background(glass.borderSubtle)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Step Content Card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = glass.surfaceElevated.copy(alpha = if (step.status.isPending) 0.85f else 0.5f),
            border = BorderStroke(
                1.dp,
                if (step.status.isPending) stepColor.copy(alpha = 0.6f) else glass.borderSubtle
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("timeline_step_${step.id}")
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Title and Status Pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isArabic) step.titleAr else step.titleEn,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = glass.textPrimary,
                        modifier = Modifier.weight(1f)
                    )

                    Surface(
                        shape = CircleShape,
                        color = stepColor.copy(alpha = 0.15f),
                        border = BorderStroke(0.8.dp, stepColor.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = if (isArabic) step.status.labelAr else step.status.labelEn,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = stepColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Detail message
                Text(
                    text = if (isArabic) step.detailAr else step.detailEn,
                    fontSize = 12.sp,
                    color = glass.textSecondary,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Footer: Timestamp & Optional View Receipt
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formattedTime,
                        fontSize = 10.sp,
                        color = glass.textMuted,
                        fontFamily = FontFamily.Monospace
                    )

                    if (step.receipt != null && onViewReceipt != null) {
                        ElevatedButton(
                            onClick = { onViewReceipt(step.receipt) },
                            shape = CircleShape,
                            colors = ButtonDefaults.elevatedButtonColors(
                                containerColor = glass.accentTeal.copy(alpha = 0.2f),
                                contentColor = glass.accentTeal
                            ),
                            elevation = ButtonDefaults.elevatedButtonElevation(0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ReceiptLong,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isArabic) "إيصال الثقة" else "Trust Receipt",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (step.rawErpId != null) {
                        Text(
                            text = "ERP: ${step.rawErpId}",
                            fontSize = 10.sp,
                            color = glass.accentTeal,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
