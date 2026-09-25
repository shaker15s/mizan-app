package app.mizan.feature.reconciliation

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mizan.R
import app.mizan.design.component.CraftSelectableCard
import app.mizan.design.component.MizanEmptyState
import app.mizan.design.component.MizanGhostButton
import app.mizan.design.component.MizanKeyValue
import app.mizan.design.component.MizanPrimaryButton
import app.mizan.design.component.MizanSecondaryButton
import app.mizan.design.component.MizanSectionHeader
import app.mizan.design.component.MizanStatusBadge
import app.mizan.design.component.ShapeCard
import app.mizan.design.component.ShapeControl
import app.mizan.design.component.StatusTone
import app.mizan.design.component.mizanBounceClick
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.token.Space
import app.mizan.domain.audit.AuditAppend
import app.mizan.domain.execution.HumanMatch
import app.mizan.domain.execution.MatchDecision
import app.mizan.domain.execution.ReconciliationPolicy
import app.mizan.domain.execution.Transition
import app.mizan.domain.model.ReconciliationCase
import app.mizan.domain.model.ReconciliationStatus
import app.mizan.feature.home.simpleFactory
import app.mizan.graph.AppGraph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReconciliationViewModel(private val graph: AppGraph) : ViewModel() {
    val cases = graph.session.session.flatMapLatest { session ->
        if (session == null) flowOf(emptyList()) else graph.cases.observe(session.tenant.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun resolve(case: ReconciliationCase, action: HumanMatch, note: String?, candidateId: String?) {
        val session = graph.session.session.value ?: return
        val decision = ReconciliationPolicy.decide(action, note, candidateId)
        if (decision is MatchDecision.Rejected) return
        val accepted = decision as MatchDecision.Accepted
        viewModelScope.launch {
            val execution = graph.executions.get(session.tenant.id, case.executionId)
            if (execution != null) {
                val transition = graph.machine.next(execution.phase, accepted.event)
                if (transition is Transition.Illegal) return@launch
                graph.executions.upsert(
                    execution.copy(
                        phase = (transition as Transition.Allowed).phase,
                        updatedAt = graph.time.now(),
                        errorCode = "HUMAN_${action.name}",
                    ),
                )
            }
            val status = if (action == HumanMatch.LINK) {
                ReconciliationStatus.LINKED
            } else {
                ReconciliationStatus.CLOSED_WITHOUT_LINK
            }
            graph.cases.upsert(
                case.copy(
                    status = status,
                    notes = note,
                    candidateRecordIds = if (action == HumanMatch.LINK && candidateId != null) {
                        listOf(candidateId)
                    } else {
                        case.candidateRecordIds
                    },
                ),
            )
            graph.audit.append(
                AuditAppend(
                    traceId = case.traceId.value,
                    tenantId = session.tenant.id,
                    actorId = session.actor.id.value,
                    action = "RECONCILIATION_${status.name}",
                    stateBefore = "OPEN",
                    stateAfter = accepted.phase.name,
                    details = "not machine-checked; candidate=${candidateId.orEmpty()}; ${note.orEmpty()}",
                    timestampMillis = graph.time.now().toEpochMilli(),
                ),
            )
        }
    }
}

@Composable
fun ReconciliationRoute(graph: AppGraph, expanded: Boolean) {
    val vm: ReconciliationViewModel = viewModel(factory = simpleFactory { ReconciliationViewModel(graph) })
    val cases by vm.cases.collectAsStateWithLifecycle()
    val colors = LocalMizanColors.current

    val open = cases.filter { it.status == ReconciliationStatus.OPEN }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val current = cases.find { it.id == selectedId }

    if (open.isEmpty() && current == null) {
        MizanEmptyState(stringResource(R.string.recon_empty_title), stringResource(R.string.recon_empty_body))
        return
    }

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
                    .size(44.dp)
                    .clip(ShapeControl)
                    .background(colors.warning.copy(alpha = 0.12f))
                    .border(BorderStroke(0.6.dp, colors.warning.copy(alpha = 0.3f)), ShapeControl),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SyncProblem,
                    contentDescription = null,
                    tint = colors.warning,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.recon_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Text(
                    text = "${open.size} pending ambiguous cases require human match",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }

        // Open Cases List
        if (open.isNotEmpty()) {
            MizanSectionHeader("Cases Awaiting Decision")
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                open.forEach { case ->
                    val isSelected = case.id == selectedId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ShapeCard)
                            .background(if (isSelected) colors.accentMuted.copy(alpha = 0.2f) else colors.glass)
                            .border(
                                BorderStroke(if (isSelected) 1.2.dp else 0.8.dp, if (isSelected) colors.accent else colors.glassBorder),
                                ShapeCard,
                            )
                            .mizanBounceClick(role = Role.Button) { selectedId = case.id }
                            .padding(Space.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(ShapeControl)
                                .background(colors.accentMuted),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.FactCheck, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(Space.sm))
                        Column(Modifier.weight(1f)) {
                            Text(case.intent, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                            Text(case.id, style = MaterialTheme.typography.labelSmall, fontFamily = app.mizan.design.theme.MizanMono, color = colors.textTertiary)
                        }
                        MizanStatusBadge("NEEDS REVIEW", StatusTone.Warning)
                    }
                }
            }
        }

        // Selected Case Resolution Detail
        current?.let { case ->
            MizanSectionHeader("Case Decision Inspector")
            CaseDetailCard(case, vm::resolve) { selectedId = null }
        }

        Spacer(Modifier.height(Space.xl))
    }
}

