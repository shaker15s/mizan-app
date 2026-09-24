package app.mizan.feature.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mizan.R
import app.mizan.design.component.MizanCommandField
import app.mizan.design.component.MizanDangerButton
import app.mizan.design.component.MizanGlassDock
import app.mizan.design.component.MizanKeyValue
import app.mizan.design.component.MizanPrimaryButton
import app.mizan.design.component.MizanSecondaryButton
import app.mizan.design.component.MizanStatusBadge
import app.mizan.design.component.MizanSurface
import app.mizan.design.component.StatusTone
import app.mizan.design.component.SuggestionRow
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.token.Space
import app.mizan.domain.agent.MissingField
import app.mizan.domain.agent.ProposalResult
import app.mizan.domain.authority.AuthorityOutcome
import app.mizan.domain.authority.ExecuteCommand
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.policy.ApprovalRecord
import app.mizan.domain.policy.SodCode
import app.mizan.domain.model.ConnectorCapabilities
import app.mizan.domain.model.CreateDraftOrderArgs
import app.mizan.domain.model.CustomerSearchArgs
import app.mizan.domain.model.SalesSummaryArgs
import app.mizan.domain.model.StockLookupArgs
import app.mizan.domain.model.Proposal
import app.mizan.domain.model.ToolName
import app.mizan.domain.security.AuthProof
import app.mizan.domain.security.Freshness
import app.mizan.feature.home.simpleFactory
import app.mizan.graph.AppGraph
import app.mizan.simulationActors
import app.mizan.security.confirmDevice
import app.mizan.ui.missingLabel
import app.mizan.ui.reasonLabel
import app.mizan.ui.toolLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface AgentLine {
    val id: String

    data class User(override val id: String, val text: String) : AgentLine
    data class Info(override val id: String, val code: String) : AgentLine
    data class Clarify(override val id: String, val fields: List<MissingField>) : AgentLine
    data class ProposalLine(override val id: String, val proposal: Proposal) : AgentLine
    data class Result(override val id: String, val code: String, val detail: String?) : AgentLine
}

class AgentViewModel(private val graph: AppGraph) : ViewModel() {
    private val _lines = MutableStateFlow<List<AgentLine>>(emptyList())
    val lines = _lines.asStateFlow()
    var input by mutableStateOf("")
    var pending by mutableStateOf<Proposal?>(null)
    var needsProof by mutableStateOf(false)
    var offerSimulatedProof by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var secondApproverId by mutableStateOf<String?>(null)

    fun submit() {
        val text = input.trim()
        val session = graph.session.session.value ?: return
        if (text.isBlank() || busy) return
        input = ""
        add(AgentLine.User(newId(), text))
        viewModelScope.launch {
            val customers = graph.readModels.customers(session.tenant.id).first()
            val caps = if (graph.demoMode) ConnectorCapabilities.simulation else ConnectorCapabilities.servicePreview
            when (val result = graph.proposals.propose(text, session.actor, caps, customers)) {
                is ProposalResult.Clarify -> add(AgentLine.Clarify(newId(), result.interpretation.missing))
                is ProposalResult.Rejected -> add(AgentLine.Info(newId(), result.reasonCode))
                is ProposalResult.Unsupported -> add(AgentLine.Info(newId(), "TOOL_NOT_SUPPORTED"))
                is ProposalResult.Proposed -> {
                    if (result.proposal.args.tool.readOnly) {
                        add(AgentLine.Info(newId(), "SAFE_READ"))
                        add(AgentLine.Result(newId(), "READ_LOCAL", savedCopy(result.proposal)))
                    } else {
                        pending = result.proposal
                        secondApproverId = null
                        add(AgentLine.ProposalLine(newId(), result.proposal))
                    }
                }
            }
        }
    }

    fun dismiss() {
        pending = null
        add(AgentLine.Info(newId(), "CANCELLED"))
    }

    fun requestApproval() {
        val proposal = pending ?: return
        val session = graph.session.session.value ?: return
        val second = simulationActors(session.tenant.id).find { it.id.value == secondApproverId }
        val approvals = listOf(ApprovalRecord(session.actor, graph.time.now().toEpochMilli())) +
            listOfNotNull(second?.let { ApprovalRecord(it, graph.time.now().toEpochMilli()) })
        val sod = graph.sod.check(
            proposal.initiator.id,
            proposal.tenantId,
            proposal.policy.approval,
            approvals,
        )
        if (sod != SodCode.SATISFIED && sod != SodCode.NOT_REQUIRED) {
            add(AgentLine.Result(newId(), "SOD_${sod.name}", null))
            return
        }
        if (proposal.policy.approval == ApprovalLevel.L0_NONE) {
            execute(null)
            return
        }
        val freshness = graph.reauth.check(
            proof = null,
            operationId = proposal.executionId.value,
            actorId = session.actor.id,
            tenantId = session.tenant.id,
            approval = proposal.policy.approval,
        )
        if (freshness != Freshness.FRESH) {
            needsProof = true
            offerSimulatedProof = false
        } else {
            execute(null)
        }
    }

