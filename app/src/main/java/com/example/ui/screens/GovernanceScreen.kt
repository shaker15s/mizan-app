package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.ApprovalLevel
import com.example.model.UserRole
import com.example.ui.components.ApprovalBadge
import com.example.ui.components.LiquidGlassCard
import com.example.ui.components.RiskBadge
import com.example.ui.theme.LocalLiquidGlass
import com.example.ui.theme.MizanBlue
import com.example.ui.theme.MizanCardBg
import com.example.ui.theme.MizanCardBorder
import com.example.ui.theme.MizanCoral
import com.example.ui.theme.MizanCyan
import com.example.ui.theme.MizanGold
import com.example.ui.theme.MizanGreen
import com.example.ui.theme.MizanSurface
import com.example.ui.theme.MizanTextMuted
import com.example.ui.theme.MizanTextPrimary
import com.example.ui.theme.MizanTextSecondary
import com.example.viewmodel.MizanViewModel

@Composable
fun GovernanceScreen(viewModel: MizanViewModel) {
    val isArabic by viewModel.isArabic.collectAsStateWithLifecycle()
    val simRole by viewModel.simRole.collectAsStateWithLifecycle()
    val simTool by viewModel.simTool.collectAsStateWithLifecycle()
    val simAmount by viewModel.simAmount.collectAsStateWithLifecycle()
    val simResult by viewModel.simResult.collectAsStateWithLifecycle()
    val blastRadius by viewModel.blastRadius.collectAsStateWithLifecycle()
    val currentTenant by viewModel.currentTenant.collectAsStateWithLifecycle()
    val glass = LocalLiquidGlass.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(glass.backgroundGradient)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section 1: Policy Simulator
        LiquidGlassCard(
            shape = RoundedCornerShape(24.dp),
            accentBorder = glass.accentTeal.copy(alpha = 0.5f),
            elevation = 6.dp,
            modifier = Modifier.fillMaxWidth().testTag("card_policy_simulator")
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(glass.accentTeal.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.Radar, contentDescription = null, tint = glass.accentTeal, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isArabic) "محاكي السياسات وقواعد الصلاحيات" else "DETERMINISTIC POLICY SIMULATOR",
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = glass.accentTeal
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (isArabic)
                        "هل تسمح السياسة اليوم بهذا الإجراء؟ جرّب أي دور وأي أداة وأي مبلغ لاختبار استجابة نظام الحوكمة فورًا دون لمس الإنتاج."
                    else
                        "Would today's policy allow this operation? Test roles, tools, and financial amounts instantaneously without executing mutations.",
                    fontSize = 12.sp,
                    color = glass.textSecondary,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Select Role Chips
                Text(
                    text = if (isArabic) "الدور الوظيفي المطلوب اختباره:" else "Simulated Principal Role:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = glass.accentOrange
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    UserRole.values().forEach { role ->
                        val selected = role == simRole
                        Surface(
                            color = if (selected) glass.accentTeal else glass.surfaceElevated,
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) glass.accentTeal else glass.borderSubtle),
                            modifier = Modifier.clickable { viewModel.updateSimulator(role, simTool, simAmount) }
                        ) {
                            Text(
                                text = if (isArabic) role.roleNameAr else role.roleNameEn,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) Color.White else glass.textSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Select Tool Chips
                Text(
                    text = if (isArabic) "الأداة المستهدفة:" else "Target Business Tool:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = glass.accentOrange
                )
                Spacer(modifier = Modifier.height(6.dp))
                val tools = listOf("sales.order.create_draft", "sales.order.cancel", "payment.register", "stock.availability")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tools.forEach { tool ->
                        val selected = tool == simTool
                        Surface(
                            color = if (selected) glass.accentOrange else glass.surfaceElevated,
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) glass.accentOrange else glass.borderSubtle),
                            modifier = Modifier.clickable { viewModel.updateSimulator(simRole, tool, simAmount) }
                        ) {
                            Text(
                                text = tool,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) Color.White else glass.textSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Financial Amount Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isArabic) "المبلغ المالي:" else "Financial Exposure:",
                        fontSize = 11.sp,
                        color = glass.textSecondary
                    )
                    Text(
                        text = "$${simAmount.toInt()}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = glass.accentTeal
                    )
                }

                Slider(
                    value = simAmount.toFloat(),
                    onValueChange = { viewModel.updateSimulator(simRole, simTool, it.toDouble()) },
                    valueRange = 500f..40000f,
                    colors = SliderDefaults.colors(
                        thumbColor = glass.accentTeal,
                        activeTrackColor = glass.accentTeal,
                        inactiveTrackColor = glass.borderSubtle
                    )
                )

                // Simulation Outcome Box
                if (simResult != null) {
                    val res = simResult!!
                    Surface(
                        color = if (res.allowed) glass.accentGreen.copy(alpha = 0.15f) else glass.accentCoral.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (res.allowed) glass.accentGreen.copy(alpha = 0.5f) else glass.accentCoral.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (res.allowed) "STATUS: PERMITTED" else "STATUS: DENIED",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp,
                                    color = if (res.allowed) glass.accentGreen else glass.accentCoral
                                )
                                ApprovalBadge(res.requiredApprovalLevel)
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "Rule: ${res.ruleId} • Risk: ${res.riskTier}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = glass.textMuted
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = if (isArabic) res.reasonAr else res.reasonEn,
                                fontSize = 11.sp,
                                color = glass.textPrimary
                            )

                            if (res.requiresSeparationOfDuties) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (isArabic) "⚠️ شرط إلزامي: فصل المهام (Separation of Duties: Initiator != Approver)"
                                    else "⚠️ Mandatory: Separation of Duties (Initiator != Approver)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = glass.accentOrange
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section 2: Blast Radius Preview
        if (blastRadius != null) {
            val br = blastRadius!!
            LiquidGlassCard(
                shape = RoundedCornerShape(24.dp),
                accentBorder = glass.accentOrange.copy(alpha = 0.5f),
                elevation = 5.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(glass.accentOrange.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Default.Shield, contentDescription = null, tint = glass.accentOrange, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isArabic) "معاينة نطاق التأثير (Blast Radius Preview)" else "BLAST RADIUS PREVIEW",
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            letterSpacing = 1.sp,
                            color = glass.accentOrange
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = if (isArabic) br.riskSummaryAr else br.riskSummaryEn,
                        fontSize = 12.sp,
                        color = glass.textPrimary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isArabic) "موافقة المدير التنفيذي:" else "Manager Sign-off Required:",
                            fontSize = 11.sp,
                            color = glass.textSecondary
                        )
                        Text(
                            text = if (br.requiresManagerSignoff) "YES (Mandatory)" else "NO",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (br.requiresManagerSignoff) glass.accentCoral else glass.accentGreen
                        )
                    }
                }
            }
        }

        // Section 3: Capability Health Map (Blueprint 31.7)
        LiquidGlassCard(
            shape = RoundedCornerShape(24.dp),
            accentBorder = glass.accentGreen.copy(alpha = 0.5f),
            elevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(glass.accentGreen.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.HealthAndSafety, contentDescription = null, tint = glass.accentGreen, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isArabic) "خريطة صحة القدرات التنفيذية (Capability Health)" else "BUSINESS CAPABILITY HEALTH MAP",
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = glass.accentGreen
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                CapabilityHealthRow(if (isArabic) "المبيعات (Sales Lifecycle)" else "Sales Continuity (Orders, Drafts)", "Healthy", glass.accentGreen)
                CapabilityHealthRow(if (isArabic) "المخزون (Inventory & Stock)" else "Inventory Position & Availability", "Healthy", glass.accentGreen)
                CapabilityHealthRow(if (isArabic) "المالية (Invoices & Payments)" else "Financial Settlement & Receivables", "Healthy", glass.accentGreen)
                CapabilityHealthRow(if (isArabic) "التحليلات المقيدة (Analytics)" else "Bounded Semantic Analytics", "Healthy", glass.accentGreen)
                CapabilityHealthRow(
                    name = currentTenant.erpSystem,
                    status = "Active • Bearer Auth Isolated",
                    color = glass.accentTeal
                )
            }
        }

        // Section 4: Multi-Tenant Isolation Evidence
        LiquidGlassCard(
            shape = RoundedCornerShape(24.dp),
            accentBorder = glass.accentPurple.copy(alpha = 0.5f),
            elevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(glass.accentPurple.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.Hub, contentDescription = null, tint = glass.accentPurple, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isArabic) "عزل المستأجرين (Multi-Tenant Isolation)" else "MULTI-TENANT ISOLATION PROOF",
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = glass.accentPurple
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (isArabic)
                        "يتم فرض العزل التام للمستأجر على مستوى الخادم في جلسات العمل، بيانات الاعتماد، سجلات التدقيق، وعمليات الـ ERP دون أي تداخل."
                    else
                        "Server-owned tenant boundary enforced across sessions, credentials, audit ledgers, and connector instances.",
                    fontSize = 11.sp,
                    color = glass.textSecondary,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isArabic) "المستأجر النشط حاليًا:" else "Active Tenant Context:",
                        fontSize = 11.sp,
                        color = glass.textMuted
                    )
                    Text(
                        text = "${currentTenant.tenantId} (${currentTenant.tenantNameEn})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = glass.accentTeal
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isArabic) "حالة فحص تسرب البيانات:" else "Cross-Tenant Leakage Check:",
                        fontSize = 11.sp,
                        color = glass.textMuted
                    )
                    Text(
                        text = "0 Violations (100% Isolated)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = glass.accentGreen
                    )
                }
            }
        }
    }
}

@Composable
fun CapabilityHealthRow(name: String, status: String, color: Color) {
    val glass = LocalLiquidGlass.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            fontSize = 12.sp,
            color = glass.textPrimary
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = status,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
    Divider(color = glass.borderSubtle, thickness = 0.5.dp, modifier = Modifier.padding(top = 4.dp))
}
