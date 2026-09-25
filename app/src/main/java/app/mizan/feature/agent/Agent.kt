package app.mizan.feature.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mizan.R
import app.mizan.design.component.MizanDangerButton
import app.mizan.design.component.MizanRobotScale
import app.mizan.design.component.RobotScaleState
import app.mizan.design.component.MizanPrimaryButton
import app.mizan.design.component.MizanSecondaryButton
import app.mizan.design.component.MizanStatusBadge
import app.mizan.design.component.ShapeBubbleAgent
import app.mizan.design.component.ShapeBubbleUser
import app.mizan.design.component.ShapeCard
import app.mizan.design.component.ShapePill
import app.mizan.design.component.StatusTone
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.theme.MizanMono
import app.mizan.design.token.Space
import app.mizan.domain.agent.MissingField
import app.mizan.domain.agent.ProposalResult
import app.mizan.domain.authority.AuthorityOutcome
import app.mizan.domain.authority.ExecuteCommand
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.ConnectorCapabilities
import app.mizan.domain.model.CreateDraftOrderArgs
import app.mizan.domain.model.CustomerSearchArgs
import app.mizan.domain.model.Proposal
import app.mizan.domain.model.SalesSummaryArgs
import app.mizan.domain.model.StockLookupArgs
import app.mizan.domain.policy.ApprovalRecord
import app.mizan.domain.policy.SodCode
import app.mizan.domain.security.AuthProof
import app.mizan.domain.security.Freshness
import app.mizan.feature.home.simpleFactory
import app.mizan.graph.AppGraph
import app.mizan.security.BiometricAuthModal
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
    private val aiHarness = MizanAiHarness()

    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
    var pending by mutableStateOf<Proposal?>(null)
    var secondApproverId by mutableStateOf<String?>(null)
    var needsProof by mutableStateOf(false)
    var offerSimulatedProof by mutableStateOf(false)

    fun submit() {
        val prompt = input.trim()
        if (prompt.isBlank() || busy) return
        input = ""
        val session = graph.session.session.value
        if (session == null) {
            add(AgentLine.Info(newId(), "SESSION_EXPIRED"))
            return
        }
        add(AgentLine.User(newId(), prompt))
        busy = true
        viewModelScope.launch {
            val customers = graph.readModels.customers(session.tenant.id).first()
            val capabilities = if (graph.demoMode) ConnectorCapabilities.simulation else ConnectorCapabilities.servicePreview
            val interpretation = aiHarness.parse(
                input = prompt,
                capabilities = capabilities,
                customPrompt = graph.preferences.customSystemPrompt,
                speedTier = graph.preferences.aiModelSpeedTier,
            )
            val parsed = graph.proposals.propose(
                interpreted = interpretation,
                intent = prompt,
                actor = session.actor,
                customers = customers,
            )
            busy = false
            when (parsed) {
                is ProposalResult.Proposed -> {
                    val proposal = parsed.proposal
                    if (proposal.policy.approval == ApprovalLevel.L0_NONE && !proposal.args.tool.destructive) {
                        val outcome = readLocal(proposal)
                        add(AgentLine.Result(newId(), "READ_LOCAL", outcome))
                    } else {
                        pending = proposal
                        add(AgentLine.ProposalLine(newId(), proposal))
                    }
                }
                is ProposalResult.Clarify -> add(AgentLine.Clarify(newId(), parsed.interpretation.missing))
                is ProposalResult.Unsupported -> add(AgentLine.Info(newId(), "TOOL_NOT_SUPPORTED"))
                is ProposalResult.Rejected -> add(AgentLine.Info(newId(), parsed.reasonCode))
            }
        }
    }

    fun dismiss() {
        pending = null
        needsProof = false
        offerSimulatedProof = false
        add(AgentLine.Info(newId(), "DISMISSED"))
    }

    fun clearChat() {
        _lines.value = emptyList()
        pending = null
        needsProof = false
        offerSimulatedProof = false
    }

    private suspend fun readLocal(proposal: Proposal): String {
        return when (val outcome = graph.authority.execute(
            ExecuteCommand(proposal = proposal, approver = proposal.initiator, proof = null),
        )) {
            is AuthorityOutcome.Verified -> outcome.erpRecordId
            is AuthorityOutcome.AcceptedUnverified -> outcome.messageCode
            else -> savedCopy(proposal)
        }
    }

    fun requestApproval() {
        val proposal = pending ?: return
        val session = graph.session.session.value ?: return
        val second = graph.simulation.actors(session.tenant.id).find { it.id.value == secondApproverId }
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
        offerSimulatedProof = false
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
            val second = graph.simulation.actors(session.tenant.id).find { it.id.value == secondApproverId }
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
                if (hit == null) "NOT_IN_SAVED_COPY ${args.sku}" else "${hit.name} · ${hit.availableQty}"
            }
            is CustomerSearchArgs -> {
                val hits = graph.readModels.customers(tenant).first()
                    .filter { it.name.contains(args.query, true) }
                if (hits.isEmpty()) "NOT_IN_SAVED_COPY" else hits.joinToString { it.name }
            }
            is SalesSummaryArgs -> {
                val orders = graph.readModels.orders(tenant).first()
                "Orders: ${orders.size}"
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
    val lines by vm.lines.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

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

    if (vm.offerSimulatedProof) {
        val proposal = vm.pending
        BiometricAuthModal(
            operationName = proposal?.args?.tool?.let { toolLabel(it) } ?: "ERP Execution",
            operationId = proposal?.executionId?.value ?: "SEC-AUTH",
            isSimulated = graph.demoMode,
            onConfirm = vm::useSimulatedProof,
            onDismiss = vm::clearChallenge,
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // Top Apple Glass Header
        AgentTopBar(
            demoMode = graph.demoMode,
            onClear = vm::clearChat,
            canClear = lines.isNotEmpty(),
        )

        // Chat conversation / Empty state
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (lines.isEmpty()) {
                AgentEmptyHero(
                    onSelectPrompt = { vm.input = it },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Space.md),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                    contentPadding = PaddingValues(vertical = Space.md),
                ) {
                    items(lines, key = { it.id }, contentType = { it::class.simpleName }) { line ->
                        when (line) {
                            is AgentLine.User -> ChatGPTUserBubble(line.text)
                            is AgentLine.Info -> ChatGPTInfoBlock(reasonLabel(line.code))
                            is AgentLine.Clarify -> ClarifyBlock(line.fields)
                            is AgentLine.ProposalLine -> ProposalBlock(
                                proposal = line.proposal,
                                demo = graph.demoMode,
                                secondId = vm.secondApproverId,
                                actors = graph.session.session.value?.let { graph.simulation.actors(it.tenant.id) }.orEmpty(),
                                onSecond = { vm.secondApproverId = it },
                                onReview = vm::requestApproval,
                                onDismiss = vm::dismiss,
                            )
                            is AgentLine.Result -> ResultBlock(line)
                        }
                    }
                }
            }
        }

        // Bottom ChatGPT-Style Floating Dock
        ChatGPTFloatingDock(
            input = vm.input,
            onInputChange = { vm.input = it },
            onSubmit = vm::submit,
            busy = vm.busy,
            showSuggestions = lines.isEmpty(),
            onSelectSuggestion = { vm.input = it },
        )
    }
}

