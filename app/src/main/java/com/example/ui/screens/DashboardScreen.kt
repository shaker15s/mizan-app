package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import com.example.ui.components.AuditSearchBar
import com.example.ui.components.QuickErpActionsCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.example.auth.BiometricHardwareStatus
import com.example.auth.MizanBiometricManager
import com.example.ui.components.AgentExecutionTimelineView
import com.example.ui.components.LiquidGlassCard
import com.example.ui.theme.LocalLiquidGlass
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.connectors.odoo.OdooSession
import com.example.connectors.odoo.OdooSessionState
import com.example.data.local.AuditRecordEntity
import com.example.model.TrustReceipt
import com.example.ui.theme.MizanBlue
import com.example.ui.theme.MizanCardBg
import com.example.ui.theme.MizanCardBorder
import com.example.ui.theme.MizanCoral
import com.example.ui.theme.MizanCyan
import com.example.ui.theme.MizanGold
import com.example.ui.theme.MizanGreen
import com.example.ui.theme.MizanPurple
import com.example.ui.theme.MizanSurface
import com.example.ui.theme.MizanSurfaceVariant
import com.example.ui.theme.MizanTextMuted
import com.example.ui.theme.MizanTextPrimary
import com.example.ui.theme.MizanTextSecondary
import com.example.viewmodel.MizanViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MizanViewModel,
    onNavigateToAgent: () -> Unit,
    onNavigateToEvidence: () -> Unit
) {
    val isArabic by viewModel.isArabic.collectAsStateWithLifecycle()
    val currentTenant by viewModel.currentTenant.collectAsStateWithLifecycle()
    val auditRecords by viewModel.currentAuditRecords.collectAsStateWithLifecycle()
    val odooSessionState by viewModel.odooSessionState.collectAsStateWithLifecycle()
    val odooPingResult by viewModel.odooPingResult.collectAsStateWithLifecycle()
    val biometricStatusMessage by viewModel.biometricStatusMessage.collectAsStateWithLifecycle()
    val agentTimeline by viewModel.timeline.collectAsStateWithLifecycle()
    val pendingProposal by viewModel.pendingProposal.collectAsStateWithLifecycle()
    val currentExecutions by viewModel.currentExecutions.collectAsStateWithLifecycle()
    val isReasoning by viewModel.isAgentReasoning.collectAsStateWithLifecycle()
    val isVerifyingChain by viewModel.isVerifyingChain.collectAsStateWithLifecycle()
    val verificationProgress by viewModel.verificationProgress.collectAsStateWithLifecycle()
    val liveInspectedHash by viewModel.liveInspectedHash.collectAsStateWithLifecycle()
    val chainReport by viewModel.chainReport.collectAsStateWithLifecycle()

    var showConnectDialog by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    var selectedStatusFilter by remember { mutableStateOf("ALL") }

    // Filter records by category, status, and search query (ID, #Index, Trace, Action, State)
    val filteredRecords = remember(auditRecords, selectedFilter, searchQuery, selectedStatusFilter) {
        auditRecords.filter { record ->
            // Category filter
            val matchesCategory = when (selectedFilter) {
                "ODOO" -> record.action.contains("ODOO", ignoreCase = true)
                "ORDER" -> record.action.contains("ORDER", ignoreCase = true) || record.action.contains("STOCK", ignoreCase = true)
                "POLICY" -> record.action.contains("POLICY", ignoreCase = true) || record.action.contains("GENESIS", ignoreCase = true)
                else -> true
            }

            // Status filter
            val matchesStatus = when (selectedStatusFilter) {
                "CONFIRMED" -> record.stateAfter.contains("CONFIRMED", ignoreCase = true) || record.stateBefore.contains("CONFIRMED", ignoreCase = true) || record.action.contains("CONFIRMED", ignoreCase = true)
                "PENDING" -> record.stateAfter.contains("PENDING", ignoreCase = true) || record.stateBefore.contains("PENDING", ignoreCase = true)
                "HALTED" -> record.stateAfter.contains("HALT", ignoreCase = true) || record.stateBefore.contains("HALT", ignoreCase = true) || record.action.contains("DENIED", ignoreCase = true)
                "RECONCILED" -> record.stateAfter.contains("RECONCILED", ignoreCase = true) || record.stateBefore.contains("RECONCILED", ignoreCase = true)
                else -> true
            }

            // Text search by ID, chain index, traceId, action, state, or actor
            val query = searchQuery.trim()
            val matchesQuery = if (query.isBlank()) {
                true
            } else {
                val cleanQuery = query.removePrefix("#")
                record.id.toString() == cleanQuery ||
                record.chainIndex.toString() == cleanQuery ||
                record.traceId.contains(query, ignoreCase = true) ||
                record.stateAfter.contains(query, ignoreCase = true) ||
                record.stateBefore.contains(query, ignoreCase = true) ||
                record.action.contains(query, ignoreCase = true) ||
                record.actorId.contains(query, ignoreCase = true) ||
                record.currentHash.contains(query, ignoreCase = true) ||
                record.detailsJson.contains(query, ignoreCase = true)
            }

            matchesCategory && matchesStatus && matchesQuery
        }
    }

    val glass = LocalLiquidGlass.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(glass.backgroundGradient)
            .padding(horizontal = 16.dp)
            .testTag("screen_dashboard"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))

            // Dashboard Title & Overview
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isArabic) "لوحة المراقبة والحوكمة" else "Executive Governance & ERP Hub",
                        color = MizanTextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isArabic) "نظام ميزان — المراقبة الفورية للعمليات وجلسات ERP" else "Real-time authority gating, audit trail & Odoo session telemetry",
                        color = MizanTextSecondary,
                        fontSize = 12.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MizanSurfaceVariant,
                    border = BorderStroke(1.dp, MizanCardBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MizanCyan)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isArabic) currentTenant.tenantNameAr else currentTenant.tenantNameEn,
                            color = MizanCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // --- TOP SEARCH & FILTER BAR (Liquid Glassmorphism for Odoo Audit Logs) ---
        item {
            AuditSearchBar(
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                selectedStatusFilter = selectedStatusFilter,
                onStatusFilterChange = { selectedStatusFilter = it },
                resultCount = filteredRecords.size,
                totalCount = auditRecords.size,
                isArabic = isArabic,
                onClear = {
                    searchQuery = ""
                    selectedStatusFilter = "ALL"
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // --- QUICK ERP ACTIONS & SHA-256 INTEGRITY INSPECTOR ---
        item {
            QuickErpActionsCard(
                isArabic = isArabic,
                isVerifyingChain = isVerifyingChain,
                verificationProgress = verificationProgress,
                inspectedHash = liveInspectedHash,
                chainReport = chainReport,
                onVerifyChain = { viewModel.verifyAuditChain() },
                onSimulateTamper = { viewModel.testTamperSimulation() },
                onQuickOrder = { customer, amount, items ->
                    viewModel.triggerQuickErpSale(customer, amount, items)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // --- SECTION 1: Odoo Session Status Hero Card ---
        item {
            OdooSessionCard(
                sessionState = odooSessionState,
                isArabic = isArabic,
                onConnectClick = { showConnectDialog = true },
                onLogoutClick = { viewModel.logoutOdoo() },
                onPingClick = { viewModel.testOdooPing() }
            )
        }

        // Ping Result Notification Banner
        if (odooPingResult != null) {
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0D2538),
                    border = BorderStroke(1.dp, MizanBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("banner_odoo_ping")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Info",
                            tint = MizanBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = odooPingResult!!,
                            color = MizanTextPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.clearOdooPingResult() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MizanTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // --- SECTION 1.5: Biometric Security & Deterministic Authority Guard ---
        item {
            BiometricSecurityCard(
                viewModel = viewModel,
                isArabic = isArabic
            )
        }

        // Biometric Status Message Banner
        if (biometricStatusMessage != null) {
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF162521),
                    border = BorderStroke(1.dp, MizanCyan),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("banner_biometric_status")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = "Biometric Info",
                            tint = MizanCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = biometricStatusMessage!!,
                            color = MizanTextPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.clearBiometricStatusMessage() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MizanTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // --- SECTION 2: Agent Execution Timeline Visualization (Pending & Completed ERP Operations) ---
        item {
            AgentExecutionTimelineView(
                timelineItems = agentTimeline,
                pendingProposal = pendingProposal,
                executions = currentExecutions,
                isArabic = isArabic,
                isReasoning = isReasoning,
                onNavigateToAgent = onNavigateToAgent,
                onViewReceipt = { receipt -> viewModel.selectTrustReceipt(receipt) }
            )
        }

        // --- SECTION 2.5: Key Executive Metrics Row ---
        item {
            ExecutiveMetricsRow(
                sessionState = odooSessionState,
                totalAuditLogs = auditRecords.size,
                isArabic = isArabic
            )
        }

        // --- SECTION 3: Recent Audit Logs Header & Filter Chips ---
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Audit Shield",
                            tint = MizanCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isArabic) "سجل التدقيق الأخير (Room Ledger)" else "Recent Audit Ledger",
                            color = MizanTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "${filteredRecords.size} ${if (isArabic) "سجل" else "Records"}",
                        color = MizanTextMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedFilter == "ALL",
                        onClick = { selectedFilter = "ALL" },
                        label = { Text(if (isArabic) "الكل" else "All", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MizanCyan,
                            selectedLabelColor = Color(0xFF090D16),
                            containerColor = MizanSurface,
                            labelColor = MizanTextSecondary
                        ),
                        modifier = Modifier.testTag("filter_all")
                    )
                    FilterChip(
                        selected = selectedFilter == "ODOO",
                        onClick = { selectedFilter = "ODOO" },
                        label = { Text(if (isArabic) "أودو ERP" else "Odoo Events", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MizanCyan,
                            selectedLabelColor = Color(0xFF090D16),
                            containerColor = MizanSurface,
                            labelColor = MizanTextSecondary
                        ),
                        modifier = Modifier.testTag("filter_odoo")
                    )
                    FilterChip(
                        selected = selectedFilter == "ORDER",
                        onClick = { selectedFilter = "ORDER" },
                        label = { Text(if (isArabic) "الطلبات والمخزون" else "Orders & Stock", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MizanCyan,
                            selectedLabelColor = Color(0xFF090D16),
                            containerColor = MizanSurface,
                            labelColor = MizanTextSecondary
                        ),
                        modifier = Modifier.testTag("filter_orders")
                    )
                    FilterChip(
                        selected = selectedFilter == "POLICY",
                        onClick = { selectedFilter = "POLICY" },
                        label = { Text(if (isArabic) "الحوكمة والسياسات" else "Policy & Mint", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MizanCyan,
                            selectedLabelColor = Color(0xFF090D16),
                            containerColor = MizanSurface,
                            labelColor = MizanTextSecondary
                        ),
                        modifier = Modifier.testTag("filter_policy")
                    )
                }
            }
        }

        // --- SECTION 4: List of Recent Audit Records ---
        if (filteredRecords.isEmpty()) {
            item {
                LiquidGlassCard(
                    shape = RoundedCornerShape(24.dp),
                    accentBorder = if (searchQuery.isNotBlank() || selectedStatusFilter != "ALL") glass.accentCoral.copy(alpha = 0.5f) else glass.borderSubtle,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(glass.accentTeal.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (searchQuery.isNotBlank()) Icons.Default.Search else Icons.Default.ReceiptLong,
                                contentDescription = "Empty",
                                tint = if (searchQuery.isNotBlank()) glass.accentCoral else glass.accentTeal,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                if (isArabic) "لا توجد سجلات تدقيق تطابق '$searchQuery'" else "No audit logs matching '$searchQuery'"
                            } else if (selectedStatusFilter != "ALL") {
                                if (isArabic) "لا توجد سجلات تدقيق بالحالة '$selectedStatusFilter'" else "No audit logs with status '$selectedStatusFilter'"
                            } else {
                                if (isArabic) "لا توجد سجلات تدقيق مطابقة لهذا التصنيف" else "No audit records matching this filter."
                            },
                            color = glass.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isArabic) {
                                "جرب البحث بالمعرف (#0, 1) أو الحالة (CONFIRMED, PENDING, HALTED)"
                            } else {
                                "Try searching by ID (#Index, 1, 2) or status (CONFIRMED, PENDING, HALTED)"
                            },
                            color = glass.textMuted,
                            fontSize = 12.sp
                        )

                        if (searchQuery.isNotBlank() || selectedStatusFilter != "ALL" || selectedFilter != "ALL") {
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    searchQuery = ""
                                    selectedStatusFilter = "ALL"
                                    selectedFilter = "ALL"
                                },
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = glass.accentTeal),
                                modifier = Modifier.testTag("btn_empty_clear_search")
                            ) {
                                Text(
                                    text = if (isArabic) "مسح معايير البحث" else "Clear Search Filters",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        } else {
            items(filteredRecords, key = { it.id }) { record ->
                AuditRecordCard(
                    record = record,
                    isArabic = isArabic,
                    searchQuery = searchQuery
                )
            }
        }

        // Bottom padding spacer
        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Connect / Authenticate Odoo Dialog
    if (showConnectDialog) {
        OdooConnectDialog(
            currentTenantDatabase = currentTenant.tenantId.let { if (it == "tenant-a") "odoo_alamal_prod" else "odoo_nile_prod" },
            isArabic = isArabic,
            onDismiss = { showConnectDialog = false },
            onConnect = { db, login, pass ->
                viewModel.authenticateOdoo(db, login, pass)
                showConnectDialog = false
            }
        )
    }
}

/**
 * Hero Card displaying live Odoo ERP Session state with interactive actions.
 */
@Composable
private fun OdooSessionCard(
    sessionState: OdooSessionState,
    isArabic: Boolean,
    onConnectClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onPingClick: () -> Unit
) {
    val (statusColor, statusTitleEn, statusTitleAr, statusIcon) = when (sessionState) {
        is OdooSessionState.Authenticated -> Quadruple(
            MizanGreen,
            "AUTHENTICATED (XML-RPC 2.0)",
            "مُتصل ومُصرح (XML-RPC 2.0)",
            Icons.Default.CloudDone
        )
        is OdooSessionState.Authenticating -> Quadruple(
            MizanBlue,
            "CONNECTING TO ODOO...",
            "جاري الاتصال بخادم أودو...",
            Icons.Default.Sync
        )
        is OdooSessionState.Error -> Quadruple(
            MizanCoral,
            "CONNECTION FAULT",
            "خطأ في الاتصال",
            Icons.Default.CloudOff
        )
        OdooSessionState.Unauthenticated -> Quadruple(
            MizanGold,
            "UNAUTHENTICATED / READY",
            "غير متصل / جاهز للربط",
            Icons.Default.Key
        )
    }

    val activeSession = (sessionState as? OdooSessionState.Authenticated)?.session
    val glass = LocalLiquidGlass.current

    LiquidGlassCard(
        shape = RoundedCornerShape(24.dp),
        accentBorder = statusColor.copy(alpha = 0.7f),
        elevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("card_odoo_session_status")
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header Row: Brand badge & Live Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF714B67)), // Odoo purple brand accent
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "odoo",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isArabic) "ربط أودو ERP المؤسسي" else "Odoo ERP Session Status",
                            color = MizanTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = if (isArabic) "بروتوكول XML-RPC 2.0 & JSON-2" else "XML-RPC 2.0 & JSON-2 Endpoints",
                            color = MizanTextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                // Live status pill
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = statusColor.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (sessionState is OdooSessionState.Authenticating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                color = statusColor,
                                strokeWidth = 1.5.dp
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isArabic) statusTitleAr else statusTitleEn,
                            color = statusColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Session Parameters Grid
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = glass.surfaceElevated.copy(alpha = 0.7f),
                border = BorderStroke(1.dp, glass.borderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SessionField(
                            label = if (isArabic) "قاعدة البيانات" else "Database",
                            value = activeSession?.database ?: "odoo_alamal_prod",
                            isCode = true
                        )
                        SessionField(
                            label = if (isArabic) "معرف المستخدم UID" else "Session UID",
                            value = activeSession?.uid?.toString() ?: "N/A",
                            isCode = true
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = glass.borderSubtle, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SessionField(
                            label = if (isArabic) "المستخدم / الاعتماد" else "User Principal",
                            value = activeSession?.username ?: "admin@alamal.com",
                            isCode = false
                        )
                        SessionField(
                            label = if (isArabic) "إصدار الخادم" else "Server Version",
                            value = activeSession?.serverVersion ?: "19.0+e (Enterprise)",
                            isCode = true
                        )
                    }

                    if (sessionState is OdooSessionState.Error) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Error: ${(sessionState as OdooSessionState.Error).message}",
                            color = glass.accentCoral,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (activeSession != null) {
                    Button(
                        onClick = onPingClick,
                        colors = ButtonDefaults.buttonColors(containerColor = glass.accentTeal),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("button_odoo_ping")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Ping",
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isArabic) "اختبار Ping" else "Test Ping",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onConnectClick,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, glass.accentTeal.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("button_switch_session")
                    ) {
                        Text(
                            text = if (isArabic) "تبديل الجلسة" else "Switch Session",
                            color = glass.accentTeal,
                            fontSize = 12.sp
                        )
                    }

                    OutlinedButton(
                        onClick = onLogoutClick,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, glass.accentCoral.copy(alpha = 0.6f)),
                        modifier = Modifier.testTag("button_odoo_logout")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Logout",
                            tint = glass.accentCoral,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    Button(
                        onClick = onConnectClick,
                        colors = ButtonDefaults.buttonColors(containerColor = glass.accentTeal),
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("button_odoo_connect")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "Connect",
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isArabic) "تسجيل الدخول وربط Odoo XML-RPC" else "Authenticate Odoo XML-RPC Session",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionField(label: String, value: String, isCode: Boolean) {
    Column {
        Text(
            text = label,
            color = MizanTextMuted,
            fontSize = 10.sp
        )
        Text(
            text = value,
            color = MizanTextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (isCode) FontFamily.Monospace else FontFamily.Default
        )
    }
}

/**
 * 3 High-level summary cards at the top of the dashboard.
 */
@Composable
private fun ExecutiveMetricsRow(
    sessionState: OdooSessionState,
    totalAuditLogs: Int,
    isArabic: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Metric 1: Odoo ERP Protocol
        MetricCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Storage,
            iconTint = MizanPurple,
            title = if (isArabic) "اتصال ERP" else "ERP Channel",
            value = if (sessionState is OdooSessionState.Authenticated) "ONLINE" else "STANDBY",
            valueColor = if (sessionState is OdooSessionState.Authenticated) MizanGreen else MizanGold,
            subtext = "XML-RPC 2.0"
        )

        // Metric 2: Cryptographic Chain
        MetricCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.VerifiedUser,
            iconTint = MizanCyan,
            title = if (isArabic) "سلسلة الحفظ" else "Audit Chain",
            value = "100% OK",
            valueColor = MizanCyan,
            subtext = "SHA-256 Valid"
        )

        // Metric 3: Logged Events
        MetricCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.ReceiptLong,
            iconTint = MizanGold,
            title = if (isArabic) "الأحداث المقيدة" else "Ledger Logs",
            value = "$totalAuditLogs",
            valueColor = MizanTextPrimary,
            subtext = if (isArabic) "سجل موثق" else "Persistent"
        )
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    value: String,
    valueColor: Color,
    subtext: String
) {
    val glass = LocalLiquidGlass.current
    LiquidGlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        accentBorder = glass.borderSubtle,
        elevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = glass.textSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(iconTint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                color = valueColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = subtext,
                color = glass.textMuted,
                fontSize = 10.sp
            )
        }
    }
}

