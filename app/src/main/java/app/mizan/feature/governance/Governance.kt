package app.mizan.feature.governance

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
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mizan.R
import app.mizan.design.component.mizanGlassPane
import app.mizan.design.component.MizanSectionHeader
import app.mizan.design.component.MizanStatusBadge
import app.mizan.design.component.ShapeCard
import app.mizan.design.component.ShapeControl
import app.mizan.design.component.ShapePill
import app.mizan.design.component.StatusTone
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.token.Space
import app.mizan.graph.AppGraph

@Composable
fun GovernanceRoute(graph: AppGraph) {
    val colors = LocalMizanColors.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        // Governance Hero Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .mizanGlassPane(ShapeCard)
                .padding(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(ShapeControl)
                    .background(colors.accentMuted)
                    .border(BorderStroke(0.6.dp, colors.accent.copy(alpha = 0.3f)), ShapeControl),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Gavel,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.gov_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Text(
                    text = stringResource(R.string.gov_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }

        if (graph.demoMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeControl)
                    .background(colors.warning.copy(alpha = 0.1f))
                    .border(BorderStroke(0.6.dp, colors.warning.copy(alpha = 0.3f)), ShapeControl)
                    .padding(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = colors.warning, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.sm))
                Text(
                    text = stringResource(R.string.gov_sim_thresholds),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.warning,
                )
            }
        }

        MizanSectionHeader("Enforced Policy Rules")

        // Rule Cards with Expandable Details
        GovernanceRuleCard(
            id = "POL-SAFE-READ",
            title = stringResource(R.string.gov_reads),
            levelLabel = "L1 · READ ONLY",
            tone = StatusTone.Success,
            icon = Icons.Outlined.CheckCircle,
            details = "Zero mutation guarantee. Immediate execution allowed without dual biometric sign-off.",
        )

        GovernanceRuleCard(
            id = "POL-AUDITOR-READONLY",
            title = stringResource(R.string.gov_auditor),
            levelLabel = "L2 · AUDITOR ACCESS",
            tone = StatusTone.Info,
            icon = Icons.Outlined.Shield,
            details = "Restricted read-only access for compliance auditors to inspect ledgers and trace chains.",
        )

        GovernanceRuleCard(
            id = "POL-DESTRUCTIVE",
            title = stringResource(R.string.gov_destructive),
            levelLabel = "L3 · MUTATING WRITE",
            tone = StatusTone.Warning,
            icon = Icons.Outlined.WarningAmber,
            details = "Guards ERP ledger updates. Requires verified identity attestation and policy engine validation.",
        )

        GovernanceRuleCard(
            id = "POL-THRESHOLD-L4",
            title = stringResource(R.string.gov_dual),
            levelLabel = "L4 · DUAL APPROVAL",
            tone = StatusTone.Accent,
            icon = Icons.Outlined.Lock,
            details = "Requires dual cryptographic approvals (CFO + Controller) when purchase orders exceed threshold values.",
        )

        GovernanceRuleCard(
            id = "POL-CURRENCY-UNCONFIGURED",
            title = stringResource(R.string.gov_currency),
            levelLabel = "L5 · CURRENCY LOCK",
            tone = StatusTone.Danger,
            icon = Icons.Outlined.WarningAmber,
            details = "Blocks any foreign currency transaction without calibrated FX rate binding.",
        )

        Spacer(Modifier.height(Space.xl))
    }
}

@Composable
private fun GovernanceRuleCard(
    id: String,
    title: String,
    levelLabel: String,
    tone: StatusTone,
    icon: ImageVector,
    details: String,
) {
    val colors = LocalMizanColors.current
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (colors.isDark) 0.dp else 2.dp,
                shape = ShapeCard,
                spotColor = Color(0x0D0F172A),
                ambientColor = Color(0x050F172A),
            )
            .clip(ShapeCard)
            .background(
                if (colors.isDark) SolidColor(colors.glass) else Brush.verticalGradient(
                    listOf(Color(0xFAFFFFFF), Color(0xEDFFFFFF)),
                ),
            )
            .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
            .clickable(role = Role.Button) { expanded = !expanded }
            .padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(ShapeControl)
                    .background(colors.accentMuted)
                    .border(BorderStroke(0.6.dp, colors.accent.copy(alpha = 0.3f)), ShapeControl),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(Space.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    text = id,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = app.mizan.design.theme.MizanMono,
                    color = colors.textTertiary,
                )
            }
            Spacer(Modifier.width(Space.xs))
            MizanStatusBadge(levelLabel, tone)
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Space.xs)
                    .clip(ShapeControl)
                    .background(colors.surfaceElevated)
                    .border(BorderStroke(0.6.dp, colors.border), ShapeControl)
                    .padding(Space.sm),
            ) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}
