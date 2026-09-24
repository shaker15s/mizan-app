package app.mizan.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.mizan.R
import app.mizan.design.component.MizanCommandField
import app.mizan.design.component.MizanGhostButton
import app.mizan.design.component.MizanListRow
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.token.Space
import app.mizan.domain.store.LocalSearchHits
import app.mizan.graph.AppGraph
import kotlinx.coroutines.launch

@Composable
fun SearchRoute(graph: AppGraph, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(LocalSearchHits(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())) }
    val scope = rememberCoroutineScope()
    val colors = LocalMizanColors.current
    Column(Modifier.fillMaxSize().padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        MizanGhostButton(stringResource(R.string.cd_back), onBack)
        Text(stringResource(R.string.search_scope), color = colors.textSecondary)
        MizanCommandField(
            value = query,
            onValueChange = { query = it },
            onSubmit = {
                val session = graph.session.session.value ?: return@MizanCommandField
                scope.launch { hits = graph.readModels.search(session.tenant.id, query, 20) }
            },
            placeholder = stringResource(R.string.search_hint),
        )
        if (query.length in 1..1) {
            Text(stringResource(R.string.search_short), color = colors.textTertiary)
        }
        val empty = hits.orders.isEmpty() && hits.customers.isEmpty() && hits.stock.isEmpty() &&
            hits.receipts.isEmpty() && hits.executions.isEmpty() && hits.cases.isEmpty()
        if (query.length >= 2 && empty) {
            Text(stringResource(R.string.search_empty), color = colors.textSecondary)
        }
        hits.customers.forEach { MizanListRow(it.name, subtitle = it.id) }
        hits.orders.forEach { MizanListRow(it.customerName, subtitle = it.id) }
        hits.stock.forEach { MizanListRow(it.name, subtitle = it.sku) }
        hits.executions.forEach { MizanListRow(it.intent, subtitle = it.id.value) }
        hits.cases.forEach { MizanListRow(it.intent, subtitle = it.id) }
        hits.receipts.forEach { MizanListRow(it.tool.wire, subtitle = it.id.value) }
    }
}