@Composable
private fun AgentTopBar(
    demoMode: Boolean,
    onClear: () -> Unit,
    canClear: Boolean,
) {
    val colors = LocalMizanColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.glass)
            .border(BorderStroke(0.6.dp, colors.glassBorder))
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.accentMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(Space.sm))
            Column {
                Text(
                    text = "Mizan AI",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (demoMode) colors.warning else colors.success),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (demoMode) "Simulation" else "ERP Ready",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        if (canClear) {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Outlined.AddComment,
                    contentDescription = "New Chat",
                    tint = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun AgentEmptyHero(
    onSelectPrompt: (String) -> Unit,
) {
    val colors = LocalMizanColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Robot-Scale Emblem (ميزان وروبوت في نفس الوقت)
        MizanRobotScale(
            size = 115.dp,
            state = RobotScaleState.IDLE_BALANCED,
            interactive = true,
        )

        Spacer(Modifier.height(Space.md))

        Text(
            text = "Mizan Intelligence",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Space.xs))

        Text(
            text = "Command ERP operations, check stock, or draft orders.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Space.lg))

        // Quick suggestions
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.sm),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp),
        ) {
            val stockExample = stringResource(R.string.suggest_stock)
            val draftExample = stringResource(R.string.suggest_draft)

            PromptChip(
                icon = Icons.Outlined.Inventory2,
                title = "Check Inventory",
                subtitle = stockExample,
                onClick = { onSelectPrompt(stockExample) },
            )
            PromptChip(
                icon = Icons.Outlined.ReceiptLong,
                title = "Create Draft Order",
                subtitle = draftExample,
                onClick = { onSelectPrompt(draftExample) },
            )
        }
    }
}