    fun onProof(proof: AuthProof) {
        needsProof = false
        execute(proof)
    }

    fun clearChallenge() {
        needsProof = false
        offerSimulatedProof = false
    }

    fun useSimulatedProof() {
        val session = graph.session.session.value ?: return
        val proposal = pending ?: return
        offerSimulatedProof = false
        needsProof = false
        execute(
            app.mizan.security.simulatedProof(session.actor.id, session.tenant.id, proposal.executionId.value),
        )
    }

    private fun execute(proof: AuthProof?) {
        val proposal = pending ?: return
        val session = graph.session.session.value ?: return
        busy = true
        viewModelScope.launch {
            val second = simulationActors(session.tenant.id).find { it.id.value == secondApproverId }
            val outcome = graph.authority.execute(
                ExecuteCommand(
                    proposal = proposal,
                    approver = session.actor,
                    proof = proof,
                    secondApprover = second,
                    simulateAmbiguous = graph.demoMode && graph.preferences.simulateNextAmbiguous,
                ),
            )
            if (graph.preferences.simulateNextAmbiguous) graph.preferences.simulateNextAmbiguous = false
            busy = false
            pending = null
            when (outcome) {
                is AuthorityOutcome.Verified -> add(
                    AgentLine.Result(
                        newId(),
                        if (outcome.verification == app.mizan.domain.model.VerificationKind.READ_BACK) {
                            "ERP_CHECKED"
                        } else {
                            "SIM_CHECKED"
                        },
                        outcome.erpRecordId,
                    ),
                )
                is AuthorityOutcome.AcceptedUnverified -> add(AgentLine.Result(newId(), "ACCEPTED", outcome.messageCode))
                is AuthorityOutcome.Uncertain -> add(AgentLine.Result(newId(), "UNCERTAIN", outcome.messageCode))
                is AuthorityOutcome.Refused -> add(AgentLine.Result(newId(), outcome.error.code, null))
            }
        }
    }

    private suspend fun savedCopy(proposal: Proposal): String {
        val tenant = proposal.tenantId
        return when (val args = proposal.args) {
            is StockLookupArgs -> {
                val hit = graph.readModels.stock(tenant).first().find { it.sku.equals(args.sku, true) }
                if (hit == null) "NOT_IN_SAVED_COPY ${args.sku}" else "${hit.name} ${hit.availableQty}"
            }
            is CustomerSearchArgs -> {
                val hits = graph.readModels.customers(tenant).first()
                    .filter { it.name.contains(args.query, true) }
                if (hits.isEmpty()) "NOT_IN_SAVED_COPY" else hits.joinToString { it.name }
            }
            is SalesSummaryArgs -> {
                val orders = graph.readModels.orders(tenant).first()
                "orders=${orders.size}"
            }
            else -> args.tool.wire
        }
    }

    private fun add(line: AgentLine) {
        _lines.value = _lines.value + line
    }

    private fun newId() = UUID.randomUUID().toString()
}

