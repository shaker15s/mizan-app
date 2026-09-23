package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
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
import com.example.data.local.AuditRecordEntity
import com.example.data.local.TrustReceiptEntity
import com.example.model.TrustReceipt
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
import com.example.ui.components.LiquidGlassCard
import com.example.ui.theme.LocalLiquidGlass
import com.example.viewmodel.MizanViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EvidenceScreen(viewModel: MizanViewModel) {
    val auditRecords by viewModel.currentAuditRecords.collectAsStateWithLifecycle()
    val receipts by viewModel.currentReceipts.collectAsStateWithLifecycle()
    val chainReport by viewModel.chainReport.collectAsStateWithLifecycle()
    val isArabic by viewModel.isArabic.collectAsStateWithLifecycle()
    val isVerifyingChain by viewModel.isVerifyingChain.collectAsStateWithLifecycle()
    val verificationProgress by viewModel.verificationProgress.collectAsStateWithLifecycle()
    val liveInspectedHash by viewModel.liveInspectedHash.collectAsStateWithLifecycle()
    val glass = LocalLiquidGlass.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(glass.backgroundGradient)
            .padding(16.dp)
    ) {
        // Hash Chain Integrity Banner & Verification Trigger
        LiquidGlassCard(
            shape = RoundedCornerShape(24.dp),
            accentBorder = if (isVerifyingChain) glass.accentTeal else glass.accentTeal.copy(alpha = 0.7f),
            elevation = 6.dp,
            modifier = Modifier.fillMaxWidth().testTag("card_evidence_integrity")
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(glass.accentTeal.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Default.Fingerprint, contentDescription = null, tint = glass.accentTeal, modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isArabic) "سلسلة الأدلة المشفرة (SHA-256 Chain)" else "SHA-256 AUDIT EVIDENCE CHAIN",
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            letterSpacing = 1.sp,
                            color = glass.accentTeal
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { viewModel.testTamperSimulation() },
                            enabled = !isVerifyingChain,
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = glass.surfaceElevated),
                            border = androidx.compose.foundation.BorderStroke(1.dp, glass.accentCoral.copy(alpha = 0.5f)),
                            modifier = Modifier.testTag("btn_evidence_test_tamper")
                        ) {
                            Icon(imageVector = Icons.Default.Shield, contentDescription = null, tint = glass.accentCoral, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isArabic) "محاكاة هجوم" else "Attack Test",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = glass.accentCoral
                            )
                        }

                        Button(
                            onClick = { viewModel.verifyAuditChain() },
                            enabled = !isVerifyingChain,
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = glass.accentTeal),
                            modifier = Modifier.testTag("btn_verify_chain")
                        ) {
                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isArabic) "فحص السلسلة" else "Verify Chain",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                // Live Verification Progress Bar
                if (isVerifyingChain) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = glass.surfaceElevated,
                        border = androidx.compose.foundation.BorderStroke(1.dp, glass.accentTeal.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isArabic) "جارٍ فحص التوقيعات الرقمية لكل كتلة..." else "Cryptographically verifying blocks...",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = glass.accentTeal
                                )
                                Text(
                                    text = "${(verificationProgress * 100).toInt()}%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = glass.accentTeal
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { verificationProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = glass.accentTeal,
                                trackColor = glass.surfaceGlass
                            )
                            if (liveInspectedHash.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "HASH: ${liveInspectedHash.take(28)}...",
                                    fontSize = 9.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = glass.textMuted
                                )
                            }
                        }
                    }
                }

                if (chainReport != null && !isVerifyingChain) {
                    val report = chainReport!!
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = if (report.isValid) glass.accentGreen.copy(alpha = 0.15f) else glass.accentCoral.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (report.isValid) glass.accentGreen.copy(alpha = 0.5f) else glass.accentCoral.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (report.isValid) glass.accentGreen else glass.accentCoral)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (report.isValid) "INTEGRITY: 100% UNBROKEN" else "TAMPER DETECTED",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (report.isValid) glass.accentGreen else glass.accentCoral
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isArabic) report.messageAr else report.messageEn,
                                fontSize = 11.sp,
                                color = glass.textPrimary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Receipts Section
        if (receipts.isNotEmpty()) {
            Text(
                text = if (isArabic) "إيصالات الثقة الموثقة (${receipts.size})" else "Minted Trust Receipts (${receipts.size})",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MizanGold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                receipts.take(2).forEach { rec ->
                    ReceiptQuickCard(
                        receipt = rec,
                        onOpen = {
                            viewModel.selectTrustReceipt(
                                TrustReceipt(
                                    receiptId = rec.receiptId,
                                    traceId = rec.traceId,
                                    executionId = rec.executionId,
                                    timestamp = rec.timestamp,
                                    tenantId = rec.tenantId,
                                    tenantName = rec.tenantName,
                                    initiatorId = rec.initiatorId,
                                    initiatorName = rec.initiatorName,
                                    initiatorRole = rec.initiatorRole,
                                    intent = rec.intent,
                                    toolName = rec.toolName,
                                    toolVersion = rec.toolVersion,
                                    policyRuleId = rec.policyRuleId,
                                    approvalLevel = rec.approvalLevel,
                                    riskTier = rec.riskTier,
                                    approverId = rec.approverId,
                                    approverName = rec.approverName,
                                    sodProof = rec.sodProof,
                                    canonicalArgumentsJson = rec.canonicalArgumentsJson,
                                    idempotencyKey = rec.idempotencyKey,
                                    erpRecordId = rec.erpRecordId,
                                    erpModel = rec.erpModel,
                                    verificationHash = rec.verificationHash,
                                    auditChainIndex = rec.auditChainIndex,
                                    tamperProofToken = rec.tamperProofToken
                                )
                            )
                        }
                    )
                }
            }
        }

        // Ledger Records List
        Text(
            text = if (isArabic) "كتل سلسلة الأدلة (Audit Blocks: ${auditRecords.size})" else "Immutable Audit Blocks (${auditRecords.size})",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MizanTextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(auditRecords) { rec ->
                AuditBlockCard(rec = rec)
            }
        }
    }
}

