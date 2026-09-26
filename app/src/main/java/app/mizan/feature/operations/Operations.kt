package app.mizan.feature.operations

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mizan.R
import app.mizan.design.component.AuditTrailReceiptCard
import app.mizan.design.motion.mizanReveal
import app.mizan.design.component.GlassSegmentedControl
import app.mizan.design.component.MizanEmptyState
import app.mizan.design.component.MizanGhostButton
import app.mizan.design.component.MizanKeyValue
import app.mizan.design.component.MizanSectionHeader
import app.mizan.design.component.MizanStatusBadge
import app.mizan.design.component.MizanSurface
import app.mizan.design.component.ShapeCard
import app.mizan.design.component.ShapeControl
import app.mizan.design.component.ShapePill
import app.mizan.design.component.StatusTone
import app.mizan.design.component.mizanBounceClick
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.token.Space
import app.mizan.domain.execution.ExecutionPhase
import app.mizan.domain.model.ExecutionRecord
import app.mizan.feature.home.simpleFactory
import app.mizan.graph.AppGraph
import app.mizan.ui.phaseLabel
import app.mizan.ui.toolLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

class OperationsViewModel(graph: AppGraph) : ViewModel() {
    val records = graph.session.session.flatMapLatest { session ->
        if (session == null) flowOf(emptyList()) else graph.executions.observe(session.tenant.id, 80)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun OperationsRoute(graph: AppGraph, expanded: Boolean) {
    val vm: OperationsViewModel = viewModel(factory = simpleFactory { OperationsViewModel(graph) })
    val records by vm.records.collectAsStateWithLifecycle()
    val colors = LocalMizanColors.current

    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }

    val verifiedCount = remember(records) { records.count { it.phase == ExecutionPhase.VERIFIED } }
    val pendingCount = remember(records) {
        records.count {
            it.phase == ExecutionPhase.AWAITING_APPROVAL ||
                it.phase == ExecutionPhase.AMBIGUOUS ||
                it.phase == ExecutionPhase.RECONCILIATION_REQUIRED ||
                it.phase == ExecutionPhase.LINKED_UNVERIFIED
        }
    }
    val faultCount = remember(records) {
        records.count {
            it.phase == ExecutionPhase.ERP_FAILURE ||
                it.phase == ExecutionPhase.REJECTED ||
                it.phase == ExecutionPhase.TIMEOUT
        }
    }

    val filterOptions = listOf(
        "All (${records.size})",
        "Verified ($verifiedCount)",
        "Pending ($pendingCount)",
        "Faults ($faultCount)",
    )

    val filteredRecords = remember(records, filterIndex) {
        when (filterIndex) {
            1 -> records.filter { it.phase == ExecutionPhase.VERIFIED }
            2 -> records.filter {
                it.phase == ExecutionPhase.AWAITING_APPROVAL ||
                    it.phase == ExecutionPhase.AMBIGUOUS ||
                    it.phase == ExecutionPhase.RECONCILIATION_REQUIRED ||
                    it.phase == ExecutionPhase.LINKED_UNVERIFIED
            }
            3 -> records.filter {
                it.phase == ExecutionPhase.ERP_FAILURE ||
                    it.phase == ExecutionPhase.REJECTED ||
                    it.phase == ExecutionPhase.TIMEOUT
            }
            else -> records
        }
    }

    val current = records.find { it.id.value == selectedId }

    if (records.isEmpty()) {
        MizanEmptyState(
            stringResource(R.string.ops_empty_title),
            stringResource(R.string.ops_empty_body),
        )
        return
    }

    if (expanded) {
        // Split-pane layout for tablet / wide screens
        Row(
            modifier = Modifier.fillMaxSize().padding(Space.lg),
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Column(
                modifier = Modifier
                    .weight(0.52f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                OperationsHeroStats(records.size, verifiedCount, pendingCount, faultCount)
                GlassSegmentedControl(
                    options = filterOptions,
                    selectedIndex = filterIndex,
                    onSelect = { filterIndex = it },
                )
                OperationsReceiptsList(filteredRecords, selectedId) { selectedId = it }
            }

            Column(
                modifier = Modifier
                    .weight(0.48f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                if (current != null) {
                    OperationDetailCard(current) { selectedId = null }
                } else {
                    MizanEmptyState(
                        title = "Select an Execution Receipt",
                        body = "Choose any operation from the execution list to inspect its governance lease, cryptographic verification proof, and audit metadata.",
                    )
                }
            }
        }
    } else {
        // Mobile Handheld layout
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            OperationsHeroStats(records.size, verifiedCount, pendingCount, faultCount)

            // Filter Tabs
            GlassSegmentedControl(
                options = filterOptions,
                selectedIndex = filterIndex,
                onSelect = { filterIndex = it },
            )

            // Operations List
            MizanSectionHeader(stringResource(R.string.ops_title))
            OperationsReceiptsList(filteredRecords, selectedId) { selectedId = it }

            // Inspector Detail Modal if tapped
            current?.let { record ->
                Spacer(Modifier.height(Space.sm))
                OperationDetailCard(record) { selectedId = null }
            }

            Spacer(Modifier.height(Space.xl))
        }
    }
}

@Composable
private fun OperationsHeroStats(
    total: Int,
    verified: Int,
    pending: Int,
    faults: Int,
) {
    val colors = LocalMizanColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (colors.isDark) 0.dp else 4.dp,
                shape = ShapeCard,
                spotColor = Color(0x140F172A),
                ambientColor = Color(0x080F172A),
            )
            .clip(ShapeCard)
            .background(
                if (colors.isDark) SolidColor(colors.glass) else Brush.verticalGradient(
                    listOf(Color(0xFAFFFFFF), Color(0xEDFFFFFF)),
                ),
            )
            .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
            .padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(ShapeControl)
                        .background(colors.accentMuted)
                        .border(BorderStroke(0.6.dp, colors.accent.copy(alpha = 0.3f)), ShapeControl),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FactCheck,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column {
                    Text(
                        text = "ERP Execution Authority",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Real-time lease and idempotent execution tracking",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
            MizanStatusBadge(
                label = if (faults > 0) "$faults Faults" else "Nominal",
                tone = if (faults > 0) StatusTone.Danger else StatusTone.Success,
            )
        }

        // Stats summary row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            OperationsStatChip(
                label = "Total Run",
                value = "$total",
                dotColor = colors.accent,
                modifier = Modifier.weight(1f),
            )
            OperationsStatChip(
                label = "Verified",
                value = "$verified",
                dotColor = Color(0xFF10B981),
                modifier = Modifier.weight(1f),
            )
            OperationsStatChip(
                label = "Pending",
                value = "$pending",
                dotColor = colors.warning,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OperationsStatChip(
    label: String,
    value: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMizanColors.current
    Column(
        modifier = modifier
            .clip(ShapeControl)
            .background(if (colors.isDark) colors.surfaceElevated else Color(0x0A000000))
            .border(BorderStroke(0.6.dp, colors.borderStrong), ShapeControl)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.textPrimary)
    }
}

@Composable
private fun OperationsReceiptsList(
    records: List<ExecutionRecord>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    if (records.isEmpty()) {
        MizanEmptyState(
            title = "No Matching Operations",
            body = "No execution records match the selected filter category.",
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            records.forEachIndexed { index, record ->
                val (tone, statusLabel) = when (record.phase) {
                    ExecutionPhase.VERIFIED -> StatusTone.Success to "Verified Safe"
                    ExecutionPhase.AWAITING_APPROVAL -> StatusTone.Warning to "Awaiting Sign-off"
                    ExecutionPhase.AMBIGUOUS -> StatusTone.Warning to "Ambiguous Match"
                    ExecutionPhase.RECONCILIATION_REQUIRED -> StatusTone.Warning to "Reconcile Needed"
                    ExecutionPhase.ERP_FAILURE, ExecutionPhase.REJECTED, ExecutionPhase.TIMEOUT -> StatusTone.Danger to "Execution Fault"
                    else -> StatusTone.Accent to phaseLabel(record.phase)
                }

                AuditTrailReceiptCard(
                    modifier = Modifier.mizanReveal(index),
                    toolName = toolLabel(record.tool),
                    intent = record.intent.ifBlank { record.tool.wire },
                    phaseLabel = statusLabel,
                    statusTone = tone,
                    receiptId = record.id.value,
                    erpRecordId = record.erpRecordId,
                    policyRule = record.policyRuleId,
                    traceId = record.id.value,
                    timestampFormatted = "Phase: ${phaseLabel(record.phase)}",
                    isBiometricVerified = record.phase == ExecutionPhase.VERIFIED,
                    isSimulation = false,
                    onClick = { onSelect(record.id.value) },
                )
            }
        }
    }
}

@Composable
private fun OperationDetailCard(record: ExecutionRecord, onClose: () -> Unit) {
    val colors = LocalMizanColors.current
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (colors.isDark) 0.dp else 4.dp,
                shape = ShapeCard,
                spotColor = Color(0x140F172A),
                ambientColor = Color(0x080F172A),
            )
            .clip(ShapeCard)
            .background(
                if (colors.isDark) SolidColor(colors.glass) else Brush.verticalGradient(
                    listOf(Color(0xFAFFFFFF), Color(0xEDFFFFFF)),
                ),
            )
            .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
            .padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(ShapeControl)
                        .background(colors.accentMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.ReceiptLong, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(
                        text = "Operation Inspector",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = toolLabel(record.tool),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
            MizanGhostButton("Close", onClick = onClose)
        }

        MizanKeyValue("Execution ID", record.id.value, mono = true)
        MizanKeyValue("Execution Phase", phaseLabel(record.phase))
        MizanKeyValue("Tool Invocation", record.tool.wire, mono = true)
        record.erpRecordId?.let {
            MizanKeyValue("ERP Bound Identifier", it, mono = true)
        }
        MizanKeyValue("Authority Proposal ID", record.proposalId.value, mono = true)
        record.leaseExpiresAt?.let {
            MizanKeyValue("Authority Lease Expiry", it.toString(), mono = true)
        }
        record.errorCode?.let {
            MizanKeyValue("Fault Code", it, mono = true)
        }

        // Trace ID copy button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapePill)
                .background(if (colors.isDark) colors.surfaceElevated else Color(0x0A000000))
                .border(BorderStroke(0.6.dp, colors.borderStrong), ShapePill)
                .clickable {
                    try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Throwable) {}
                    clipboard.setText(AnnotatedString(record.id.value))
                    copied = true
                }
                .padding(horizontal = Space.md, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Fingerprint, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
                Text(
                    text = if (copied) "Execution ID Copied!" else "Copy Trace ID",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copied) colors.accent else colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
            Icon(
                imageVector = if (copied) Icons.Outlined.CheckCircle else Icons.Outlined.ContentCopy,
                contentDescription = null,
                tint = if (copied) colors.accent else colors.textTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
