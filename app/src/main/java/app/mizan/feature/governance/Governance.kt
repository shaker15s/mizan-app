package app.mizan.feature.governance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.mizan.R
import app.mizan.design.component.MizanSectionHeader
import app.mizan.design.component.MizanSurface
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.token.Space
import app.mizan.graph.AppGraph

@Composable
fun GovernanceRoute(graph: AppGraph) {
    val colors = LocalMizanColors.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        MizanSectionHeader(stringResource(R.string.gov_title))
        Text(stringResource(R.string.gov_intro), color = colors.textSecondary)
        Rule(stringResource(R.string.gov_reads), "POL-SAFE-READ")
        Rule(stringResource(R.string.gov_auditor), "POL-AUDITOR-READONLY")
        Rule(stringResource(R.string.gov_destructive), "POL-DESTRUCTIVE")
        Rule(stringResource(R.string.gov_dual), "POL-THRESHOLD-L4")
        Rule(stringResource(R.string.gov_currency), "POL-CURRENCY-UNCONFIGURED")
        if (graph.demoMode) {
            Text(stringResource(R.string.gov_sim_thresholds), style = MaterialTheme.typography.bodySmall, color = colors.warning)
        }
    }
}

@Composable
private fun Rule(text: String, id: String) {
    MizanSurface {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = LocalMizanColors.current.textPrimary)
        Text(id, style = MaterialTheme.typography.labelSmall, color = LocalMizanColors.current.textTertiary)
    }
}
