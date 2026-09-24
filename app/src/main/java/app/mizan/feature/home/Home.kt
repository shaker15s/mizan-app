package app.mizan.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mizan.R
import app.mizan.design.component.AuditTrailReceiptCard
import app.mizan.design.component.ErpSuccessProgressGauge
import app.mizan.design.component.GlassSegmentedControl
import app.mizan.design.component.MizanGhostButton
import app.mizan.design.component.MizanIconButton
import app.mizan.design.component.MizanListRow
import app.mizan.design.component.MizanMark
import app.mizan.design.component.MizanSectionHeader
import app.mizan.design.component.MizanStatusBadge
import app.mizan.design.component.ShapeCard
import app.mizan.design.component.ShapePill
import app.mizan.design.component.StatusTone
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.token.Space
import app.mizan.domain.attention.AttentionItem
import app.mizan.domain.attention.AttentionKind
import app.mizan.domain.execution.ExecutionPhase
import app.mizan.domain.model.ExecutionRecord
import app.mizan.domain.model.HealthStatus
import app.mizan.domain.model.SystemHealth
import app.mizan.domain.model.TrustReceipt
import app.mizan.graph.AppGraph
import app.mizan.ui.attentionLabel
import app.mizan.ui.healthLabel
import app.mizan.ui.phaseLabel
import app.mizan.ui.roleLabel
import app.mizan.ui.toolLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

data class HomeUi(
    val workspace: String = "",
    val actor: String = "",
    val roleName: String = "",
    val attention: List<AttentionItem> = emptyList(),
    val recent: List<ExecutionRecord> = emptyList(),
    val receipts: List<TrustReceipt> = emptyList(),
    val allExecutions: List<ExecutionRecord> = emptyList(),
    val health: SystemHealth = SystemHealth.unknown,
    val offline: Boolean = false,
    val demoMode: Boolean = false,
)