@Composable
private fun PromptChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors = LocalMizanColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeCard)
            .background(colors.glass)
            .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(colors.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(Space.sm))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, maxLines = 1)
        }
    }
}

@Composable
private fun ChatGPTUserBubble(text: String) {
    val colors = LocalMizanColors.current
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(ShapeBubbleUser)
                .background(colors.userBubble)
                .border(BorderStroke(0.5.dp, colors.glassBorder), ShapeBubbleUser)
                .padding(horizontal = Space.md, vertical = 10.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onUserBubble,
            )
        }
    }
}

@Composable
private fun ChatGPTInfoBlock(text: String) {
    val colors = LocalMizanColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(colors.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(Space.sm))
        Box(
            modifier = Modifier
                .clip(ShapeBubbleAgent)
                .background(colors.glass)
                .border(BorderStroke(0.7.dp, colors.glassBorder), ShapeBubbleAgent)
                .padding(horizontal = Space.md, vertical = 8.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}

@Composable
private fun ClarifyBlock(fields: List<MissingField>) {
    val colors = LocalMizanColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(colors.warningContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.HelpOutline, contentDescription = null, tint = colors.warning, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(Space.sm))
        Column(
            modifier = Modifier
                .clip(ShapeBubbleAgent)
                .background(colors.glass)
                .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeBubbleAgent)
                .padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text(stringResource(R.string.agent_clarify), style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
            fields.distinct().forEach { field ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(colors.warning))
                    Spacer(Modifier.width(6.dp))
                    Text(missingLabel(field), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
            }
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

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (destructive) colors.dangerContainer else colors.accentMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (destructive) Icons.Outlined.WarningAmber else Icons.Outlined.ReceiptLong,
                contentDescription = null,
                tint = if (destructive) colors.danger else colors.accent,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(Space.sm))
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(ShapeCard)
                .background(colors.glass)
                .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                .padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            // Header: Tool name + Biometric security badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = toolLabel(proposal.args.tool),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Fingerprint,
                        contentDescription = "Biometric Security",
                        tint = colors.accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    MizanStatusBadge(
                        label = if (demo) "Simulated" else "Biometric Protected",
                        tone = if (demo) StatusTone.Warning else StatusTone.Success,
                    )
                }
            }

            // Compact details
            proposal.amount?.let { money ->
                Text(
                    text = money.format(java.util.Locale.getDefault()),
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = MizanMono),
                    fontWeight = FontWeight.Bold,
                    color = colors.accent,
                )
            }

            (proposal.args as? CreateDraftOrderArgs)?.let { args ->
                Text(
                    text = "${args.customerName} · ${args.itemsSummary}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }

            Text(
                text = "Initiated by ${proposal.initiator.displayName} · ${reasonLabel(proposal.policy.reasonCode)}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )

            // Second approver row if required
            if (proposal.policy.approval == ApprovalLevel.L4_DUAL && demo) {
                Text(stringResource(R.string.approve_need_second), color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                actors.filter { it.id != proposal.initiator.id && it.role != app.mizan.domain.model.Role.AUDITOR && it.role != app.mizan.domain.model.Role.SALES_REP }
                    .forEach { actor ->
                        MizanSecondaryButton(
                            text = actor.displayName + if (actor.id.value == secondId) " ✓" else "",
                            onClick = { onSecond(actor.id.value) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
            }

            Spacer(Modifier.height(Space.xs))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                if (destructive) {
                    MizanDangerButton(
                        text = stringResource(R.string.approve_cancel_order),
                        onClick = onReview,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    MizanPrimaryButton(
                        text = stringResource(R.string.approve_execute),
                        onClick = onReview,
                        modifier = Modifier.weight(1f),
                    )
                }
                MizanSecondaryButton(
                    text = stringResource(R.string.proposal_dismiss),
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun ResultBlock(line: AgentLine.Result) {
    val colors = LocalMizanColors.current
    val (label, tone) = when (line.code) {
        "SIM_CHECKED" -> "Verified in Simulation" to StatusTone.Warning
        "ERP_CHECKED" -> "Verified in ERP" to StatusTone.Success
        "ACCEPTED" -> "ERP Accepted" to StatusTone.Info
        "UNCERTAIN" -> "Outcome Uncertain" to StatusTone.Warning
        "READ_LOCAL" -> "Retrieved from Cache" to StatusTone.Info
        else -> "Refused: ${reasonLabel(line.code)}" to StatusTone.Danger
    }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (tone == StatusTone.Success) colors.successContainer else colors.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (tone == StatusTone.Success) Icons.Outlined.CheckCircle else Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = if (tone == StatusTone.Success) colors.success else colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(Space.sm))
        Column(
            modifier = Modifier
                .clip(ShapeBubbleAgent)
                .background(colors.glass)
                .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeBubbleAgent)
                .padding(horizontal = Space.md, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MizanStatusBadge(label, tone)
            line.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MizanMono),
                    color = colors.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun ChatGPTFloatingDock(
    input: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    busy: Boolean,
    showSuggestions: Boolean,
    onSelectSuggestion: (String) -> Unit,
) {
    val colors = LocalMizanColors.current
    val canSend = input.isNotBlank() && !busy

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.glass)
            .border(BorderStroke(0.8.dp, colors.glassBorder), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        // Quick suggestion chips horizontally scrollable
        if (showSuggestions) {
            val stockExample = stringResource(R.string.suggest_stock)
            val draftExample = stringResource(R.string.suggest_draft)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                item {
                    CompactChip(
                        icon = Icons.Outlined.Inventory2,
                        text = "Check Stock",
                        onClick = { onSelectSuggestion(stockExample) },
                    )
                }
                item {
                    CompactChip(
                        icon = Icons.Outlined.ReceiptLong,
                        text = "Draft Order",
                        onClick = { onSelectSuggestion(draftExample) },
                    )
                }
            }
        }

        // ChatGPT Style Capsule Input Dock
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapePill)
                .background(colors.surfaceElevated.copy(alpha = 0.85f))
                .border(BorderStroke(0.8.dp, colors.glassBorder), ShapePill)
                .padding(horizontal = Space.sm, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }

            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                enabled = !busy,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() }),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .padding(horizontal = Space.sm),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (input.isEmpty()) {
                            Text(
                                text = "Ask Mizan or command ERP...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textTertiary,
                            )
                        }
                        inner()
                    }
                },
            )

            // Send circular action button
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (canSend) colors.accent else colors.border)
                    .clickable(enabled = canSend, role = Role.Button, onClick = onSubmit),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.onAccent,
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) colors.onAccent else colors.textTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    val colors = LocalMizanColors.current
    Row(
        modifier = Modifier
            .clip(ShapePill)
            .background(colors.surfaceElevated)
            .border(BorderStroke(0.6.dp, colors.glassBorder), ShapePill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = colors.textPrimary)
    }
}
