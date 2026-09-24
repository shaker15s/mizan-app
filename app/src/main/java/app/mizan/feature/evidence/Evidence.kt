package app.mizan.feature.evidence

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mizan.R
import app.mizan.design.component.AuditTrailReceiptCard
import app.mizan.design.component.GlassSegmentedControl
import app.mizan.design.component.MizanEmptyState
import app.mizan.design.component.MizanGhostButton
import app.mizan.design.component.MizanKeyValue
import app.mizan.design.component.MizanPrimaryButton
import app.mizan.design.component.MizanSecondaryButton
import app.mizan.design.component.MizanSectionHeader
import app.mizan.design.component.MizanStatusBadge
import app.mizan.design.component.MizanSurface
import app.mizan.design.component.ShapeCard
import app.mizan.design.component.ShapeControl
import app.mizan.design.component.ShapePill
import app.mizan.design.component.StatusTone
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.token.Space
import app.mizan.domain.audit.ChainReport
import app.mizan.domain.model.EvidenceOrigin
import app.mizan.domain.model.TrustReceipt
import app.mizan.domain.model.VerificationKind
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
    var inspected: Int by mutableIntStateOf(0)

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
    val colors = LocalMizanColors.current

    var openReceiptId by remember { mutableStateOf<String?>(null) }
    var filterIndex by remember { mutableIntStateOf(0) }
    val filterOptions = listOf("All Receipts (${receipts.size})", "ERP Verified", "Simulations")

    val filteredReceipts = remember(receipts, filterIndex) {
        when (filterIndex) {
            1 -> receipts.filter { it.verification == VerificationKind.READ_BACK }
            2 -> receipts.filter { it.origin == EvidenceOrigin.SIMULATION }
            else -> receipts
        }
    }

    val selected = receipts.find { it.id.value == openReceiptId }

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
                    .size(42.dp)
                    .clip(ShapeControl)
                    .background(colors.accentMuted)
                    .border(BorderStroke(0.6.dp, colors.accent.copy(alpha = 0.3f)), ShapeControl),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.evidence_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Immutable Merkle Audit Ledger & Trust Receipts",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }

        // Ledger Verification Card
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
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Cryptographic Chain Integrity",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = stringResource(R.string.evidence_empty_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (vm.report?.intact == false) colors.danger else colors.accent),
                )
            }

            // Report Banner if available
            vm.report?.let { report ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapeControl)
                        .background(if (report.intact) colors.accentMuted else colors.danger.copy(alpha = 0.15f))
                        .border(
                            BorderStroke(0.6.dp, if (report.intact) colors.accent else colors.danger),
                            ShapeControl,
                        )
                        .padding(Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (report.intact) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = if (report.intact) colors.accent else colors.danger,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        text = chainLabel(report.messageCode),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (report.intact) colors.accent else colors.danger,
                    )
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                MizanPrimaryButton(
                    text = if (vm.checking) "Verifying Ledger..." else stringResource(R.string.evidence_check),
                    onClick = { vm.check(false) },
                    enabled = !vm.checking,
                    modifier = Modifier.weight(1f),
                )
            }

            if (graph.demoMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapeControl)
                        .background(colors.warning.copy(alpha = 0.08f))
                        .border(BorderStroke(0.6.dp, colors.warning.copy(alpha = 0.25f)), ShapeControl)
                        .padding(Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Tamper Simulation Test",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.warning,
                        )
                        Text(
                            text = stringResource(R.string.evidence_demo_tamper_note),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = colors.textSecondary,
                        )
                    }
                    Spacer(Modifier.width(Space.sm))
                    MizanGhostButton(stringResource(R.string.evidence_demo_tamper), onClick = { vm.check(true) })
                }
            }
        }

        // Segmented filter tabs
        GlassSegmentedControl(
            options = filterOptions,
            selectedIndex = filterIndex,
            onSelect = { filterIndex = it },
        )

        // Trust Receipts List
        MizanSectionHeader("Audited Trust Receipts")
        if (filteredReceipts.isEmpty()) {
            MizanEmptyState(
                stringResource(R.string.evidence_empty_title),
                stringResource(R.string.evidence_empty_body),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                filteredReceipts.forEach { receipt ->
                    AuditTrailReceiptCard(
                        toolName = toolLabel(receipt.tool),
                        intent = "ERP Receipt · ${toolLabel(receipt.tool)}",
                        phaseLabel = receipt.verification.name,
                        statusTone = StatusTone.Success,
                        receiptId = receipt.id.value,
                        erpRecordId = receipt.erpRecordId,
                        policyRule = receipt.policyRuleId,
                        traceId = receipt.id.value,
                        timestampFormatted = if (receipt.origin == EvidenceOrigin.SIMULATION) "Simulated Evidence" else "Machine Signed & Sealed",
                        isBiometricVerified = true,
                        isSimulation = receipt.origin == EvidenceOrigin.SIMULATION,
                        onClick = { openReceiptId = receipt.id.value },
                    )
                }
            }
        }

        // Expanded modal detail
        selected?.let { receipt ->
            Spacer(Modifier.height(Space.sm))
            ReceiptDetailCard(receipt) { openReceiptId = null }
        }

        Spacer(Modifier.height(Space.xl))
    }
}

@Composable
private fun ReceiptDetailCard(receipt: TrustReceipt, onClose: () -> Unit) {
    val colors = LocalMizanColors.current
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
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Receipt Inspector",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            MizanGhostButton("Close", onClick = onClose)
        }

        MizanKeyValue("Receipt ID", receipt.id.value, mono = true)
        MizanKeyValue(stringResource(R.string.evidence_policy), receipt.policyRuleId, mono = true)
        receipt.erpRecordId?.let {
            MizanKeyValue("ERP Record Binding", it, mono = true)
        }
        MizanKeyValue("Verification Level", receipt.verification.name)
        MizanKeyValue("Evidence Origin", receipt.origin.name)
    }
}