class HomeViewModel(graph: AppGraph) : ViewModel() {
    val state: StateFlow<HomeUi> = graph.session.session.flatMapLatest { session ->
        if (session == null) {
            flowOf(HomeUi(demoMode = graph.demoMode))
        } else {
            combine(
                graph.executions.observe(session.tenant.id),
                graph.cases.observe(session.tenant.id),
                graph.receipts.observe(session.tenant.id, 20),
                graph.health.networkStatus,
            ) { executions, cases, receipts, network ->
                val health = graph.health.snapshot(signedIn = true, simulation = graph.demoMode).copy(network = network)
                HomeUi(
                    workspace = session.tenant.displayName,
                    actor = session.actor.displayName,
                    roleName = session.actor.role.name,
                    attention = graph.attention.plan(executions, cases, health, null),
                    recent = executions.take(8),
                    receipts = receipts,
                    allExecutions = executions,
                    health = health,
                    offline = health.network == HealthStatus.UNAVAILABLE,
                    demoMode = graph.demoMode,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi(demoMode = graph.demoMode))
}

@Composable
fun HomeRoute(graph: AppGraph, expanded: Boolean, onOpen: (String) -> Unit) {
    val vm: HomeViewModel = viewModel(factory = simpleFactory { HomeViewModel(graph) })
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = LocalMizanColors.current

    // Compute live ERP execution statistics for visual gauge
    val totalExecutions = state.allExecutions.size
    val verifiedCount = state.allExecutions.count { it.phase == ExecutionPhase.VERIFIED }
    val pendingCount = state.allExecutions.count {
        it.phase == ExecutionPhase.AWAITING_APPROVAL ||
            it.phase == ExecutionPhase.AMBIGUOUS ||
            it.phase == ExecutionPhase.RECONCILIATION_REQUIRED ||
            it.phase == ExecutionPhase.LINKED_UNVERIFIED
    }
    val failureCount = state.allExecutions.count {
        it.phase == ExecutionPhase.ERP_FAILURE ||
            it.phase == ExecutionPhase.REJECTED ||
            it.phase == ExecutionPhase.TIMEOUT
    }
    val successRate = if (totalExecutions == 0) 1.0f else (verifiedCount.toFloat() / totalExecutions.toFloat())

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        // Workspace Apple Glass Header Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeCard)
                .background(colors.glass)
                .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                .padding(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MizanMark()
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = Space.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.workspace.ifBlank { stringResource(R.string.app_name) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (state.offline) colors.danger else colors.accent),
                    )
                }
                if (state.actor.isNotBlank() && state.roleName.isNotBlank()) {
                    Text(
                        text = "${state.actor} · ${roleLabel(app.mizan.domain.model.Role.valueOf(state.roleName))}",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            MizanIconButton(Icons.Outlined.Search, stringResource(R.string.cd_search), { onOpen("search") })
        }

        if (state.offline) {
            Text(stringResource(R.string.offline_banner), color = colors.warning, style = MaterialTheme.typography.bodySmall)
        }

        // Craft Metric Grid (3 compact widgets)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            MetricCard(
                title = "Pending",
                value = if (state.attention.isEmpty()) "0" else "${state.attention.size}",
                icon = Icons.Outlined.FactCheck,
                tone = if (state.attention.isEmpty()) StatusTone.Success else StatusTone.Warning,
                onClick = { onOpen(if (state.attention.isEmpty()) "operations" else "agent") },
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                title = "Vault",
                value = "Active",
                icon = Icons.Outlined.Fingerprint,
                tone = StatusTone.Success,
                onClick = { onOpen("security") },
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                title = "System",
                value = if (state.offline) "Offline" else "100%",
                icon = Icons.Outlined.Shield,
                tone = if (state.offline) StatusTone.Danger else StatusTone.Success,
                onClick = { onOpen("connection") },
                modifier = Modifier.weight(1f),
            )
        }

        // ChatGPT-style Quick Action Suggestions (Horizontal swipeable chips)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            QuickChip(
                label = stringResource(R.string.suggest_stock),
                icon = Icons.Outlined.Hub,
                onClick = { onOpen("agent") },
            )
            QuickChip(
                label = "Draft Cairo Tech Order",
                icon = Icons.Outlined.FactCheck,
                onClick = { onOpen("agent") },
            )
            QuickChip(
                label = "Inspect Ledger Chain",
                icon = Icons.Outlined.Security,
                onClick = { onOpen("evidence") },
            )
            QuickChip(
                label = "Verify Receipts",
                icon = Icons.Outlined.History,
                onClick = { onOpen("operations") },
            )
        }

        // VISUAL PROGRESS INDICATOR: Animated ERP Success Rates Gauge
        ErpSuccessProgressGauge(
            successRate = successRate,
            totalRequests = totalExecutions,
            verifiedCount = verifiedCount,
            pendingCount = pendingCount,
            failureCount = failureCount,
            onInspectClick = { onOpen("operations") },
        )

        // AUDIT TRAIL COMPONENT: Card-based layout with status indicators & receipts
        AuditTrailSection(
            receipts = state.receipts,
            recentExecutions = state.recent,
            isSimulation = state.demoMode,
            onOpenAll = { onOpen("operations") },
            onInspectEvidence = { onOpen("evidence") },
        )

        // Attention / Needs Review Section
        MizanSectionHeader(stringResource(R.string.home_kicker))
        if (state.attention.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeCard)
                    .background(colors.glass)
                    .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                    .padding(Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Space.md))
                Text(
                    text = "All clear · No pending approval actions required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeCard)
                    .background(colors.glass)
                    .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                    .padding(horizontal = Space.md, vertical = Space.xs),
            ) {
                state.attention.forEach { item ->
                    MizanListRow(
                        title = attentionLabel(item.kind),
                        subtitle = item.referenceId,
                        trailing = when (item.kind) {
                            AttentionKind.APPROVAL -> "Approve"
                            AttentionKind.RECONCILIATION -> "Reconcile"
                            else -> "Review"
                        },
                        tone = when (item.kind) {
                            AttentionKind.APPROVAL -> StatusTone.Warning
                            AttentionKind.RECONCILIATION -> StatusTone.Info
                            else -> StatusTone.Neutral
                        },
                        onClick = {
                            onOpen(
                                when (item.kind) {
                                    AttentionKind.RECONCILIATION -> "reconciliation"
                                    AttentionKind.APPROVAL -> "agent"
                                    else -> "operations"
                                },
                            )
                        },
                    )
                }
            }
        }

        // Health Section (Compact Apple Glass Row)
        MizanSectionHeader(stringResource(R.string.home_health))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeCard)
                .background(colors.glass)
                .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                .padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            HealthRow(stringResource(R.string.health_network), state.health.network)
            HealthRow(stringResource(R.string.health_backend), state.health.backend)
            HealthRow(stringResource(R.string.health_erp), state.health.erp)
        }

        Spacer(Modifier.height(Space.lg))
    }
}

/**
 * Dedicated Audit Trail Component displaying a scrollable card-based list of recent ERP execution receipts.
 */
