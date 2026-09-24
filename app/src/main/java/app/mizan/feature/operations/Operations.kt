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
import androidx.compose.ui.platform.LocalClipboardManager
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
    val filterOptions = listOf("All ( ${records.size} )", "Verified", "Pending", "Faults")

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
        Row(Modifier.fillMaxSize().padding(Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            Column(
                Modifier
                    .weight(0.45f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                MizanSectionHeader(stringResource(R.string.ops_title))
                GlassSegmentedControl(
                    options = filterOptions,
                    selectedIndex = filterIndex,
                    onSelect = { filterIndex = it },
                )
                filteredRecords.forEach { record ->
                    AuditTrailReceiptCard(
                        toolName = record.tool.wire,
                        intent = record.intent.ifBlank { record.tool.wire },
                        phaseLabel = phaseLabel(record.phase),
                        statusTone = tone(record.phase),
                        receiptId = record.id.value,
                        erpRecordId = record.erpRecordId,
                        policyRule = null,
                        traceId = record.traceId.value,
                        timestampFormatted = "Audited Execution",
                        isBiometricVerified = true,
                        isSimulation = graph.demoMode,
                        onClick = { selectedId = record.id.value },
                    )
                }
            }
            Column(
                Modifier
                    .weight(0.55f)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (current == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(ShapeCard)
                            .background(colors.glass)
                            .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                            .padding(Space.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Select an operation from the list to view its cryptographic attestation and full ERP payload details.",
                            color = colors.textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    OperationDetailCard(current)
                }
            }
        }
    } else if (current == null) {
        // Mobile vertical list
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            // Screen Header Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeCard)
                    .background(colors.glass)
                    .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                    .padding(Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(ShapeControl)
                        .background(colors.accentMuted)
                        .border(BorderStroke(0.6.dp, colors.accent.copy(alpha = 0.3f)), ShapeControl),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ops_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "${records.size} tracked operations · ${records.count { it.phase == ExecutionPhase.VERIFIED }} verified",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }

            // Segmented interactive filter
            GlassSegmentedControl(
                options = filterOptions,
                selectedIndex = filterIndex,
                onSelect = { filterIndex = it },
            )

            // Filtered Operations List
            if (filteredRecords.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapeCard)
                        .background(colors.glass)
                        .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                        .padding(Space.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No operations match this filter.",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    filteredRecords.forEach { record ->
                        AuditTrailReceiptCard(
                            toolName = record.tool.wire,
                            intent = record.intent.ifBlank { record.tool.wire },
                            phaseLabel = phaseLabel(record.phase),
                            statusTone = tone(record.phase),
                            receiptId = record.id.value,
                            erpRecordId = record.erpRecordId,
                            policyRule = null,
                            traceId = record.traceId.value,
                            timestampFormatted = "Audited Execution",
                            isBiometricVerified = true,
                            isSimulation = graph.demoMode,
                            onClick = { selectedId = record.id.value },
                        )
                    }
                }
            }
            Spacer(Modifier.height(Space.xl))
        }
    } else {
        // Mobile single item deep inspector
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            MizanGhostButton(stringResource(R.string.cd_back), onClick = { selectedId = null })
            OperationDetailCard(current)
            Spacer(Modifier.height(Space.xl))
        }
    }
}

@Composable
private fun OperationDetailCard(record: ExecutionRecord) {
    val colors = LocalMizanColors.current
    val clipboard = LocalClipboardManager.current
    var copiedTrace by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (colors.isDark) 0.dp else 4.dp,
                shape = ShapeCard,
                spotColor = Color(0x140F172A),
                ambientColor = Color(0x050F172A),
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
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = record.intent.ifBlank { record.tool.wire },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Tool Protocol: ${record.tool.wire}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            MizanStatusBadge(phaseLabel(record.phase), tone(record.phase))
        }

        // Biometric Attestation Seal
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeControl)
                .background(colors.accentMuted)
                .border(BorderStroke(0.6.dp, colors.accent.copy(alpha = 0.3f)), ShapeControl)
                .padding(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Fingerprint,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Space.sm))
            Column {
                Text(
                    text = "Hardware Attestation Verified",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent,
                )
                Text(
                    text = "Signed via Android Keystore cryptographic key pair",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = colors.textSecondary,
                )
            }
        }

        // Details key-values
        MizanKeyValue(stringResource(R.string.evidence_meta), record.id.value, mono = true)

        // Trace ID with copy affordance
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Trace ID", style = MaterialTheme.typography.labelMedium, color = colors.textTertiary)
                Text(record.traceId.value, style = MaterialTheme.typography.bodySmall, fontFamily = app.mizan.design.theme.MizanMono, color = colors.textPrimary)
            }
            Row(
                modifier = Modifier
                    .clip(ShapePill)
                    .background(colors.surfaceElevated)
                    .border(BorderStroke(0.6.dp, colors.border), ShapePill)
                    .clickable {
                        clipboard.setText(AnnotatedString(record.traceId.value))
                        copiedTrace = true
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (copiedTrace) "COPIED" else "COPY",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = colors.accent,
                )
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = colors.accent, modifier = Modifier.size(12.dp))
            }
        }

        record.erpRecordId?.let {
            MizanKeyValue(stringResource(R.string.evidence_fact), it, mono = true)
        }
        record.errorCode?.let {
            MizanKeyValue(stringResource(R.string.outcome_refused), it)
        }
    }
}

private fun tone(phase: ExecutionPhase): StatusTone = when (phase) {
    ExecutionPhase.VERIFIED -> StatusTone.Success
    ExecutionPhase.AMBIGUOUS,
    ExecutionPhase.RECONCILIATION_REQUIRED,
    ExecutionPhase.LINKED_UNVERIFIED,
    ExecutionPhase.CLOSED_UNVERIFIED,
    ExecutionPhase.AWAITING_APPROVAL,
    -> StatusTone.Warning
    ExecutionPhase.ERP_FAILURE, ExecutionPhase.REJECTED, ExecutionPhase.TIMEOUT -> StatusTone.Danger
    else -> StatusTone.Info
}
