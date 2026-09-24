package app.mizan.feature.reconciliation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mizan.R
import app.mizan.design.component.MizanEmptyState
import app.mizan.design.component.MizanKeyValue
import app.mizan.design.component.MizanListRow
import app.mizan.design.component.MizanPrimaryButton
import app.mizan.design.component.MizanSecondaryButton
import app.mizan.design.component.MizanSectionHeader
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
    val open = cases.filter { it.status == ReconciliationStatus.OPEN }
    var selected by remember { mutableStateOf<String?>(null) }
    val current = cases.find { it.id == selected }
    if (open.isEmpty() && current == null) {
        MizanEmptyState(stringResource(R.string.recon_empty_title), stringResource(R.string.recon_empty_body))
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        MizanSectionHeader(stringResource(R.string.recon_title))
        Text(stringResource(R.string.recon_empty_body), color = LocalMizanColors.current.textSecondary)
        open.forEach { case ->
            MizanListRow(case.intent, subtitle = case.id, onClick = { selected = case.id })
        }
        current?.let { CaseDetail(it, vm::resolve) }
        if (expanded) {
            Text(stringResource(R.string.outcome_uncertain), color = LocalMizanColors.current.textTertiary)
        }
    }
}

@Composable
private fun CaseDetail(
    case: ReconciliationCase,
    onResolve: (ReconciliationCase, HumanMatch, String?, String?) -> Unit,
) {
    var note by remember(case.id) { mutableStateOf("") }
    var chosen by remember(case.id) { mutableStateOf(case.candidateRecordIds.singleOrNull()) }
    var needNote by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        MizanKeyValue(stringResource(R.string.evidence_meta), case.executionId.value, mono = true)
        Text(stringResource(R.string.recon_candidates), style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
        if (case.candidateRecordIds.isEmpty()) {
            Text(stringResource(R.string.recon_no_candidate), color = LocalMizanColors.current.textSecondary)
        } else {
            Text(stringResource(R.string.recon_pick), color = LocalMizanColors.current.textSecondary)
            case.candidateRecordIds.forEach { id ->
                MizanListRow(
                    title = id,
                    trailing = if (id == chosen) "•" else null,
                    onClick = { chosen = id },
                )
            }
        }
        OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.recon_note_hint)) })
        if (needNote) Text(stringResource(R.string.recon_need_note), color = LocalMizanColors.current.danger)
        MizanPrimaryButton(
            stringResource(R.string.recon_link),
            {
                if (note.isBlank() || chosen.isNullOrBlank()) needNote = true
                else onResolve(case, HumanMatch.LINK, note, chosen)
            },
            enabled = chosen != null,
        )
        MizanSecondaryButton(stringResource(R.string.recon_close), {
            onResolve(case, HumanMatch.CLOSE_WITHOUT_LINK, note.ifBlank { null }, null)
        })
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycle() =
    androidx.lifecycle.compose.collectAsStateWithLifecycle(this)