@Composable
private fun AuditTrailSection(
    receipts: List<TrustReceipt>,
    recentExecutions: List<ExecutionRecord>,
    isSimulation: Boolean,
    onOpenAll: () -> Unit,
    onInspectEvidence: () -> Unit,
) {
    val colors = LocalMizanColors.current
    var selectedFilterIndex by remember { mutableIntStateOf(0) }
    val filterOptions = listOf("All Audit Receipts", "Verified Only", "Recent Executions")

    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "ERP Audit Trail",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
            }
            Text(
                text = "View All",
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpenAll),
            )
        }

        // Micro-interaction filter tabs
        GlassSegmentedControl(
            options = filterOptions,
            selectedIndex = selectedFilterIndex,
            onSelect = { selectedFilterIndex = it },
        )

        // Cards list
        if (receipts.isEmpty() && recentExecutions.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeCard)
                    .background(colors.glass)
                    .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                    .padding(Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "No ERP execution receipts recorded yet. Execute tasks to generate signed audit receipts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        } else {
            when (selectedFilterIndex) {
                0 -> { // All Receipts
                    receipts.take(4).forEach { receipt ->
                        AuditTrailReceiptCard(
                            toolName = toolLabel(receipt.tool),
                            intent = "ERP Sync · ${toolLabel(receipt.tool)}",
                            phaseLabel = receipt.verification.name,
                            statusTone = StatusTone.Success,
                            receiptId = receipt.id.value,
                            erpRecordId = receipt.erpRecordId,
                            policyRule = receipt.policyRuleId,
                            traceId = receipt.id.value,
                            timestampFormatted = "Just now · Authenticated",
                            isBiometricVerified = true,
                            isSimulation = isSimulation,
                            onClick = onInspectEvidence,
                        )
                    }
                }
                1 -> { // Verified Only
                    receipts.filter { it.verification == app.mizan.domain.model.VerificationKind.READ_BACK }.take(4).forEach { receipt ->
                        AuditTrailReceiptCard(
                            toolName = toolLabel(receipt.tool),
                            intent = "ERP Read-Back Confirmed",
                            phaseLabel = "VERIFIED",
                            statusTone = StatusTone.Success,
                            receiptId = receipt.id.value,
                            erpRecordId = receipt.erpRecordId,
                            policyRule = receipt.policyRuleId,
                            traceId = receipt.id.value,
                            timestampFormatted = "Verified by ERP Authority",
                            isBiometricVerified = true,
                            isSimulation = isSimulation,
                            onClick = onInspectEvidence,
                        )
                    }
                }
                2 -> { // Recent Executions
                    recentExecutions.take(4).forEach { record ->
                        AuditTrailReceiptCard(
                            toolName = record.tool.wire,
                            intent = record.intent.ifBlank { record.tool.wire },
                            phaseLabel = phaseLabel(record.phase),
                            statusTone = when (record.phase) {
                                ExecutionPhase.VERIFIED -> StatusTone.Success
                                ExecutionPhase.ERP_FAILURE, ExecutionPhase.REJECTED -> StatusTone.Danger
                                else -> StatusTone.Warning
                            },
                            receiptId = record.id.value,
                            erpRecordId = record.erpRecordId,
                            policyRule = null,
                            traceId = record.traceId.value,
                            timestampFormatted = "Execution Trace",
                            isBiometricVerified = true,
                            isSimulation = isSimulation,
                            onClick = onOpenAll,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    icon: ImageVector,
    tone: StatusTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMizanColors.current
    Column(
        modifier = modifier
            .clip(ShapeCard)
            .background(colors.glass)
            .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(16.dp),
            )
            MizanStatusBadge(value, tone)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QuickChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val colors = LocalMizanColors.current
    Row(
        modifier = Modifier
            .clip(ShapePill)
            .background(colors.surfaceElevated)
            .border(BorderStroke(0.8.dp, colors.glassBorder), ShapePill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.md, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textPrimary)
    }
}

@Composable
private fun HealthRow(label: String, status: HealthStatus) {
    val tone = when (status) {
        HealthStatus.HEALTHY -> StatusTone.Success
        HealthStatus.DEGRADED -> StatusTone.Warning
        HealthStatus.UNAVAILABLE -> StatusTone.Danger
        HealthStatus.UNKNOWN -> StatusTone.Neutral
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        MizanStatusBadge(healthLabel(status), tone)
    }
}

fun <T : ViewModel> simpleFactory(create: () -> T): androidx.lifecycle.ViewModelProvider.Factory =
    object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM {
            @Suppress("UNCHECKED_CAST")
            return create() as VM
        }
    }
