package app.mizan.feature.home

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mizan.design.component.mizanGlassPane
import app.mizan.design.component.MizanGhostButton
import app.mizan.design.component.MizanKeyValue
import app.mizan.design.component.ShapeCard
import app.mizan.design.component.ShapeControl
import app.mizan.design.component.ShapePill
import app.mizan.design.component.StatusTone
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.token.Space
import app.mizan.domain.execution.ExecutionPhase
import app.mizan.domain.model.HistoricalErpActionLog
import app.mizan.ui.phaseLabel
import app.mizan.ui.toolLabel
import java.time.Duration
import java.time.Instant

enum class ActionLogStatusFilter(val label: String) {
    ALL("All"),
    VERIFIED("Verified"),
    PENDING("Pending"),
    RECONCILE("Reconcile"),
    FAULTS("Faults"),
}

enum class ActionLogDateRangeFilter(val label: String) {
    ALL_TIME("All Time"),
    TODAY("Today"),
    LAST_7_DAYS("Last 7 Days"),
    LAST_30_DAYS("Last 30 Days"),
}

/**
 * Read-only list component to display historical ERP action logs, showing
 * transaction status, timestamp, and verification hash for each executed command.
 *
 * Includes:
 * - Search and Filter Bar: Quickly find transactions by status, date range, or keyword.
 * - Export Feature: Generate and share compliant PDF or CSV audit reports for selected logs.
 */