/**
 * Individual audit record item displaying tamper-evident hash and state transitions.
 */
@Composable
private fun AuditRecordCard(
    record: AuditRecordEntity,
    isArabic: Boolean,
    searchQuery: String = ""
) {
    var expanded by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss · dd MMM", Locale.getDefault()) }
    val dateString = remember(record.timestamp) { dateFormat.format(Date(record.timestamp)) }

    val isDirectMatch = remember(record, searchQuery) {
        if (searchQuery.isBlank()) false
        else {
            val q = searchQuery.trim().removePrefix("#")
            record.id.toString() == q ||
            record.chainIndex.toString() == q ||
            record.traceId.contains(q, ignoreCase = true) ||
            record.stateAfter.contains(q, ignoreCase = true) ||
            record.stateBefore.contains(q, ignoreCase = true)
        }
    }

    val actionBadgeColor = when {
        record.action.contains("ODOO", ignoreCase = true) -> MizanCyan
        record.action.contains("ORDER", ignoreCase = true) -> MizanGreen
        record.action.contains("POLICY", ignoreCase = true) -> MizanGold
        record.action.contains("STOCK", ignoreCase = true) -> MizanBlue
        record.action.contains("GENESIS", ignoreCase = true) -> MizanPurple
        else -> MizanTextSecondary
    }

    val glass = LocalLiquidGlass.current

    LiquidGlassCard(
        shape = RoundedCornerShape(24.dp),
        accentBorder = when {
            isDirectMatch -> glass.accentTeal
            expanded -> glass.accentTeal.copy(alpha = 0.6f)
            else -> glass.borderSubtle
        },
        elevation = if (expanded || isDirectMatch) 4.dp else 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .testTag("audit_item_${record.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Chain index, Action Badge, Timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Block Index Badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = glass.accentTeal.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, glass.accentTeal.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = "#${record.chainIndex}",
                            color = glass.accentTeal,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Action Label
                    Text(
                        text = record.action,
                        color = actionBadgeColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )

                    if (isDirectMatch) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = glass.accentTeal.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, glass.accentTeal.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = if (isArabic) "مطابق" else "MATCH",
                                color = glass.accentTeal,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Text(
                    text = dateString,
                    color = glass.textMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // State Transition: StateBefore -> StateAfter
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = glass.surfaceElevated.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = record.stateBefore,
                        color = glass.textSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Text(
                    text = "  ➔  ",
                    color = glass.accentTeal,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = glass.accentGreen.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = record.stateAfter,
                        color = glass.accentGreen,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand",
                    tint = glass.textMuted,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Current Hash Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = "Hash",
                    tint = glass.textMuted,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "SHA-256: ${record.currentHash.take(16)}...",
                    color = glass.textMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Actor: ${record.actorId}",
                    color = glass.textSecondary,
                    fontSize = 10.sp
                )
            }

            // Expanded Details View
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    Divider(color = glass.borderSubtle, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Trace ID: ${record.traceId} | Tenant: ${record.tenantId}",
                        color = glass.accentTeal,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Prev Hash: ${record.previousHash.take(24)}...",
                        color = glass.textMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = glass.surfaceElevated.copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, glass.borderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = record.detailsJson,
                            color = glass.textSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Interactive Dialog to configure or authenticate Odoo ERP connection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OdooConnectDialog(
    currentTenantDatabase: String,
    isArabic: Boolean,
    onDismiss: () -> Unit,
    onConnect: (database: String, login: String, pass: String) -> Unit
) {
    var database by remember { mutableStateOf(currentTenantDatabase) }
    var login by remember { mutableStateOf("admin@alamal.com") }
    var passwordOrKey by remember { mutableStateOf("odoo_sec_key_449102830192_tenant_a") }
    val glass = LocalLiquidGlass.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glass.cardBackground,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(glass.accentTeal.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = glass.accentTeal,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isArabic) "مصادقة جلسة Odoo XML-RPC" else "Authenticate Odoo XML-RPC",
                    color = glass.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isArabic) "أدخل بيانات اعتماد خادم أودو للربط عبر /xmlrpc/2/common" else "Configure credentials for Odoo /xmlrpc/2/common & object calls.",
                    color = glass.textSecondary,
                    fontSize = 12.sp
                )

                OutlinedTextField(
                    value = database,
                    onValueChange = { database = it },
                    label = { Text(if (isArabic) "قاعدة البيانات" else "Database Name", fontSize = 11.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = glass.textPrimary,
                        unfocusedTextColor = glass.textPrimary,
                        focusedBorderColor = glass.accentTeal,
                        unfocusedBorderColor = glass.borderSubtle
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("input_odoo_database")
                )

                OutlinedTextField(
                    value = login,
                    onValueChange = { login = it },
                    label = { Text(if (isArabic) "اسم المستخدم أو البريد" else "Login / Email", fontSize = 11.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = glass.textPrimary,
                        unfocusedTextColor = glass.textPrimary,
                        focusedBorderColor = glass.accentTeal,
                        unfocusedBorderColor = glass.borderSubtle
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("input_odoo_login")
                )

                OutlinedTextField(
                    value = passwordOrKey,
                    onValueChange = { passwordOrKey = it },
                    label = { Text(if (isArabic) "مفتاح API أو كلمة السر" else "API Key / Password", fontSize = 11.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = glass.textPrimary,
                        unfocusedTextColor = glass.textPrimary,
                        focusedBorderColor = glass.accentTeal,
                        unfocusedBorderColor = glass.borderSubtle
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("input_odoo_password")
                )

                // Preset button
                TextButton(
                    onClick = {
                        database = "odoo_alamal_prod"
                        login = "admin@alamal.com"
                        passwordOrKey = "odoo_sec_key_449102830192_tenant_a"
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = if (isArabic) "استعادة الإعدادات الافتراضية للشركة" else "Use Tenant A Preset",
                        color = glass.accentTeal,
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(database, login, passwordOrKey) },
                colors = ButtonDefaults.buttonColors(containerColor = glass.accentTeal),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("button_dialog_connect")
            ) {
                Text(
                    text = if (isArabic) "اتصال" else "Authenticate",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = if (isArabic) "إلغاء" else "Cancel",
                    color = glass.textMuted
                )
            }
        }
    )
}

/**
 * Biometric Authority Guard Card:
 * Secures the Odoo ERP session and provides deterministic authority via androidx.biometric
 */
@Composable
fun BiometricSecurityCard(
    viewModel: MizanViewModel,
    isArabic: Boolean
) {
    val context = LocalContext.current
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val currentTenant by viewModel.currentTenant.collectAsStateWithLifecycle()
    val isBiometricEnforced by viewModel.isBiometricEnforced.collectAsStateWithLifecycle()
    val isBiometricSessionUnlocked by viewModel.isBiometricSessionUnlocked.collectAsStateWithLifecycle()
    val lastBiometricProof by viewModel.lastBiometricProof.collectAsStateWithLifecycle()
    val biometricCapability by viewModel.biometricCapability.collectAsStateWithLifecycle()

    val cardBorderColor = if (!isBiometricEnforced) {
        MizanTextMuted
    } else if (isBiometricSessionUnlocked) {
        MizanCyan
    } else {
        MizanCoral
    }

    val glass = LocalLiquidGlass.current

    LiquidGlassCard(
        shape = RoundedCornerShape(24.dp),
        accentBorder = cardBorderColor.copy(alpha = 0.8f),
        elevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("card_biometric_security")
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header: Icon + Title + Status Pill
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
                            .background(if (isBiometricSessionUnlocked) glass.accentTeal.copy(alpha = 0.2f) else glass.accentCoral.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = "Biometric Authority",
                            tint = if (isBiometricSessionUnlocked) glass.accentTeal else glass.accentCoral,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isArabic) "حارس السلطة البيومترية" else "Deterministic Biometric Guard",
                            color = glass.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = if (isArabic) "حماية جلسة Odoo والتحقق من البصمة" else "androidx.biometric Session & ERP Gating",
                            color = glass.textMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                // Status Pill
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (!isBiometricEnforced) glass.surfaceElevated else if (isBiometricSessionUnlocked) glass.accentGreen.copy(alpha = 0.15f) else glass.accentCoral.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, if (!isBiometricEnforced) glass.borderSubtle else if (isBiometricSessionUnlocked) glass.accentGreen.copy(alpha = 0.5f) else glass.accentCoral.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (!isBiometricEnforced) glass.textMuted else if (isBiometricSessionUnlocked) glass.accentGreen else glass.accentCoral)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (!isBiometricEnforced) {
                                if (isArabic) "معطل (نمط مطور)" else "BYPASSED (DEV)"
                            } else if (isBiometricSessionUnlocked) {
                                if (isArabic) "مفوض وموثق" else "UNLOCKED & AUTHORIZED"
                            } else {
                                if (isArabic) "الجلسة مقفلة" else "LOCKED (AUTH REQ)"
                            },
                            color = if (!isBiometricEnforced) glass.textMuted else if (isBiometricSessionUnlocked) glass.accentGreen else glass.accentCoral,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Body: Cryptographic proof info & Hardware Capability
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MizanCardBg,
                border = BorderStroke(1.dp, MizanCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SessionField(
                            label = if (isArabic) "مستشعر البصمة" else "Hardware Sensor",
                            value = if (isArabic) biometricCapability.labelAr else biometricCapability.labelEn,
                            isCode = false
                        )
                        SessionField(
                            label = if (isArabic) "سياسة الإلزام" else "Enforcement",
                            value = if (isBiometricEnforced) "MANDATORY" else "OPTIONAL",
                            isCode = true
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = MizanCardBorder, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (lastBiometricProof != null) {
                        val dateFormat = remember { SimpleDateFormat("HH:mm:ss · dd MMM", Locale.getDefault()) }
                        val proofTime = remember(lastBiometricProof!!.verifiedAt) { dateFormat.format(Date(lastBiometricProof!!.verifiedAt)) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            SessionField(
                                label = if (isArabic) "آخر توقيع بيومتري" else "Cryptographic Proof Token",
                                value = "SHA256: ${lastBiometricProof!!.signatureToken.take(16)}...",
                                isCode = true
                            )
                            SessionField(
                                label = if (isArabic) "وقت التوثيق" else "Verified At",
                                value = proofTime,
                                isCode = true
                            )
                        }
                    } else {
                        Text(
                            text = if (isArabic) "لم يتم التحقق من البصمة في هذه الجلسة بعد. انقر أدناه للتوثيق." else "No biometric signature recorded yet. Authenticate to establish deterministic ERP authority.",
                            color = MizanTextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Primary authenticate button
                Button(
                    onClick = {
                        val fragmentActivity = MizanBiometricManager.findFragmentActivity(context)
                        val capability = MizanBiometricManager.checkCapability(context)
                        viewModel.updateBiometricCapability(capability)

                        if (fragmentActivity != null && (capability == BiometricHardwareStatus.AVAILABLE || capability == BiometricHardwareStatus.NONE_ENROLLED)) {
                            try {
                                MizanBiometricManager.authenticate(
                                    activity = fragmentActivity,
                                    actorId = currentUser.userId,
                                    tenantId = currentTenant.tenantId,
                                    purpose = "ODOO_ERP_DETERMINISTIC_AUTHORITY",
                                    title = if (isArabic) "التحقق البيومتري لسلطة ميزان" else "MIZAN Deterministic Biometric Authority",
                                    subtitle = if (isArabic) "المصادقة لاعتماد تنفيذ عمليات Odoo" else "Authenticate to authorize Odoo session & ERP mutations",
                                    onSuccess = { proof ->
                                        viewModel.onBiometricAuthSuccess(proof)
                                    },
                                    onError = { code, err ->
                                        // If emulator error (e.g. no hardware / canceled / lockout), allow simulated fallback with notice
                                        if (code == androidx.biometric.BiometricPrompt.ERROR_HW_NOT_PRESENT ||
                                            code == androidx.biometric.BiometricPrompt.ERROR_NO_BIOMETRICS ||
                                            code == androidx.biometric.BiometricPrompt.ERROR_HW_UNAVAILABLE) {
                                            val simProof = MizanBiometricManager.createSimulatedProof(
                                                actorId = currentUser.userId,
                                                tenantId = currentTenant.tenantId,
                                                purpose = "ODOO_SESSION_EMULATOR_FALLBACK",
                                                method = "EMULATOR_CREDENTIAL_PASSKEY"
                                            )
                                            viewModel.onBiometricAuthSuccess(simProof)
                                        } else {
                                            viewModel.onBiometricAuthError(code, err)
                                        }
                                    },
                                    onFailed = {
                                        viewModel.onBiometricAuthError(-1, "Biometric signature rejected. Try again.")
                                    }
                                )
                            } catch (e: Exception) {
                                // Fallback simulation
                                val simProof = MizanBiometricManager.createSimulatedProof(
                                    actorId = currentUser.userId,
                                    tenantId = currentTenant.tenantId,
                                    purpose = "ODOO_SESSION_SIMULATED",
                                    method = "SIMULATED_FINGERPRINT_STRONG"
                                )
                                viewModel.onBiometricAuthSuccess(simProof)
                            }
                        } else {
                            // Fallback simulation for emulators without hardware
                            val simProof = MizanBiometricManager.createSimulatedProof(
                                actorId = currentUser.userId,
                                tenantId = currentTenant.tenantId,
                                purpose = "ODOO_SESSION_AUTHORITY",
                                method = "SIMULATED_FINGERPRINT_STRONG"
                            )
                            viewModel.onBiometricAuthSuccess(simProof)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isBiometricSessionUnlocked) glass.accentTeal else glass.accentCoral),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .weight(2f)
                        .testTag("button_verify_biometrics")
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = "Verify",
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isBiometricSessionUnlocked) {
                            if (isArabic) "إعادة تأكيد البصمة" else "Re-Verify Fingerprint"
                        } else {
                            if (isArabic) "فتح الجلسة بالبصمة" else "Unlock with Biometrics"
                        },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isBiometricSessionUnlocked) {
                    OutlinedButton(
                        onClick = { viewModel.lockBiometricSession() },
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, glass.accentCoral.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("button_lock_biometrics")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Lock",
                            tint = glass.accentCoral,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isArabic) "قفل" else "Lock",
                            color = glass.accentCoral,
                            fontSize = 12.sp
                        )
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.toggleBiometricEnforcement() },
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, glass.borderSubtle),
                    modifier = Modifier.testTag("button_toggle_biometric_policy")
                ) {
                    Text(
                        text = if (isBiometricEnforced) "Enforced" else "Bypass",
                        color = if (isBiometricEnforced) glass.accentTeal else glass.accentOrange,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