@Composable
private fun CaseDetailCard(
    case: ReconciliationCase,
    onResolve: (ReconciliationCase, HumanMatch, String?, String?) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalMizanColors.current
    var note by remember(case.id) { mutableStateOf("") }
    var chosen by remember(case.id) { mutableStateOf(case.candidateRecordIds.singleOrNull()) }
    var needNote by remember { mutableStateOf(false) }

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
            Text(
                text = "Resolve Ambiguity",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            MizanGhostButton("Dismiss", onClick = onClose)
        }

        MizanKeyValue("Execution Trace", case.executionId.value, mono = true)

        Text(
            text = stringResource(R.string.recon_candidates),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )

        if (case.candidateRecordIds.isEmpty()) {
            Text(stringResource(R.string.recon_no_candidate), color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                case.candidateRecordIds.forEach { id ->
                    val isCandidateSelected = id == chosen
                    CraftSelectableCard(
                        title = id,
                        subtitle = "Candidate ERP Transaction Match",
                        icon = Icons.Outlined.ReceiptLong,
                        selected = isCandidateSelected,
                        onSelect = { chosen = id },
                    )
                }
            }
        }

        // Justification Note Input
        OutlinedTextField(
            value = note,
            onValueChange = {
                note = it
                needNote = false
            },
            label = { Text(stringResource(R.string.recon_note_hint)) },
            modifier = Modifier.fillMaxWidth(),
            shape = ShapeControl,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.border,
                focusedContainerColor = colors.surfaceElevated,
                unfocusedContainerColor = colors.surfaceElevated,
            ),
        )

        if (needNote) {
            Text(stringResource(R.string.recon_need_note), color = colors.danger, style = MaterialTheme.typography.bodySmall)
        }

        // Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            MizanPrimaryButton(
                text = stringResource(R.string.recon_link),
                onClick = {
                    if (note.isBlank() || chosen.isNullOrBlank()) needNote = true
                    else {
                        onResolve(case, HumanMatch.LINK, note, chosen)
                        onClose()
                    }
                },
                enabled = chosen != null,
                modifier = Modifier.weight(1f),
            )
            MizanSecondaryButton(
                text = stringResource(R.string.recon_close),
                onClick = {
                    onResolve(case, HumanMatch.CLOSE_WITHOUT_LINK, note.ifBlank { null }, null)
                    onClose()
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
