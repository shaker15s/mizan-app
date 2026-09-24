package app.mizan.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mizan.R
import app.mizan.design.component.MizanIconButton
import app.mizan.design.component.MizanListRow
import app.mizan.design.component.MizanMark
import app.mizan.design.component.MizanPrimaryButton
import app.mizan.design.component.MizanSectionHeader
import app.mizan.design.component.MizanStatusBadge
import app.mizan.design.component.StatusTone
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.token.Space
import app.mizan.domain.attention.AttentionItem
import app.mizan.domain.model.ExecutionRecord
import app.mizan.domain.model.HealthStatus
import app.mizan.domain.model.SystemHealth
import app.mizan.graph.AppGraph
import app.mizan.ui.attentionLabel
import app.mizan.ui.healthLabel
import app.mizan.ui.phaseLabel
import app.mizan.ui.roleLabel
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
    val health: SystemHealth = SystemHealth.unknown,
    val offline: Boolean = false,
)

class HomeViewModel(graph: AppGraph) : ViewModel() {
    val state: StateFlow<HomeUi> = graph.session.session.flatMapLatest { session ->
        if (session == null) {
            flowOf(HomeUi())
        } else {
            combine(
                graph.executions.observe(session.tenant.id),
                graph.cases.observe(session.tenant.id),
                graph.health.networkStatus,
            ) { executions, cases, network ->
                val health = graph.health.snapshot(signedIn = true, simulation = graph.demoMode).copy(network = network)
                HomeUi(
                    workspace = session.tenant.displayName,
                    actor = session.actor.displayName,
                    roleName = session.actor.role.name,
                    attention = graph.attention.plan(executions, cases, health, null),
                    recent = executions.take(8),
                    health = health,
                    offline = health.network == HealthStatus.UNAVAILABLE,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())
}

@Composable
fun HomeRoute(graph: AppGraph, expanded: Boolean, onOpen: (String) -> Unit) {
    val vm: HomeViewModel = viewModel(factory = simpleFactory { HomeViewModel(graph) })
    val state by vm.state.collectAsStateWithLifecycleSafe()
    val colors = LocalMizanColors.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            MizanMark()
            Column(Modifier.weight(1f).padding(start = Space.md)) {
                Text(state.workspace.ifBlank { stringResource(R.string.app_name) }, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
                if (state.actor.isNotBlank() && state.roleName.isNotBlank()) {
                    Text(
                        "${state.actor} · ${roleLabel(app.mizan.domain.model.Role.valueOf(state.roleName))}",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            MizanIconButton(Icons.Outlined.Search, stringResource(R.string.cd_search), { onOpen("search") })
        }
        if (state.offline) {
            Text(stringResource(R.string.offline_banner), color = colors.warning, style = MaterialTheme.typography.bodySmall)
        }
        MizanSectionHeader(stringResource(R.string.home_kicker))
        if (state.attention.isEmpty()) {
            Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Text(stringResource(R.string.home_empty_body), color = colors.textSecondary)
            MizanPrimaryButton(stringResource(R.string.home_ask), { onOpen("agent") })
        } else {
            state.attention.forEach { item ->
                MizanListRow(
                    title = attentionLabel(item.kind),
                    subtitle = item.referenceId,
                    trailing = null,
                    onClick = {
                        onOpen(
                            when (item.kind) {
                                app.mizan.domain.attention.AttentionKind.RECONCILIATION -> "reconciliation"
                                app.mizan.domain.attention.AttentionKind.APPROVAL -> "agent"
                                else -> "operations"
                            },
                        )
                    },
                )
            }
        }
        MizanSectionHeader(stringResource(R.string.home_recent))
        if (state.recent.isEmpty()) {
            Text(stringResource(R.string.home_changed), color = colors.textTertiary, style = MaterialTheme.typography.bodySmall)
        } else {
            state.recent.forEach { record ->
                MizanListRow(
                    title = record.intent.ifBlank { record.tool.wire },
                    subtitle = phaseLabel(record.phase),
                    onClick = { onOpen("operations") },
                )
            }
        }
        MizanSectionHeader(stringResource(R.string.home_health))
        HealthRow(stringResource(R.string.health_network), state.health.network)
        HealthRow(stringResource(R.string.health_backend), state.health.backend)
        HealthRow(stringResource(R.string.health_erp), state.health.erp)
        if (expanded) {
            Text(stringResource(R.string.home_changed), color = colors.textTertiary, style = MaterialTheme.typography.bodySmall)
        }
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
        Modifier.fillMaxWidth().clickable { }.padding(vertical = Space.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        MizanStatusBadge(healthLabel(status), tone)
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycleSafe(): androidx.compose.runtime.State<T> =
    androidx.lifecycle.compose.collectAsStateWithLifecycle(this)

fun <T : ViewModel> simpleFactory(create: () -> T): androidx.lifecycle.ViewModelProvider.Factory =
    object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM {
            @Suppress("UNCHECKED_CAST")
            return create() as VM
        }
    }
