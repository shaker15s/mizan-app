package app.mizan.feature.evidence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.mizan.design.component.MizanSecondaryButton
import app.mizan.design.component.MizanSectionHeader
import app.mizan.design.component.MizanSurface
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.token.Space
import app.mizan.domain.audit.ChainReport
import app.mizan.domain.model.TrustReceipt
import app.mizan.feature.home.simpleFactory
import app.mizan.graph.AppGraph
import app.mizan.ui.chainLabel
import app.mizan.ui.toolLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EvidenceViewModel(private val graph: AppGraph) : ViewModel() {
    val receipts = graph.session.session.flatMapLatest { session ->
        if (session == null) flowOf(emptyList()) else graph.receipts.observe(session.tenant.id, 40)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var report by mutableStateOf<ChainReport?>(null)
    var checking by mutableStateOf(false)
    var inspected: Int = 0

    fun check(demonstrate: Boolean) {
        val session = graph.session.session.value ?: return
        viewModelScope.launch {
            checking = true
            inspected = 0
            val events = withContext(Dispatchers.Default) { graph.audit.ledger(session.tenant.id) }
            val result = withContext(Dispatchers.Default) {
                if (demonstrate) graph.chain.demonstrateOnCopy(events) else {
                    events.forEachIndexed { index, _ -> inspected = index + 1 }
                    graph.chain.verify(events)
                }
            }
            report = result
            checking = false
        }
    }
}

@Composable
fun EvidenceRoute(graph: AppGraph, expanded: Boolean) {
    val vm: EvidenceViewModel = viewModel(factory = simpleFactory { EvidenceViewModel(graph) })
    val receipts by vm.receipts.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }
    val selected = receipts.find { it.id.value == open }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        MizanSectionHeader(stringResource(R.string.evidence_title))
        Text(stringResource(R.string.evidence_empty_body), color = LocalMizanColors.current.textSecondary)
        MizanSecondaryButton(
            stringResource(R.string.evidence_check),
            { vm.check(false) },
            enabled = !vm.checking,
        )
        if (graph.demoMode) {
            Text(stringResource(R.string.evidence_demo_tamper_note), color = LocalMizanColors.current.textTertiary)
            MizanSecondaryButton(stringResource(R.string.evidence_demo_tamper), { vm.check(true) })
        }
        vm.report?.let { report ->
            Text(chainLabel(report.messageCode), color = LocalMizanColors.current.textPrimary)
        }
        if (receipts.isEmpty()) {
            MizanEmptyState(stringResource(R.string.evidence_empty_title), stringResource(R.string.evidence_empty_body))
        } else {
            receipts.forEach { receipt ->
                MizanListRow(
                    title = toolLabel(receipt.tool),
                    subtitle = receipt.erpRecordId ?: receipt.id.value,
                    onClick = { open = receipt.id.value },
                )
            }
        }
        selected?.let { ReceiptDetail(it, expanded) }
    }
}

@Composable
private fun ReceiptDetail(receipt: TrustReceipt, expanded: Boolean) {
    MizanSurface {
        MizanSectionHeader(stringResource(R.string.evidence_fact))
        Text(
            if (receipt.origin == app.mizan.domain.model.EvidenceOrigin.SIMULATION) {
                stringResource(R.string.outcome_sim_checked)
            } else if (receipt.verification == app.mizan.domain.model.VerificationKind.READ_BACK) {
                stringResource(R.string.outcome_erp_checked)
            } else {
                stringResource(R.string.outcome_accepted)
            },
        )
        MizanSectionHeader(stringResource(R.string.evidence_policy))
        MizanKeyValue(stringResource(R.string.proposal_policy), receipt.policyRuleId, mono = true)
        MizanSectionHeader(stringResource(R.string.evidence_proof))
        MizanKeyValue(stringResource(R.string.evidence_proof), receipt.verification.name)
        if (expanded) {
            MizanSectionHeader(stringResource(R.string.evidence_meta))
            MizanKeyValue("trace", receipt.traceId.value, mono = true)
            MizanKeyValue("idempotency", receipt.idempotencyKey.value, mono = true)
        }
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycle() =
    androidx.lifecycle.compose.collectAsStateWithLifecycle(this)
