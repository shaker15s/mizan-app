package app.mizan.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.mizan.R
import app.mizan.simulationActors
import app.mizan.design.component.MizanGhostButton
import app.mizan.design.component.MizanKeyValue
import app.mizan.design.component.MizanListRow
import app.mizan.design.component.MizanSectionHeader
import app.mizan.design.component.MizanStatusBadge
import app.mizan.design.component.StatusTone
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.token.Space
import app.mizan.domain.model.HealthStatus
import app.mizan.graph.AppGraph
import app.mizan.log.StartupTrace
import app.mizan.ui.healthLabel
import app.mizan.ui.roleLabel

@Composable
fun AccountRoute(
    graph: AppGraph,
    onOpen: (String) -> Unit,
    onPreferencesChanged: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val colors = LocalMizanColors.current
    val session by graph.session.session.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        MizanSectionHeader(stringResource(R.string.account_title))
        session?.let {
            Text(it.actor.displayName, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
            Text(roleLabel(it.actor.role), color = colors.textSecondary)
            Text(it.tenant.displayName, color = colors.textSecondary)
            if (graph.demoMode) {
                MizanStatusBadge(stringResource(R.string.simulation_banner), StatusTone.Warning)
            }
        }
        MizanListRow(stringResource(R.string.account_security), onClick = { onOpen("security") })
        MizanListRow(stringResource(R.string.account_connection), onClick = { onOpen("connection") })
        MizanListRow(stringResource(R.string.account_rules), onClick = { onOpen("governance") })
        PreferenceChoice(stringResource(R.string.account_language), listOf("system" to R.string.account_system, "en" to R.string.account_english, "ar" to R.string.account_arabic), graph.preferences.language) {
            graph.preferences.language = it
            onPreferencesChanged()
        }
        PreferenceChoice(stringResource(R.string.account_theme), listOf("system" to R.string.account_system, "light" to R.string.account_light, "dark" to R.string.account_dark), graph.preferences.theme) {
            graph.preferences.theme = it
            onPreferencesChanged()
        }
        var motion by remember { mutableStateOf(graph.preferences.reducedMotion) }
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.account_motion), modifier = Modifier.weight(1f))
            Switch(motion, {
                motion = it
                graph.preferences.reducedMotion = it
                onPreferencesChanged()
            })
        }
        val current = session
        if (graph.demoMode && current != null) {
            Text(stringResource(R.string.account_sim_role_note), color = colors.textTertiary, style = MaterialTheme.typography.bodySmall)
            simulationActors(current.tenant.id).forEach { actor ->
                MizanListRow(actor.displayName, subtitle = roleLabel(actor.role), onClick = {
                    graph.session.updateActor(actor)
                })
            }
            var ambiguous by remember { mutableStateOf(graph.preferences.simulateNextAmbiguous) }
            androidx.compose.foundation.layout.Row {
                Text(stringResource(R.string.account_sim_ambiguous), modifier = Modifier.weight(1f))
                Switch(ambiguous, {
                    ambiguous = it
                    graph.preferences.simulateNextAmbiguous = it
                })
            }
        }
        MizanSectionHeader(stringResource(R.string.account_diagnostics))
        MizanKeyValue(
            stringResource(R.string.account_startup),
            StartupTrace.firstFrameMs?.let { "$it ms" } ?: stringResource(R.string.health_UNKNOWN),
            mono = true,
        )
        MizanGhostButton(stringResource(R.string.account_sign_out), {
            graph.tokens.clear()
            graph.session.clear()
            onSignedOut()
        })
    }
}

@Composable
private fun PreferenceChoice(
    title: String,
    options: List<Pair<String, Int>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    options.forEach { (value, label) ->
        MizanListRow(
            title = stringResource(label),
            trailing = if (value == selected) "•" else null,
            onClick = { onSelect(value) },
        )
    }
}

@Composable
fun SecurityRoute(graph: AppGraph, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        MizanGhostButton(stringResource(R.string.cd_back), onBack)
        MizanSectionHeader(stringResource(R.string.security_title))
        Text(stringResource(R.string.security_no_erp_secret))
        Text(stringResource(R.string.security_freshness), color = LocalMizanColors.current.textSecondary)
        val signedIn = graph.session.session.value != null
        MizanKeyValue(
            stringResource(R.string.security_session),
            healthLabel(if (signedIn) HealthStatus.HEALTHY else HealthStatus.UNKNOWN),
        )
    }
}

@Composable
fun ConnectionRoute(graph: AppGraph, onBack: () -> Unit) {
    val network by graph.health.networkStatus.collectAsStateWithLifecycle()
    val health = graph.health.snapshot(graph.session.session.value != null, graph.demoMode).copy(network = network)
    Column(Modifier.fillMaxSize().padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        MizanGhostButton(stringResource(R.string.cd_back), onBack)
        MizanSectionHeader(stringResource(R.string.connection_title))
        if (graph.demoMode) {
            Text(stringResource(R.string.connection_sim))
        } else if (graph.apiBaseUrl.isBlank()) {
            Text(stringResource(R.string.connection_not_configured))
        }
        Text(stringResource(R.string.security_no_erp_secret), color = LocalMizanColors.current.textSecondary)
        MizanKeyValue(stringResource(R.string.health_network), healthLabel(health.network))
        MizanKeyValue(stringResource(R.string.connection_authenticated), healthLabel(health.authentication))
        MizanKeyValue(stringResource(R.string.connection_healthy), healthLabel(health.backend))
        MizanKeyValue(stringResource(R.string.connection_erp), healthLabel(health.erp))
        MizanKeyValue(stringResource(R.string.connection_synced), stringResource(R.string.connection_unknown))
        Text(
            stringResource(R.string.connection_refresh_scope),
            color = LocalMizanColors.current.textTertiary,
            style = MaterialTheme.typography.bodySmall,
        )
        app.mizan.design.component.MizanSecondaryButton(
            stringResource(R.string.connection_refresh),
            graph.health::refresh,
        )
    }
}
