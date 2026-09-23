package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.evidence.ChainVerificationReport
import com.example.ui.theme.LocalLiquidGlass

/**
 * Interactive Quick ERP Operations & Cryptographic Chain Inspector Card
 */
@Composable
fun QuickErpActionsCard(
    isArabic: Boolean,
    isVerifyingChain: Boolean,
    verificationProgress: Float,
    inspectedHash: String,
    chainReport: ChainVerificationReport?,
    onVerifyChain: () -> Unit,
    onSimulateTamper: () -> Unit,
    onQuickOrder: (customer: String, amount: Double, items: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalLiquidGlass.current
    var isExpanded by remember { mutableStateOf(false) }

    LiquidGlassCard(
        shape = RoundedCornerShape(24.dp),
        accentBorder = if (isVerifyingChain) glass.accentTeal else glass.borderGlass,
        elevation = 6.dp,
        modifier = modifier.testTag("card_quick_erp_actions")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(glass.accentTeal.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = glass.accentTeal,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = if (isArabic) "إجراءات ERP السريعة وفحص الأمان التشفيري" else "Quick ERP Engine & Zero-Trust Checks",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = glass.textPrimary
                        )
                        Text(
                            text = if (isArabic) "تنفيذ عمليات Odoo فورية وفحص سلاسل SHA-256 بنقرة واحدة" else "One-tap transactional triggers & immutable chain audit",
                            fontSize = 11.sp,
                            color = glass.textMuted
                        )
                    }
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand",
                    tint = glass.textMuted,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Live Verification Progression Banner (When running)
            if (isVerifyingChain) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = glass.surfaceElevated,
                    border = BorderStroke(1.dp, glass.accentTeal.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isArabic) "جارٍ فحص سلامة السلسلة..." else "Traversing SHA-256 Chain...",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = glass.accentTeal
                            )
                            Text(
                                text = "${(verificationProgress * 100).toInt()}%",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = glass.accentTeal,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        LinearProgressIndicator(
                            progress = { verificationProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = glass.accentTeal,
                            trackColor = glass.surfaceGlass
                        )

                        if (inspectedHash.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "BLOCK HASH: ${inspectedHash.take(24)}...",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = glass.textMuted
                            )
                        }
                    }
                }
            }

            // Active Report Summary (if present and not expanded)
            if (chainReport != null && !isVerifyingChain) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (chainReport.isValid) glass.accentGreen.copy(alpha = 0.12f) else glass.accentCoral.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, if (chainReport.isValid) glass.accentGreen.copy(alpha = 0.4f) else glass.accentCoral.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (chainReport.isValid) glass.accentGreen else glass.accentCoral)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isArabic) chainReport.messageAr else chainReport.messageEn,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (chainReport.isValid) glass.accentGreen else glass.accentCoral
                            )
                        }
                    }
                }
            }

            // Quick Actions Action Grid (Expandable)
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isArabic) "أوامر تجريبية سريعة وموثقة:" else "Simulate Live ERP Operations:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = glass.textSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuickActionButton(
                            icon = Icons.Default.AddShoppingCart,
                            title = if (isArabic) "أمر بيع $8,500" else "Sale \$8,500",
                            subtitle = if (isArabic) "ألياف ضوئية" else "Fiber Optic",
                            tint = glass.accentTeal,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onQuickOrder(
                                    "Alexandria Logistics",
                                    8500.0,
                                    "Armored Fiber Cable 500m x 20"
                                )
                            }
                        )

                        QuickActionButton(
                            icon = Icons.Default.Sync,
                            title = if (isArabic) "خادم 2U مؤسسي" else "Server 2U",
                            subtitle = if (isArabic) "بقيمة $12,800" else "\$12,800 Order",
                            tint = glass.accentBlue,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onQuickOrder(
                                    "Cairo Tech Solutions",
                                    12800.0,
                                    "Enterprise Server Blade 2U x 4"
                                )
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (isArabic) "فحص واختبار التشفير والمناعة ضد التلاعب:" else "Cryptographic Proof & Attack Resilience:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = glass.textSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onVerifyChain,
                            enabled = !isVerifyingChain,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = glass.accentTeal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_quick_verify_chain")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isArabic) "فحص السلسلة" else "Verify Chain",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Button(
                            onClick = onSimulateTamper,
                            enabled = !isVerifyingChain,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = glass.surfaceElevated),
                            border = BorderStroke(1.dp, glass.accentCoral.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_quick_test_tamper")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = glass.accentCoral,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isArabic) "محاكاة اختراق" else "Test Attack",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = glass.accentCoral
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalLiquidGlass.current

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = glass.surfaceElevated,
        border = BorderStroke(1.dp, glass.borderGlass),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = glass.textMuted
                )
            }
        }
    }
}
