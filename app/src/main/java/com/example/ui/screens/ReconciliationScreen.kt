package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoNotDisturb
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.ReconciliationItemEntity
import com.example.ui.components.LiquidGlassCard
import com.example.ui.theme.LocalLiquidGlass
import com.example.viewmodel.MizanViewModel

@Composable
fun ReconciliationScreen(viewModel: MizanViewModel) {
    val items by viewModel.currentReconciliationItems.collectAsStateWithLifecycle()
    val isArabic by viewModel.isArabic.collectAsStateWithLifecycle()
    val glass = LocalLiquidGlass.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(glass.backgroundGradient)
            .padding(16.dp)
    ) {
        // Warning Banner explaining the architectural policy
        LiquidGlassCard(
            shape = RoundedCornerShape(24.dp),
            accentBorder = glass.accentCoral.copy(alpha = 0.7f),
            elevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(glass.accentCoral.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = glass.accentCoral, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isArabic) "مركز تسوية العمليات غير المحسومة" else "RECONCILIATION & AMBIGUITY CENTER",
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        letterSpacing = 1.sp,
                        color = glass.accentCoral
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (isArabic)
                        "Mizan لا يعيد إرسال المعاملات المشبوهة أو المفقودة تلقائيًا حتى لا تتكرر في نظام ERP. نقوم بفحص السجلات المرشحة والمطابقة القطعية قبل الحسم."
                    else
                        "Mizan does not blindly retry ambiguous mutations to prevent double-writes in the ERP. Review candidate records or safely abandon.",
                    fontSize = 12.sp,
                    color = glass.textSecondary,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isArabic) "العمليات المعلقة للتسوية (${items.size})" else "Pending Reconciliation Items (${items.size})",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = glass.textPrimary
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(glass.accentGreen.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = glass.accentGreen,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = if (isArabic) "جميع العمليات متوافقة بنسبة 100%. لا توجد حالات غير محسومة." else "All operations authoritative. Zero ambiguous states.",
                        color = glass.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(items) { item ->
                    ReconciliationCard(
                        item = item,
                        isArabic = isArabic,
                        onResolve = { matchedId, action -> viewModel.resolveAmbiguity(item, matchedId, action) }
                    )
                }
            }
        }
    }
}

@Composable
fun ReconciliationCard(
    item: ReconciliationItemEntity,
    isArabic: Boolean,
    onResolve: (String?, String) -> Unit
) {
    val candidates = item.candidateErpIds.split(",").filter { it.isNotBlank() }
    val isPending = item.resolutionStatus == "PENDING"
    val glass = LocalLiquidGlass.current

    LiquidGlassCard(
        shape = RoundedCornerShape(24.dp),
        accentBorder = if (isPending) glass.accentCoral.copy(alpha = 0.6f) else glass.accentGreen.copy(alpha = 0.5f),
        elevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${item.toolName} • ${item.reconciliationId}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (isPending) glass.accentCoral else glass.accentGreen
                )

                Surface(
                    color = if (isPending) glass.accentCoral.copy(alpha = 0.2f) else glass.accentGreen.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isPending) glass.accentCoral.copy(alpha = 0.4f) else glass.accentGreen.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = item.resolutionStatus,
                        color = if (isPending) glass.accentCoral else glass.accentGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = item.intent,
                fontSize = 13.sp,
                color = glass.textPrimary,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Idempotency: ${item.idempotencyKey}",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = glass.accentOrange
            )

            if (candidates.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (isArabic) "السجلات المرشحة في أودو / ERP:" else "Candidate Records Discovered in ERP:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = glass.accentTeal
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    candidates.forEach { cand ->
                        Surface(
                            color = glass.surfaceElevated.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, glass.accentTeal.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = cand,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = glass.accentTeal,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            if (item.resolutionNotes != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Resolution: ${item.resolutionNotes}",
                    fontSize = 11.sp,
                    color = glass.accentGreen
                )
            }

            if (isPending) {
                Divider(color = glass.borderSubtle, modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { onResolve(null, "ABANDONED") },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = glass.accentCoral),
                        border = androidx.compose.foundation.BorderStroke(1.dp, glass.accentCoral.copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isArabic) "إلغاء المعاملة" else "Abandon")
                    }

                    Button(
                        onClick = { onResolve(candidates.firstOrNull() ?: "SO-CONFIRMED", "MATCHED") },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = glass.accentTeal),
                        modifier = Modifier.weight(1.5f)
                    ) {
                        Icon(imageVector = Icons.Default.Link, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isArabic) "ربط بسجل ERP وإغلاق" else "Match & Reconcile",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
