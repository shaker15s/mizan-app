package app.mizan.feature.home

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mizan.design.component.MizanKeyValue
import app.mizan.design.component.MizanPrimaryButton
import app.mizan.design.component.WakeelMascot
import app.mizan.design.component.MizanSecondaryButton
import app.mizan.design.component.MascotState
import app.mizan.design.component.ShapeCard
import app.mizan.design.component.ShapeControl
import app.mizan.design.component.ShapePill
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.theme.MizanMono
import app.mizan.design.token.Space
import app.mizan.domain.audit.AuditAppend
import app.mizan.domain.execution.ExecutionPhase
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.CancelOrderArgs
import app.mizan.domain.model.CreateDraftOrderArgs
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.ExecutionRecord
import app.mizan.domain.model.Money
import app.mizan.domain.model.RegisterPaymentArgs
import app.mizan.domain.model.StockLookupArgs
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.ToolName
import app.mizan.domain.model.TraceId
import app.mizan.graph.AppGraph
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

@Composable
fun DevProConsoleDialog(
    graph: AppGraph,
    onDismiss: () -> Unit,
    onLedgerMutated: () -> Unit,
) {
    val colors = LocalMizanColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isSeeding by remember { mutableStateOf(false) }
    var proModeEnabled by remember { mutableStateOf(graph.preferences.developerProMode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceElevated,
        modifier = Modifier.testTag("dev_pro_console_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(ShapeControl)
                        .background(colors.accentMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column {
                    Text(
                        text = "Wakeel Pro Master Console",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Hidden developer telemetry & stress tools",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                // Interactive miniature scale
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapeControl)
                        .background(colors.surface)
                        .border(BorderStroke(0.6.dp, colors.border), ShapeControl)
                        .padding(Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    WakeelMascot(size = 54.dp, state = MascotState.IDLE_BALANCED)
                    Column(modifier = Modifier.padding(end = Space.md)) {
                        Text(
                            text = "Wakeel Hybrid Scale Engine",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.accent,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "SHA-256 Merkle Ledger Active",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = colors.textSecondary,
                        )
                    }
                }

                // Pro Mode Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapeControl)
                        .background(colors.surface)
                        .padding(Space.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "Developer Telemetry Overlay",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        )
                        Text(
                            text = "Expose raw hashes and network timings",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                    Switch(
                        checked = proModeEnabled,
                        onCheckedChange = {
                            proModeEnabled = it
                            graph.preferences.developerProMode = it
                        },
                    )
                }

                // Batch Generate Test ERP Records
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapeControl)
                        .background(colors.surface)
                        .border(BorderStroke(0.6.dp, colors.border), ShapeControl)
                        .padding(Space.md),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Text(
                        text = "Stress Simulation Seed:",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Inject 4 high-fidelity verified ERP transaction logs into the local ledger with valid SHA-256 hashes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    MizanPrimaryButton(
                        text = if (isSeeding) "Generating Ledger Entries..." else "Inject 4 Verified Transactions",
                        onClick = {
                            scope.launch {
                                isSeeding = true
                                val tenantId = TenantId("TNT-DEMO-2026")
                                val baseTime = Instant.now()

                                val stressRecords = listOf(
                                    Triple(
                                        ToolName.CREATE_DRAFT_ORDER,
                                        CreateDraftOrderArgs("Cairo Logistics Hub", Money.parseMajor("12400", "EGP")!!, "15 Industrial Forklifts"),
                                        "SO-2026-9044",
                                    ),
                                    Triple(
                                        ToolName.STOCK_AVAILABILITY,
                                        StockLookupArgs("SKU-SOLAR-INVERTER"),
                                        "STK-INV-500",
                                    ),
                                    Triple(
                                        ToolName.REGISTER_PAYMENT,
                                        RegisterPaymentArgs("INV-2026-3021", Money.parseMajor("3500", "USD")!!),
                                        "PAY-TX-9912",
                                    ),
                                    Triple(
                                        ToolName.CANCEL_ORDER,
                                        CancelOrderArgs("SO-2026-8812", "Duplicate entry on client request"),
                                        "REV-SO-8812",
                                    ),
                                )

                                for ((idx, item) in stressRecords.withIndex()) {
                                    val (tool, args, erpId) = item
                                    val execId = ExecutionId("EXE-STRESS-${UUID.randomUUID().toString().take(6).uppercase()}")
                                    val traceId = TraceId("TRC-STRESS-${UUID.randomUUID().toString().take(6).uppercase()}")
                                    val time = baseTime.minusSeconds((idx + 1) * 360L)

                                    graph.executions.upsert(
                                        ExecutionRecord(
                                            id = execId,
                                            traceId = traceId,
                                            proposalId = app.mizan.domain.model.ProposalId("PRP-STRESS-${UUID.randomUUID().toString().take(6).uppercase()}"),
                                            tenantId = tenantId,
                                            initiatorId = ActorId("act-sec-officer"),
                                            tool = tool,
                                            toolVersion = "1.0",
                                            intent = "Stress test $erpId",
                                            phase = ExecutionPhase.VERIFIED,
                                            idempotencyKey = app.mizan.domain.model.IdempotencyKey("IDEM-${UUID.randomUUID()}"),
                                            canonicalArgs = """{"tool":"${tool.wire}"}""",
                                            amount = null,
                                            approval = app.mizan.domain.model.ApprovalLevel.L0_NONE,
                                            riskTier = app.mizan.domain.model.RiskTier.R1_LOW,
                                            policyRuleId = "RULE-STRESS-PASS",
                                            approverIds = emptyList(),
                                            erpRecordId = erpId,
                                            erpModel = "account.move",
                                            dispatch = app.mizan.domain.error.DispatchState.SENT,
                                            leaseExpiresAt = null,
                                            createdAt = time,
                                            updatedAt = time,
                                            errorCode = null,
                                            origin = app.mizan.domain.model.EvidenceOrigin.SIMULATION,
                                        ),
                                    )

                                    graph.audit.append(
                                        AuditAppend(
                                            traceId = traceId.value,
                                            tenantId = tenantId,
                                            actorId = "act-sec-officer",
                                            action = "STRESS_EXEC_VERIFIED",
                                            stateBefore = "READY",
                                            stateAfter = "COMMITTED",
                                            details = """{"record":"$erpId","status":"VERIFIED"}""",
                                            timestampMillis = time.toEpochMilli(),
                                        ),
                                    )
                                }

                                isSeeding = false
                                Toast.makeText(context, "Injected 4 verified transactions successfully", Toast.LENGTH_SHORT).show()
                                onLedgerMutated()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("inject_stress_transactions_button"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = colors.accent, fontWeight = FontWeight.Bold)
            }
        },
    )
}
