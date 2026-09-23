package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.ApprovalLevel
import com.example.model.ExecutionState
import com.example.model.RiskTier
import com.example.model.TenantInfo
import com.example.model.TrustReceipt
import com.example.model.UserRole
import com.example.ui.theme.MizanBlue
import com.example.ui.theme.MizanCardBg
import com.example.ui.theme.MizanCardBorder
import com.example.ui.theme.MizanCoral
import com.example.ui.theme.MizanCyan
import com.example.ui.theme.MizanGold
import com.example.ui.theme.MizanGreen
import com.example.ui.theme.MizanPurple
import com.example.ui.theme.MizanSurface
import com.example.ui.theme.MizanTextMuted
import com.example.ui.theme.MizanTextPrimary
import com.example.ui.theme.MizanTextSecondary
import com.example.ui.theme.LocalLiquidGlass
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MizanTopBar(
    currentTenant: TenantInfo,
    onTenantSwitchClick: () -> Unit,
    isArabic: Boolean,
    onLanguageToggle: () -> Unit,
    isDarkTheme: Boolean = false,
    onThemeToggle: () -> Unit = {},
    currentUserRole: UserRole,
    onRoleSwitchClick: () -> Unit
) {
    val glass = LocalLiquidGlass.current

    Surface(
        color = glass.surfaceElevated.copy(alpha = 0.85f),
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, glass.borderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mizan Emblem & Title
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(glass.accentTeal.copy(alpha = 0.18f))
                            .border(1.dp, glass.accentTeal.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Balance,
                            contentDescription = "MIZAN Emblem",
                            tint = glass.accentTeal,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "MIZAN",
                                fontWeight = FontWeight.Black,
                                fontSize = 17.sp,
                                letterSpacing = 1.5.sp,
                                color = glass.accentTeal
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ميزان",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = glass.accentOrange
                            )
                        }
                        Text(
                            text = if (isArabic) "منصة حوكمة وكلاء الـ ERP" else "Governed ERP Execution",
                            fontSize = 11.sp,
                            color = glass.textSecondary
                        )
                    }
                }

                // Controls: Day/Night Theme toggle, Language toggle, and Role Pill
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Day / Night Theme Toggle (Apple Glassmorphism Light / Dark)
                    IconButton(
                        onClick = onThemeToggle,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(glass.surfaceElevated)
                            .border(1.dp, glass.borderSubtle, CircleShape)
                            .testTag("btn_theme_toggle")
                    ) {
                        Icon(
                            imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = if (isArabic) "تبديل المظهر النهاري/الليلي" else "Toggle Day/Night Theme",
                            tint = if (isDarkTheme) glass.accentOrange else glass.accentTeal,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Language Switcher
                    IconButton(
                        onClick = onLanguageToggle,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(glass.surfaceElevated)
                            .border(1.dp, glass.borderSubtle, CircleShape)
                            .testTag("btn_lang_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = "Toggle Language",
                            tint = glass.accentTeal,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Role Pill
                    Surface(
                        color = glass.surfaceElevated,
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, glass.borderGlass),
                        modifier = Modifier.clickable { onRoleSwitchClick() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(glass.accentOrange)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isArabic) currentUserRole.roleNameAr else currentUserRole.roleNameEn,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = glass.textPrimary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tenant Banner with switch button
            Surface(
                color = glass.cardGlass,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, glass.borderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTenantSwitchClick() }
                    .testTag("tenant_switcher_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = glass.accentTeal,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isArabic) currentTenant.tenantNameAr else currentTenant.tenantNameEn,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = glass.textPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "• ${currentTenant.erpSystem}",
                            fontSize = 11.sp,
                            color = glass.accentTeal
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isArabic) "تبديل المستأجر" else "Switch Tenant",
                            fontSize = 10.sp,
                            color = glass.textMuted
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = null,
                            tint = glass.textMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StateBadge(state: ExecutionState, isArabic: Boolean) {
    val (bgColor, textColor) = when (state) {
        ExecutionState.VERIFIED -> Pair(MizanGreen.copy(alpha = 0.2f), MizanGreen)
        ExecutionState.AWAITING_APPROVAL -> Pair(MizanGold.copy(alpha = 0.2f), MizanGold)
        ExecutionState.LEASE_ACQUIRED, ExecutionState.EXECUTING -> Pair(MizanBlue.copy(alpha = 0.2f), MizanBlue)
        ExecutionState.AMBIGUOUS, ExecutionState.RECONCILIATION_REQUIRED -> Pair(MizanCoral.copy(alpha = 0.25f), MizanCoral)
        ExecutionState.FAILED, ExecutionState.CANCELLED -> Pair(Color(0xFF4A1515), MizanCoral)
        else -> Pair(Color(0xFF1E293B), MizanTextSecondary)
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, textColor.copy(alpha = 0.5f))
    ) {
        Text(
            text = if (isArabic) state.displayNameAr else state.displayNameEn,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun ApprovalBadge(level: ApprovalLevel) {
    val color = when (level) {
        ApprovalLevel.L0_NONE -> MizanGreen
        ApprovalLevel.L1_USER_CONFIRMATION -> MizanBlue
        ApprovalLevel.L2_PRIVILEGED -> MizanGold
        ApprovalLevel.L3_MANAGER -> Color(0xFFF97316)
        ApprovalLevel.L4_DUAL_APPROVAL_SOD -> MizanCoral
        ApprovalLevel.L5_MULTI_PARTY -> MizanPurple
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.6f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(10.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = level.code,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
fun RiskBadge(risk: RiskTier) {
    val color = when (risk) {
        RiskTier.R0_READ -> MizanGreen
        RiskTier.R1_LOW -> MizanBlue
        RiskTier.R2_MEDIUM -> MizanGold
        RiskTier.R3_HIGH -> Color(0xFFF97316)
        RiskTier.R4_CRITICAL -> MizanCoral
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text = risk.code,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * The Signature Feature: Machine-Verifiable Trust Receipt Dialog
 */
@Composable
fun TrustReceiptDialog(
    receipt: TrustReceipt,
    isArabic: Boolean,
    onDismiss: () -> Unit
) {
    val glass = LocalLiquidGlass.current
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val dateString = dateFormat.format(Date(receipt.timestamp))

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = glass.cardBackground,
            shape = RoundedCornerShape(28.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, glass.borderGlass),
            shadowElevation = 24.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(glass.accentTeal.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = "Trust Receipt",
                                tint = glass.accentTeal,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isArabic) "إيصال الثقة الموثق" else "TRUST RECEIPT",
                                fontWeight = FontWeight.Black,
                                fontSize = 16.sp,
                                letterSpacing = 1.sp,
                                color = glass.accentTeal
                            )
                            Text(
                                text = "MIZAN PROVENANCE CHAIN",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = glass.accentOrange
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = glass.textMuted
                        )
                    }
                }

                Divider(
                    color = glass.borderSubtle,
                    modifier = Modifier.padding(vertical = 14.dp)
                )

                // Tamper-Proof Token Badge
                Surface(
                    color = glass.surfaceElevated,
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, glass.accentTeal.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (isArabic) "رمز التحقق المشفر (SHA-256)" else "Cryptographic Token",
                            fontSize = 10.sp,
                            color = glass.textMuted
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = receipt.tamperProofToken,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = glass.accentTeal
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // The 13 Provenance Points
                ProvenanceRow(if (isArabic) "1. من طلب العملية؟" else "1. Who Asked?", "${receipt.initiatorName} (${receipt.initiatorRole})")
                ProvenanceRow(if (isArabic) "2. المستأجر التابع" else "2. Tenant Scope", receipt.tenantName)
                ProvenanceRow(if (isArabic) "3. القصد اللغوي" else "3. Intent Understood", receipt.intent)
                ProvenanceRow(if (isArabic) "4. الأداة المقترحة" else "4. Proposed Tool", "${receipt.toolName} v${receipt.toolVersion}")
                ProvenanceRow(if (isArabic) "5. قاعدة السياسة" else "5. Policy Rule ID", receipt.policyRuleId)
                ProvenanceRow(if (isArabic) "6. مستوى الاعتماد" else "6. Approval Level", "${receipt.approvalLevel} • Risk: ${receipt.riskTier}")
                ProvenanceRow(if (isArabic) "7. من اعتمد العملية؟" else "7. Approved By", receipt.approverName)
                ProvenanceRow(if (isArabic) "8. إثبات فصل المهام" else "8. SoD Proof", receipt.sodProof)
                ProvenanceRow(if (isArabic) "9. مفتاح المنع من التكرار" else "9. Idempotency Key", receipt.idempotencyKey)
                ProvenanceRow(if (isArabic) "10. سجل الـ ERP المتأثر" else "10. ERP Record Changed", "${receipt.erpModel} : ${receipt.erpRecordId}")
                ProvenanceRow(if (isArabic) "11. هاش التحقق القطعي" else "11. Verification Hash", receipt.verificationHash.take(20) + "...")
                ProvenanceRow(if (isArabic) "12. موقع سلسلة الأدلة" else "12. Audit Chain Index", "#${receipt.auditChainIndex} ($dateString)")
                ProvenanceRow(if (isArabic) "13. حالة المعاملة" else "13. Authoritative Result", if (isArabic) "تم التحقق قطعيًا 100%" else "VERIFIED AUTHORITATIVE")

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = glass.accentTeal),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(
                        text = if (isArabic) "إغلاق الإيصال" else "Close Receipt",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ProvenanceRow(label: String, value: String) {
    val glass = LocalLiquidGlass.current
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = glass.accentOrange
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = glass.textPrimary,
            fontFamily = if (value.contains("IDEM-") || value.contains("...")) FontFamily.Monospace else FontFamily.Default
        )
        Divider(color = glass.borderSubtle, modifier = Modifier.padding(top = 4.dp))
    }
}