@Composable
fun ReceiptQuickCard(receipt: TrustReceiptEntity, onOpen: () -> Unit) {
    val glass = LocalLiquidGlass.current
    LiquidGlassCard(
        shape = RoundedCornerShape(24.dp),
        accentBorder = glass.accentTeal.copy(alpha = 0.4f),
        elevation = 3.dp,
        modifier = Modifier.clickable { onOpen() }
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Default.ReceiptLong, contentDescription = null, tint = glass.accentTeal, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = receipt.receiptId,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = glass.accentTeal
                )
                Text(
                    text = "${receipt.erpModel}: ${receipt.erpRecordId}",
                    fontSize = 10.sp,
                    color = glass.textSecondary
                )
            }
        }
    }
}

@Composable
fun AuditBlockCard(rec: AuditRecordEntity) {
    val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    val timeFormatted = dateFormat.format(Date(rec.timestamp))
    val glass = LocalLiquidGlass.current

    LiquidGlassCard(
        shape = RoundedCornerShape(24.dp),
        accentBorder = glass.borderSubtle,
        elevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(glass.accentTeal.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "#${rec.chainIndex}",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = glass.accentTeal
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = rec.action,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = glass.textPrimary
                    )
                }

                Text(
                    text = timeFormatted,
                    fontSize = 10.sp,
                    color = glass.textMuted
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Actor: ${rec.actorId} • Trace: ${rec.traceId}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = glass.accentOrange
            )

            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                color = glass.surfaceElevated.copy(alpha = 0.7f),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, glass.borderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "Prev: ${rec.previousHash.take(24)}...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = glass.textMuted
                    )
                    Text(
                        text = "Curr: ${rec.currentHash.take(24)}...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = glass.accentTeal
                    )
                }
            }
        }
    }
}
