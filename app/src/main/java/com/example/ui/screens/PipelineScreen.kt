package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
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
import com.example.data.local.ExecutionRecordEntity
import com.example.model.ExecutionState
import com.example.ui.components.LiquidGlassCard
import com.example.ui.components.StateBadge
import com.example.ui.theme.LocalLiquidGlass
import com.example.viewmodel.MizanViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PipelineScreen(viewModel: MizanViewModel) {
    val executions by viewModel.currentExecutions.collectAsStateWithLifecycle()
    val isArabic by viewModel.isArabic.collectAsStateWithLifecycle()
    val currentTenant by viewModel.currentTenant.collectAsStateWithLifecycle()
    val glass = LocalLiquidGlass.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(glass.backgroundGradient)
            .padding(16.dp)
    ) {
        // State Machine Visual Diagram
        LiquidGlassCard(
            shape = RoundedCornerShape(24.dp),
            accentBorder = glass.accentTeal.copy(alpha = 0.5f),
            elevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
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
                        Icon(imageVector = Icons.Default.Timeline, contentDescription = null, tint = glass.accentTeal, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isArabic) "مسار آلة الحالة التنفيذية القطعية" else "CANONICAL EXECUTION STATE MACHINE",
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = glass.accentTeal
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Sequence steps
                val steps = listOf(
                    Pair("PROPOSED", if (isArabic) "اقتراح" else "Proposed"),
                    Pair("VALIDATED", if (isArabic) "فحص" else "Validated"),
                    Pair("RISK_EVAL", if (isArabic) "مخاطر" else "Risk"),
                    Pair("APPROVAL", if (isArabic) "اعتماد" else "Approval"),
                    Pair("LEASE", if (isArabic) "حجز حصري" else "Lease"),
                    Pair("ERP", if (isArabic) "تنفيذ" else "Executing"),
                    Pair("VERIFIED", if (isArabic) "متحقق" else "Verified")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    steps.forEachIndexed { idx, step ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(glass.surfaceElevated)
                                    .border(1.dp, glass.accentTeal, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${idx + 1}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = glass.accentTeal
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = step.second,
                                fontSize = 9.sp,
                                color = glass.textSecondary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Execution History List
        Text(
            text = if (isArabic) "سجل العمليات والمفاتيح (${executions.size})" else "Active & Recent Executions (${executions.size})",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = glass.textPrimary
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (executions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isArabic) "لا توجد عمليات مسجلة لهذا المستأجر حتى الآن." else "No executions recorded yet for this tenant.",
                    color = glass.textMuted,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(executions) { exec ->
                    ExecutionCard(exec = exec, isArabic = isArabic)
                }
            }
        }
    }
}

@Composable
fun ExecutionCard(exec: ExecutionRecordEntity, isArabic: Boolean) {
    val dateFormat = SimpleDateFormat("HH:mm:ss • dd MMM", Locale.US)
    val timeFormatted = dateFormat.format(Date(exec.timestamp))
    val stateEnum = runCatching { ExecutionState.valueOf(exec.currentState) }.getOrDefault(ExecutionState.PROPOSED)
    val glass = LocalLiquidGlass.current

    LiquidGlassCard(
        shape = RoundedCornerShape(24.dp),
        accentBorder = glass.borderSubtle,
        elevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = exec.toolName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = glass.accentTeal
                    )
                    Text(
                        text = "${exec.executionId} • $timeFormatted",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = glass.textMuted
                    )
                }

                StateBadge(state = stateEnum, isArabic = isArabic)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = exec.rawIntent,
                fontSize = 12.sp,
                color = glass.textSecondary,
                maxLines = 2
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Idempotency & Lease badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = glass.surfaceElevated.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, glass.borderSubtle),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.Key, contentDescription = null, tint = glass.accentOrange, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = exec.idempotencyKey.take(14) + "...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = glass.accentOrange
                        )
                    }
                }

                if (exec.leaseId != null) {
                    Surface(
                        color = glass.surfaceElevated.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, glass.borderSubtle),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.Default.LockClock, contentDescription = null, tint = glass.accentBlue, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Lease: ${exec.leaseId.take(10)}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = glass.accentBlue
                            )
                        }
                    }
                }
            }

            if (exec.erpRecordId != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = glass.accentGreen.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, glass.accentGreen.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isArabic) "سجل ERP المعتمد: ${exec.erpRecordId}" else "Authoritative ERP Record: ${exec.erpRecordId}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = glass.accentGreen,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