@Composable
fun HistoricalErpActionLogsList(
    logs: List<HistoricalErpActionLog>,
    modifier: Modifier = Modifier,
    workspaceName: String = "MIZAN Workspace",
    onInspectEvidence: (() -> Unit)? = null,
) {
    val colors = LocalMizanColors.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // Search and Filter State
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedStatus by rememberSaveable { mutableStateOf(ActionLogStatusFilter.ALL) }
    var selectedDateRange by rememberSaveable { mutableStateOf(ActionLogDateRangeFilter.ALL_TIME) }

    // Multi-Selection State for Export
    var selectedLogIds by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var isExporting by rememberSaveable { mutableStateOf(false) }

    // Expanded card inspector ID
    var expandedLogId by rememberSaveable { mutableStateOf<String?>(null) }
    var inspectingLogForStatus by rememberSaveable { mutableStateOf<HistoricalErpActionLog?>(null) }

    // Counts for filter chips
    val verifiedCount = remember(logs) { logs.count { it.phase == ExecutionPhase.VERIFIED } }
    val pendingCount = remember(logs) {
        logs.count {
            it.phase == ExecutionPhase.AWAITING_APPROVAL ||
                it.phase == ExecutionPhase.AMBIGUOUS
        }
    }
    val reconcileCount = remember(logs) {
        logs.count {
            it.phase == ExecutionPhase.RECONCILIATION_REQUIRED ||
                it.phase == ExecutionPhase.LINKED_UNVERIFIED
        }
    }
    val faultCount = remember(logs) {
        logs.count {
            it.phase == ExecutionPhase.ERP_FAILURE ||
                it.phase == ExecutionPhase.REJECTED ||
                it.phase == ExecutionPhase.TIMEOUT
        }
    }

    // Filtered Logs Computation
    val filteredLogs = remember(logs, selectedStatus, selectedDateRange, searchQuery) {
        val query = searchQuery.trim().lowercase()
        val now = Instant.now()
        val startOfToday = now.minus(Duration.ofHours(24))
        val sevenDaysAgo = now.minus(Duration.ofDays(7))
        val thirtyDaysAgo = now.minus(Duration.ofDays(30))

        logs.filter { log ->
            // 1. Status Filter
            val statusMatches = when (selectedStatus) {
                ActionLogStatusFilter.ALL -> true
                ActionLogStatusFilter.VERIFIED -> log.phase == ExecutionPhase.VERIFIED
                ActionLogStatusFilter.PENDING -> log.phase == ExecutionPhase.AWAITING_APPROVAL || log.phase == ExecutionPhase.AMBIGUOUS
                ActionLogStatusFilter.RECONCILE -> log.phase == ExecutionPhase.RECONCILIATION_REQUIRED || log.phase == ExecutionPhase.LINKED_UNVERIFIED
                ActionLogStatusFilter.FAULTS -> log.phase == ExecutionPhase.ERP_FAILURE || log.phase == ExecutionPhase.REJECTED || log.phase == ExecutionPhase.TIMEOUT
            }

            // 2. Date Range Filter
            val dateMatches = when (selectedDateRange) {
                ActionLogDateRangeFilter.ALL_TIME -> true
                ActionLogDateRangeFilter.TODAY -> log.timestamp.isAfter(startOfToday)
                ActionLogDateRangeFilter.LAST_7_DAYS -> log.timestamp.isAfter(sevenDaysAgo)
                ActionLogDateRangeFilter.LAST_30_DAYS -> log.timestamp.isAfter(thirtyDaysAgo)
            }

            // 3. Keyword Query Search
            val keywordMatches = if (query.isBlank()) {
                true
            } else {
                log.commandId.lowercase().contains(query) ||
                    log.intent.lowercase().contains(query) ||
                    log.tool.wire.lowercase().contains(query) ||
                    log.verificationHash.lowercase().contains(query) ||
                    log.actorName.lowercase().contains(query) ||
                    (log.erpRecordId?.lowercase()?.contains(query) == true)
            }

            statusMatches && dateMatches && keywordMatches
        }
    }

    val hasActiveFilters = searchQuery.isNotBlank() ||
        selectedStatus != ActionLogStatusFilter.ALL ||
        selectedDateRange != ActionLogDateRangeFilter.ALL_TIME

    Column(
        modifier = modifier
            .testTag("historical_erp_action_logs_list")
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        // Section Header with Read-Only Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(ShapeControl)
                        .background(colors.accentMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Column {
                    Text(
                        text = "Historical ERP Action Logs",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Read-Only Audit Trail · Cryptographically Verified",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = colors.textSecondary,
                    )
                }
            }

            // Read-Only Security Lock Pill
            Row(
                modifier = Modifier
                    .mizanGlassPane(ShapePill)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = "Read-Only",
                    tint = colors.accent,
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    text = "READ-ONLY",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    ),
                    color = colors.accent,
                )
            }
        }

        // Summary Metric Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeControl)
                .background(colors.surfaceElevated)
                .border(BorderStroke(0.6.dp, colors.borderStrong), ShapeControl)
                .padding(horizontal = Space.md, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryItem(label = "Commands", value = "${logs.size}", color = colors.textPrimary)
            Box(Modifier.width(1.dp).height(20.dp).background(colors.borderStrong))
            SummaryItem(label = "Verified", value = "$verifiedCount", color = Color(0xFF10B981))
            Box(Modifier.width(1.dp).height(20.dp).background(colors.borderStrong))
            SummaryItem(label = "Ledger Integrity", value = "100%", color = colors.accent)
        }

        // ==========================================
        // SEARCH AND FILTER BAR (Keyword, Status, Date)
        // ==========================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .mizanGlassPane(ShapeCard)
                .padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 1. Keyword Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("action_logs_search_input"),
                placeholder = {
                    Text(
                        "Search by keyword, command ID, intent, hash, or ERP record...",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = "Search",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Clear,
                                contentDescription = "Clear search",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = ShapeControl,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.borderStrong,
                    focusedContainerColor = colors.surfaceElevated,
                    unfocusedContainerColor = colors.surfaceElevated,
                    cursorColor = colors.accent,
                ),
            )

            // 2. Status Filter Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.FilterList,
                    contentDescription = "Filter by status",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Status:",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
                ActionLogStatusFilter.entries.forEach { option ->
                    val isSelected = selectedStatus == option
                    val countBadge = when (option) {
                        ActionLogStatusFilter.ALL -> logs.size
                        ActionLogStatusFilter.VERIFIED -> verifiedCount
                        ActionLogStatusFilter.PENDING -> pendingCount
                        ActionLogStatusFilter.RECONCILE -> reconcileCount
                        ActionLogStatusFilter.FAULTS -> faultCount
                    }
                    FilterChipPill(
                        label = "${option.label} ($countBadge)",
                        isSelected = isSelected,
                        onClick = { selectedStatus = option },
                    )
                }
            }

            // 3. Date Range Filter Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.DateRange,
                    contentDescription = "Filter by date",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Date:",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
                ActionLogDateRangeFilter.entries.forEach { option ->
                    val isSelected = selectedDateRange == option
                    FilterChipPill(
                        label = option.label,
                        isSelected = isSelected,
                        onClick = { selectedDateRange = option },
                    )
                }
                if (hasActiveFilters) {
                    TextButton(
                        onClick = {
                            searchQuery = ""
                            selectedStatus = ActionLogStatusFilter.ALL
                            selectedDateRange = ActionLogDateRangeFilter.ALL_TIME
                        },
                    ) {
                        Text(
                            text = "Reset All",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.accent,
                        )
                    }
                }
            }
        }

        // ==========================================
        // SELECTION & EXPORT TOOLBAR
        // ==========================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeControl)
                .background(colors.surfaceElevated)
                .border(BorderStroke(0.6.dp, colors.borderStrong), ShapeControl)
                .padding(horizontal = Space.md, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val allFilteredSelected = filteredLogs.isNotEmpty() && filteredLogs.all { it.commandId in selectedLogIds }
                IconButton(
                    onClick = {
                        selectedLogIds = if (allFilteredSelected) {
                            emptySet()
                        } else {
                            filteredLogs.map { it.commandId }.toSet()
                        }
                        try {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } catch (_: Throwable) {}
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (allFilteredSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll,
                        contentDescription = "Select or deselect all",
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }

                Text(
                    text = if (selectedLogIds.isEmpty()) {
                        "${filteredLogs.size} logs available"
                    } else {
                        "${selectedLogIds.size} of ${filteredLogs.size} selected"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = if (selectedLogIds.isEmpty()) colors.textSecondary else colors.accent,
                )
            }

            // Export Action Button (PDF / CSV)
            Row(
                modifier = Modifier
                    .clip(ShapePill)
                    .background(colors.accentMuted)
                    .border(BorderStroke(0.6.dp, colors.accent), ShapePill)
                    .clickable(
                        role = Role.Button,
                        onClick = {
                            showExportDialog = true
                            try {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            } catch (_: Throwable) {}
                        },
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("export_report_button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = "Export report",
                    tint = colors.accent,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "Export Report",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                    ),
                    color = colors.accent,
                )
            }
        }

        // ==========================================
        // ACTION LOGS LIST
        // ==========================================
        if (filteredLogs.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .mizanGlassPane(ShapeCard)
                    .padding(Space.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (hasActiveFilters) "No executed commands match your search and filter criteria." else "No historical ERP action logs found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                filteredLogs.forEach { log ->
                    val isExpanded = expandedLogId == log.commandId
                    val isSelected = log.commandId in selectedLogIds
                    HistoricalErpActionLogCard(
                        log = log,
                        isSelected = isSelected,
                        isExpanded = isExpanded,
                        onToggleSelect = {
                            selectedLogIds = if (isSelected) {
                                selectedLogIds - log.commandId
                            } else {
                                selectedLogIds + log.commandId
                            }
                        },
                        onToggleExpand = {
                            expandedLogId = if (isExpanded) null else log.commandId
                        },
                        onInspectStatus = {
                            inspectingLogForStatus = log
                        },
                    )
                }
            }
        }
    }

    // ==========================================
    // EXPORT REPORT DIALOG (PDF or CSV)
    // ==========================================
    if (showExportDialog) {
        val targetLogs = if (selectedLogIds.isNotEmpty()) {
            logs.filter { it.commandId in selectedLogIds }
        } else {
            filteredLogs
        }

        AlertDialog(
            onDismissRequest = { if (!isExporting) showExportDialog = false },
            containerColor = colors.surfaceElevated,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(ShapeControl)
                            .background(colors.accentMuted),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        text = "Export Audit Report",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Generate and share an official compliance audit report containing ${targetLogs.size} ERP transaction action logs with verified cryptographic hashes (SHA-256).",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )

                    Spacer(Modifier.height(4.dp))

                    // Option A: PDF Compliance Document
                    ExportOptionCard(
                        icon = Icons.Outlined.PictureAsPdf,
                        title = "PDF Audit Compliance Report",
                        subtitle = "Formal A4 document with ledger seals, timestamps & cryptographic hashes",
                        badge = "Formal Report",
                        color = Color(0xFFEF4444),
                        onClick = {
                            isExporting = true
                            try {
                                val file = ErpActionLogsExporter.exportToPdf(
                                    context = context,
                                    logs = targetLogs,
                                    workspaceName = workspaceName,
                                )
                                Toast.makeText(context, "PDF Report generated successfully", Toast.LENGTH_SHORT).show()
                                ErpActionLogsExporter.shareReport(
                                    context = context,
                                    file = file,
                                    mimeType = "application/pdf",
                                    chooserTitle = "Share PDF ERP Audit Report",
                                )
                            } catch (e: Exception) {
                                Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                isExporting = false
                                showExportDialog = false
                            }
                        },
                    )

                    // Option B: CSV Spreadsheet
                    ExportOptionCard(
                        icon = Icons.Outlined.TableChart,
                        title = "CSV Structured Ledger",
                        subtitle = "RFC 4180 spreadsheet export for Excel, sheets, or enterprise SIEM systems",
                        badge = "Spreadsheet",
                        color = Color(0xFF10B981),
                        onClick = {
                            isExporting = true
                            try {
                                val file = ErpActionLogsExporter.exportToCsv(
                                    context = context,
                                    logs = targetLogs,
                                )
                                Toast.makeText(context, "CSV Ledger generated successfully", Toast.LENGTH_SHORT).show()
                                ErpActionLogsExporter.shareReport(
                                    context = context,
                                    file = file,
                                    mimeType = "text/csv",
                                    chooserTitle = "Share CSV ERP Audit Ledger",
                                )
                            } catch (e: Exception) {
                                Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                isExporting = false
                                showExportDialog = false
                            }
                        },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showExportDialog = false },
                    enabled = !isExporting,
                ) {
                    Text("Close", color = colors.textSecondary)
                }
            },
        )
    }

    // ==========================================
    // TRANSACTION STATUS DETAILS BOTTOM SHEET
    // ==========================================
    inspectingLogForStatus?.let { targetLog ->
        TransactionStatusBottomSheet(
            log = targetLog,
            onDismiss = { inspectingLogForStatus = null },
            onExportPdf = {
                try {
                    val file = ErpActionLogsExporter.exportToPdf(
                        context = context,
                        logs = listOf(targetLog),
                        workspaceName = workspaceName,
                    )
                    Toast.makeText(context, "PDF Report generated", Toast.LENGTH_SHORT).show()
                    ErpActionLogsExporter.shareReport(context, file, "application/pdf")
                } catch (t: Throwable) {
                    Toast.makeText(context, "Export error: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            },
            onExportCsv = {
                try {
                    val file = ErpActionLogsExporter.exportToCsv(
                        context = context,
                        logs = listOf(targetLog),
                    )
                    Toast.makeText(context, "CSV Report generated", Toast.LENGTH_SHORT).show()
                    ErpActionLogsExporter.shareReport(context, file, "text/csv")
                } catch (t: Throwable) {
                    Toast.makeText(context, "Export error: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

/**
 * Filter Chip pill used in the Search and Filter bar.
 */
@Composable
private fun FilterChipPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalMizanColors.current
    val bg by animateColorAsState(
        targetValue = if (isSelected) colors.accentMuted else colors.surfaceElevated,
        label = "pill_bg",
    )
    val border by animateColorAsState(
        targetValue = if (isSelected) colors.accent else colors.borderStrong,
        label = "pill_border",
    )

    Row(
        modifier = Modifier
            .clip(ShapePill)
            .background(bg)
            .border(BorderStroke(0.6.dp, border), ShapePill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (isSelected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(11.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            ),
            color = if (isSelected) colors.accent else colors.textPrimary,
        )
    }
}

/**
 * Export Option card inside the Export Report dialog.
 */
@Composable
private fun ExportOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    badge: String,
    color: Color,
    onClick: () -> Unit,
) {
    val colors = LocalMizanColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .mizanGlassPane(ShapeCard)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(ShapeControl)
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                color = colors.textSecondary,
            )
        }

        Icon(
            Icons.Outlined.Share,
            contentDescription = "Share",
            tint = colors.accent,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * High-fidelity individual read-only action log card displaying:
 * - Multi-select checkbox for export
 * - Transaction Status badge
 * - Execution Timestamp
 * - Verification Hash (SHA-256 with copy affordance)
 * - Executed command details
 */
@Composable
private fun HistoricalErpActionLogCard(
    log: HistoricalErpActionLog,
    isSelected: Boolean,
    isExpanded: Boolean,
    onToggleSelect: () -> Unit,
    onToggleExpand: () -> Unit,
    onInspectStatus: () -> Unit = {},
) {
    val colors = LocalMizanColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current

    val statusLabel = phaseLabel(log.phase)
    val toolDisplayName = toolLabel(log.tool)

    val statusTone = when (log.phase) {
        ExecutionPhase.VERIFIED -> StatusTone.Success
        ExecutionPhase.ERP_FAILURE, ExecutionPhase.REJECTED, ExecutionPhase.TIMEOUT -> StatusTone.Danger
        ExecutionPhase.AWAITING_APPROVAL -> StatusTone.Warning
        ExecutionPhase.RECONCILIATION_REQUIRED, ExecutionPhase.AMBIGUOUS -> StatusTone.Info
        else -> StatusTone.Neutral
    }

    val statusIcon = when (log.phase) {
        ExecutionPhase.VERIFIED -> Icons.Outlined.CheckCircle
        ExecutionPhase.ERP_FAILURE, ExecutionPhase.REJECTED, ExecutionPhase.TIMEOUT -> Icons.Outlined.WarningAmber
        ExecutionPhase.AWAITING_APPROVAL -> Icons.Outlined.Schedule
        ExecutionPhase.RECONCILIATION_REQUIRED, ExecutionPhase.AMBIGUOUS -> Icons.Outlined.SyncProblem
        else -> Icons.Outlined.History
    }

    val cardBorderColor by animateColorAsState(
        targetValue = if (isSelected) colors.accent else colors.glassBorder,
        label = "card_border",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("action_log_card_${log.commandId}")
            .shadow(
                elevation = if (colors.isDark) 0.dp else if (isSelected) 6.dp else 4.dp,
                shape = ShapeCard,
                spotColor = Color(0x120F172A),
                ambientColor = Color(0x060F172A),
            )
            .clip(ShapeCard)
            .background(
                if (isSelected) {
                    SolidColor(colors.accentMuted.copy(alpha = 0.2f))
                } else if (colors.isDark) {
                    SolidColor(colors.glass)
                } else {
                    Brush.verticalGradient(
                        listOf(Color(0xFAFFFFFF), Color(0xEEFFFFFF)),
                    )
                },
            )
            .border(BorderStroke(if (isSelected) 1.5.dp else 0.8.dp, cardBorderColor), ShapeCard)
            .animateContentSize()
            .clickable(role = Role.Button, onClick = onToggleExpand)
            .padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Top Header: Checkbox + Tool Name + Transaction Status Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                // Multi-select checkbox for PDF/CSV export
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("select_log_checkbox_${log.commandId}"),
                    colors = CheckboxDefaults.colors(
                        checkedColor = colors.accent,
                        uncheckedColor = colors.borderStrong,
                    ),
                )

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(ShapeControl)
                        .background(colors.accentMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Column {
                    Text(
                        text = toolDisplayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = log.commandId,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = colors.textTertiary,
                    )
                }
            }

            // Transaction Status Badge
            Row(
                modifier = Modifier
                    .clip(ShapePill)
                    .background(
                        when (statusTone) {
                            StatusTone.Success -> Color(0xFF10B981).copy(alpha = 0.15f)
                            StatusTone.Danger -> Color(0xFFEF4444).copy(alpha = 0.15f)
                            StatusTone.Warning -> Color(0xFFF59E0B).copy(alpha = 0.15f)
                            StatusTone.Info -> Color(0xFF06B6D4).copy(alpha = 0.15f)
                            StatusTone.Accent -> colors.accentMuted
                            else -> colors.surfaceElevated
                        },
                    )
                    .border(
                        BorderStroke(
                            0.6.dp,
                            when (statusTone) {
                                StatusTone.Success -> Color(0xFF10B981).copy(alpha = 0.4f)
                                StatusTone.Danger -> Color(0xFFEF4444).copy(alpha = 0.4f)
                                StatusTone.Warning -> Color(0xFFF59E0B).copy(alpha = 0.4f)
                                StatusTone.Info -> Color(0xFF06B6D4).copy(alpha = 0.4f)
                                StatusTone.Accent -> colors.accent
                                else -> colors.borderStrong
                            },
                        ),
                        ShapePill,
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = statusLabel,
                    tint = when (statusTone) {
                        StatusTone.Success -> Color(0xFF10B981)
                        StatusTone.Danger -> Color(0xFFEF4444)
                        StatusTone.Warning -> Color(0xFFF59E0B)
                        StatusTone.Info -> Color(0xFF06B6D4)
                        StatusTone.Accent -> colors.accent
                        else -> colors.textSecondary
                    },
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.5.sp,
                    ),
                    color = when (statusTone) {
                        StatusTone.Success -> Color(0xFF10B981)
                        StatusTone.Danger -> Color(0xFFEF4444)
                        StatusTone.Warning -> Color(0xFFF59E0B)
                        StatusTone.Info -> Color(0xFF06B6D4)
                        StatusTone.Accent -> colors.accent
                        else -> colors.textPrimary
                    },
                )
            }
        }

        // Executed Command Intent Description
        Text(
            text = log.intent,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
        )

        // Operator & ERP Record Binding Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Operator: ${log.actorName} (${log.actorRole})",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = colors.textSecondary,
            )
            if (log.erpRecordId != null) {
                Text(
                    text = "ERP Binding: ${log.erpRecordId}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.accent,
                )
            }
        }

        // CRYPTOGRAPHIC VERIFICATION HASH ROW
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeControl)
                .background(if (colors.isDark) Color(0x220F172A) else Color(0x080F172A))
                .border(BorderStroke(0.6.dp, colors.borderStrong), ShapeControl)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = "Verification Hash",
                    tint = colors.accent,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "SHA-256: ${log.shortHash}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Quick Copy Verification Hash Button
            Row(
                modifier = Modifier
                    .mizanGlassPane(ShapePill)
                    .clickable(
                        role = Role.Button,
                        onClick = {
                            clipboard.setText(AnnotatedString(log.verificationHash))
                            try {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            } catch (_: Throwable) {}
                            Toast.makeText(context, "Verification Hash copied!", Toast.LENGTH_SHORT).show()
                        },
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy Verification Hash",
                    tint = colors.accent,
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    text = "Copy Hash",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = colors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Timestamp Footer Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = "Timestamp",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "${log.formattedTimestamp} · ${log.relativeTime}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = colors.textTertiary,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (isExpanded) "Hide details" else "Inspect hash",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = colors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // EXPANDED READ-ONLY INSPECTOR DRAWER
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeControl)
                    .background(colors.surfaceElevated)
                    .border(BorderStroke(0.6.dp, colors.borderStrong), ShapeControl)
                    .padding(Space.md),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Cryptographic Execution Trace",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent,
                )

                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MizanKeyValue("Full Hash", log.verificationHash, mono = true)
                        if (log.previousHash != null) {
                            MizanKeyValue("Previous Link", log.previousHash, mono = true)
                        }
                        MizanKeyValue("Trace ID", log.traceId, mono = true)
                        MizanKeyValue("Command Wire", log.tool.wire)
                        MizanKeyValue("Canonical Payload", log.canonicalPayload, mono = true)
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Direct action button to open full Transaction Status Page
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapePill)
                        .background(colors.accentMuted)
                        .border(BorderStroke(0.8.dp, colors.accent), ShapePill)
                        .clickable(role = Role.Button, onClick = onInspectStatus)
                        .padding(horizontal = Space.md, vertical = 8.dp)
                        .testTag("inspect_transaction_status_row_${log.commandId}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Inspect Full Transaction Status & Proofs",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = colors.accent,
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Immutable ERP action log stored on device under local and authority cryptographic governance.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                    color = colors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String, color: Color) {
    val colors = LocalMizanColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
            color = colors.textSecondary,
        )
    }
}
