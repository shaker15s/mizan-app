package app.mizan.feature.operations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.mizan.design.component.MizanSectionHeader
import app.mizan.design.component.StatusTone
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.token.Space
import app.mizan.domain.execution.ExecutionPhase
import app.mizan.domain.model.ExecutionRecord
import app.mizan.feature.home.simpleFactory
import app.mizan.graph.AppGraph
import app.mizan.ui.phaseLabel
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
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val current = records.find { it.id.value == selected }
    if (records.isEmpty()) {
        MizanEmptyState(
            stringResource(R.string.ops_empty_title),
            stringResource(R.string.ops_empty_body),
        )
        return
    }
    if (expanded) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(0.42f).verticalScroll(rememberScrollState()).padding(Space.lg)) {
                MizanSectionHeader(stringResource(R.string.ops_title))
                records.forEach { record ->
                    MizanListRow(
                        title = record.intent.ifBlank { record.tool.wire },
                        subtitle = phaseLabel(record.phase),
                        trailing = phaseLabel(record.phase),
                        tone = tone(record.phase),
                        onClick = { selected = record.id.value },
                    )
                }
            }
            Column(Modifier.weight(0.58f).padding(Space.lg)) {
                if (current == null) {
                    Text(stringResource(R.string.ops_empty_body), color = LocalMizanColors.current.textSecondary)
                } else {
                    Detail(current)
                }
            }
        }
    } else if (current == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.lg)) {
            MizanSectionHeader(stringResource(R.string.ops_title))
            records.forEach { record ->
                MizanListRow(
                    title = record.intent.ifBlank { record.tool.wire },
                    subtitle = phaseLabel(record.phase),
                    tone = tone(record.phase),
                    onClick = { selected = record.id.value },
                )
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.lg)) {
            MizanSecondaryBack { selected = null }
            Detail(current)
        }
    }
}

@Composable
private fun Detail(record: ExecutionRecord) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(phaseLabel(record.phase), style = MaterialTheme.typography.headlineSmall)
        Text(record.intent, color = LocalMizanColors.current.textSecondary)
        MizanKeyValue(stringResource(R.string.evidence_meta), record.id.value, mono = true)
        MizanKeyValue("trace", record.traceId.value, mono = true)
        record.erpRecordId?.let { MizanKeyValue(stringResource(R.string.evidence_fact), it, mono = true) }
        record.errorCode?.let { MizanKeyValue(stringResource(R.string.outcome_refused), it) }
    }
}

@Composable
private fun MizanSecondaryBack(onClick: () -> Unit) {
    app.mizan.design.component.MizanGhostButton(stringResource(R.string.cd_back), onClick)
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

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycle() =
    androidx.lifecycle.compose.collectAsStateWithLifecycle(this)