@Composable
fun AgentRoute(graph: AppGraph, activity: FragmentActivity, expanded: Boolean) {
    val vm: AgentViewModel = viewModel(factory = simpleFactory { AgentViewModel(graph) })
    val lines by vm.lines.collectAsStateWithLifecycleSafe()
    val listState = rememberLazyListState()
    val colors = LocalMizanColors.current
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    if (vm.needsProof) {
        LaunchedEffect(vm.pending?.executionId) {
            val session = graph.session.session.value ?: return@LaunchedEffect
            val proposal = vm.pending ?: return@LaunchedEffect
            confirmDevice(
                activity = activity,
                actorId = session.actor.id,
                tenantId = session.tenant.id,
                operationId = proposal.executionId.value,
                allowSimulated = graph.demoMode,
                title = activity.getString(R.string.reauth_title),
                subtitle = activity.getString(R.string.reauth_body),
                onProof = vm::onProof,
                onNeedSimulated = { vm.offerSimulatedProof = true },
                onUnavailable = { vm.clearChallenge() },
            )
        }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            item(key = "intro", contentType = "intro") {
                Text(
                    stringResource(R.string.agent_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = Space.lg),
                )
            }
            items(lines, key = { it.id }, contentType = { it::class.simpleName }) { line ->
                when (line) {
                    is AgentLine.User -> Text(line.text, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    is AgentLine.Info -> Text(reasonLabel(line.code), color = colors.textSecondary)
                    is AgentLine.Clarify -> ClarifyBlock(line.fields)
                    is AgentLine.ProposalLine -> ProposalBlock(
                        proposal = line.proposal,
                        demo = graph.demoMode,
                        secondId = vm.secondApproverId,
                        actors = graph.session.session.value?.let { simulationActors(it.tenant.id) }.orEmpty(),
                        onSecond = { vm.secondApproverId = it },
                        onReview = vm::requestApproval,
                        onDismiss = vm::dismiss,
                    )
                    is AgentLine.Result -> ResultBlock(line)
                }
            }
        }
        if (vm.offerSimulatedProof) {
            MizanSurface(Modifier.padding(Space.lg)) {
                Text(stringResource(R.string.reauth_sim_note))
                MizanSecondaryButton(stringResource(R.string.reauth_sim), vm::useSimulatedProof)
            }
        }
        MizanGlassDock {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                if (lines.isEmpty()) {
                    val stockExample = stringResource(R.string.suggest_stock)
                    val draftExample = stringResource(R.string.suggest_draft)
                    SuggestionRow(
                        listOf(
                            stringResource(R.string.tool_stock) to { vm.input = stockExample },
                            stringResource(R.string.tool_draft) to { vm.input = draftExample },
                        ),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    MizanCommandField(
                        value = vm.input,
                        onValueChange = { vm.input = it },
                        onSubmit = vm::submit,
                        placeholder = stringResource(R.string.agent_placeholder),
                        modifier = Modifier.weight(1f),
                        enabled = !vm.busy,
                    )
                }
                MizanPrimaryButton(
                    stringResource(R.string.agent_send),
                    vm::submit,
                    enabled = vm.input.isNotBlank() && !vm.busy,
                    loading = vm.busy,
                )
                if (expanded) {
                    Text(stringResource(R.string.agent_intro), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                }
            }
        }
    }
}

@Composable
private fun ClarifyBlock(fields: List<MissingField>) {
    MizanSurface {
        Text(stringResource(R.string.agent_clarify), style = MaterialTheme.typography.titleMedium)
        fields.distinct().forEach { field ->
            Text(missingLabel(field), color = LocalMizanColors.current.textSecondary)
        }
    }
}

@Composable
private fun ProposalBlock(
    proposal: Proposal,
    demo: Boolean,
    secondId: String?,
    actors: List<app.mizan.domain.model.Actor>,
    onSecond: (String) -> Unit,
    onReview: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalMizanColors.current
    val destructive = proposal.args.tool.destructive
    MizanSurface {
        Text(toolLabel(proposal.args.tool), style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
        MizanStatusBadge(
            if (demo) stringResource(R.string.agent_sim_policy) else stringResource(R.string.agent_preview),
            if (demo) StatusTone.Warning else StatusTone.Info,
        )
        MizanKeyValue(stringResource(R.string.proposal_policy), reasonLabel(proposal.policy.reasonCode))
        proposal.amount?.let { money ->
            MizanKeyValue(stringResource(R.string.proposal_amount), money.format(java.util.Locale.getDefault()))
        }
        (proposal.args as? CreateDraftOrderArgs)?.let { args ->
            MizanKeyValue(stringResource(R.string.proposal_customer), args.customerName)
            MizanKeyValue(stringResource(R.string.proposal_action), args.itemsSummary)
        }
        MizanKeyValue(stringResource(R.string.proposal_actor), proposal.initiator.displayName)
        Text(stringResource(R.string.risk_note), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
        proposal.risk.factors.forEach { factor ->
            Text("${factor.code} · ${factor.weight.name}", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        if (proposal.policy.approval == ApprovalLevel.L4_DUAL && demo) {
            Text(stringResource(R.string.approve_need_second), color = colors.textSecondary)
            actors.filter { it.id != proposal.initiator.id && it.role != app.mizan.domain.model.Role.AUDITOR && it.role != app.mizan.domain.model.Role.SALES_REP }
                .forEach { actor ->
                    MizanSecondaryButton(
                        actor.displayName + if (actor.id.value == secondId) " •" else "",
                        { onSecond(actor.id.value) },
                        Modifier.fillMaxWidth(),
                    )
                }
        } else if (proposal.policy.approval == ApprovalLevel.L4_DUAL) {
            Text(stringResource(R.string.approve_need_second), color = colors.warning)
        }
        if (destructive) {
            Text(stringResource(R.string.approve_destructive_body), color = colors.danger)
            MizanDangerButton(stringResource(R.string.approve_cancel_order), onReview, Modifier.fillMaxWidth())
        } else {
            MizanPrimaryButton(stringResource(R.string.approve_execute), onReview, Modifier.fillMaxWidth())
        }
        MizanSecondaryButton(stringResource(R.string.approve_keep), onDismiss, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ResultBlock(line: AgentLine.Result) {
    val (text, tone) = when (line.code) {
        "SIM_CHECKED" -> stringResource(R.string.outcome_sim_checked) to StatusTone.Warning
        "ERP_CHECKED" -> stringResource(R.string.outcome_erp_checked) to StatusTone.Success
        "ACCEPTED" -> stringResource(R.string.outcome_accepted) to StatusTone.Info
        "UNCERTAIN" -> stringResource(R.string.outcome_uncertain) to StatusTone.Warning
        "READ_LOCAL" -> stringResource(R.string.outcome_saved_copy) to StatusTone.Info
        else -> stringResource(R.string.outcome_refused) + " " + reasonLabel(line.code) to StatusTone.Danger
    }
    MizanSurface {
        MizanStatusBadge(text, tone)
        line.detail?.let { MizanKeyValue(stringResource(R.string.evidence_meta), it, mono = true) }
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycleSafe() =
    androidx.lifecycle.compose.collectAsStateWithLifecycle(this)
