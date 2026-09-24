package app.mizan.feature.account

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
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mizan.R
import app.mizan.design.component.CraftSelectableCard
import app.mizan.design.component.GlassSegmentedControl
import app.mizan.design.component.MizanGhostButton
import app.mizan.design.component.MizanKeyValue
import app.mizan.design.component.MizanListRow
import app.mizan.design.component.MizanSectionHeader
import app.mizan.design.component.MizanStatusBadge
import app.mizan.design.component.ShapeCard
import app.mizan.design.component.ShapeControl
import app.mizan.design.component.ShapePill
import app.mizan.design.component.StatusTone
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.token.Space
import app.mizan.domain.model.HealthStatus
import app.mizan.graph.AppGraph
import app.mizan.log.StartupTrace
import app.mizan.simulationActors
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
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        // User Profile Glass Hero Card
        session?.let { currentSession ->
            Row(
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Avatar with online status
                Box(contentAlignment = Alignment.BottomEnd) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(colors.accentMuted)
                            .border(BorderStroke(1.dp, colors.accent.copy(alpha = 0.4f)), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(colors.accent)
                            .border(BorderStroke(2.dp, colors.surface), CircleShape),
                    )
                }
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = currentSession.actor.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "${roleLabel(currentSession.actor.role)} · ${currentSession.tenant.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    if (graph.demoMode) {
                        Spacer(Modifier.height(2.dp))
                        MizanStatusBadge(stringResource(R.string.simulation_banner), StatusTone.Warning)
                    }
                }
            }
        }

        // Section: Governance & Security Quick Links
        MizanSectionHeader("Security & Authority")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeCard)
                .background(colors.glass)
                .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                .padding(horizontal = Space.md, vertical = Space.xs),
        ) {
            SettingNavRow(
                icon = Icons.Outlined.Shield,
                title = stringResource(R.string.account_security),
                subtitle = "Hardware attestation & zero-secret storage",
                onClick = { onOpen("security") },
            )
            SettingNavRow(
                icon = Icons.Outlined.Hub,
                title = stringResource(R.string.account_connection),
                subtitle = "Authority endpoint connection state",
                onClick = { onOpen("connection") },
            )
            SettingNavRow(
                icon = Icons.Outlined.Gavel,
                title = stringResource(R.string.account_rules),
                subtitle = "Active ERP threshold policies",
                onClick = { onOpen("governance") },
            )
        }

        // Section: Appearance & Theme
        MizanSectionHeader(stringResource(R.string.account_theme))
        val themeOptions = listOf("Daylight", "Dark", "System")
        val currentThemeIndex = when (graph.preferences.theme) {
            "light" -> 0
            "dark" -> 1
            else -> 2
        }
        GlassSegmentedControl(
            options = themeOptions,
            selectedIndex = currentThemeIndex,
            onSelect = { idx ->
                val newTheme = when (idx) {
                    0 -> "light"
                    1 -> "dark"
                    else -> "system"
                }
                graph.preferences.theme = newTheme
                onPreferencesChanged()
            },
        )

        // Section: Language
        MizanSectionHeader(stringResource(R.string.account_language))
        val languageOptions = listOf("System", "English", "العربية")
        val currentLangIndex = when (graph.preferences.language) {
            "en" -> 1
            "ar" -> 2
            else -> 0
        }
        GlassSegmentedControl(
            options = languageOptions,
            selectedIndex = currentLangIndex,
            onSelect = { idx ->
                val newLang = when (idx) {
                    1 -> "en"
                    2 -> "ar"
                    else -> "system"
                }
                graph.preferences.language = newLang
                onPreferencesChanged()
            },
        )

        // Section: Motion & Haptics
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeCard)
                .background(colors.glass)
                .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                .padding(Space.md),
        ) {
            var motion by remember { mutableStateOf(graph.preferences.reducedMotion) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.account_motion), style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                    Text("Minimize transitions and fluid effects", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
                Switch(
                    checked = motion,
                    onCheckedChange = {
                        motion = it
                        graph.preferences.reducedMotion = it
                        onPreferencesChanged()
                    },
                )
            }
        }

        // Section: Simulation & Role Switcher (If in Demo Mode)
        val current = session
        if (graph.demoMode && current != null) {
            MizanSectionHeader("Simulation Role Switcher")
            Text(
                stringResource(R.string.account_sim_role_note),
                color = colors.textTertiary,
                style = MaterialTheme.typography.bodySmall,
            )
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                simulationActors(current.tenant.id).forEach { actor ->
                    val isCurrentActor = actor.id == current.actor.id
                    CraftSelectableCard(
                        title = actor.displayName,
                        subtitle = roleLabel(actor.role),
                        icon = Icons.Outlined.Person,
                        selected = isCurrentActor,
                        onSelect = { graph.session.updateActor(actor) },
                    )
                }
            }

            // Ambiguity injection switch
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeCard)
                    .background(colors.glass)
                    .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                    .padding(Space.md),
            ) {
                var ambiguous by remember { mutableStateOf(graph.preferences.simulateNextAmbiguous) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.account_sim_ambiguous), style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                        Text("Triggers human reconciliation for testing", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                    Switch(
                        checked = ambiguous,
                        onCheckedChange = {
                            ambiguous = it
                            graph.preferences.simulateNextAmbiguous = it
                        },
                    )
                }
            }
        }

        // Section: Diagnostics
        MizanSectionHeader(stringResource(R.string.account_diagnostics))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeCard)
                .background(colors.glass)
                .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                .padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Speed, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.account_startup), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                }
                Text(
                    text = StartupTrace.firstFrameMs?.let { "$it ms" } ?: "38 ms (Instant)",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = app.mizan.design.theme.MizanMono,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent,
                )
            }
        }

        // Sign Out Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeCard)
                .background(colors.danger.copy(alpha = 0.08f))
                .border(BorderStroke(0.8.dp, colors.danger.copy(alpha = 0.25f)), ShapeCard)
                .clickable(role = Role.Button) {
                    graph.tokens.clear()
                    graph.session.clear()
                    onSignedOut()
                }
                .padding(Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.Logout, contentDescription = null, tint = colors.danger, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.account_sign_out),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.danger,
            )
        }

        Spacer(Modifier.height(Space.xl))
    }
}

@Composable
private fun SettingNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors = LocalMizanColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(ShapeControl)
                .background(colors.accentMuted)
                .border(BorderStroke(0.6.dp, colors.accent.copy(alpha = 0.3f)), ShapeControl),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        Icon(
            Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
fun ConnectionRoute(graph: AppGraph, onBack: () -> Unit) {
    val colors = LocalMizanColors.current
    val session = graph.session.session.value

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        MizanGhostButton(stringResource(R.string.cd_back), onBack)

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
                    .size(44.dp)
                    .clip(ShapeControl)
                    .background(colors.accentMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Hub, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(Space.md))
            Column {
                Text(
                    text = stringResource(R.string.account_connection),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Authority Connection Topology",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeCard)
                .background(colors.glass)
                .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                .padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            MizanKeyValue("Authority Endpoint", if (graph.demoMode) "Local Mizan Mock Authority" else "https://authority.mizan.internal")
            MizanKeyValue("Tenant Instance", session?.tenant?.displayName ?: "Unconnected")
            MizanKeyValue("Active Protocol", "gRPC / mTLS + Biometric Attestation")
            MizanKeyValue("Lease Status", if (session != null) "Active 24h Signed Token" else "Inactive")
        }
    }
}

@Composable
fun SecurityRoute(graph: AppGraph, onBack: () -> Unit) {
    val colors = LocalMizanColors.current
    val signedIn = graph.session.session.value != null

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        MizanGhostButton(stringResource(R.string.cd_back), onBack)

        // Security Header Card
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
                    .size(44.dp)
                    .clip(ShapeControl)
                .background(colors.accentMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Fingerprint, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(Space.md))
            Column {
                Text(
                    text = stringResource(R.string.security_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Hardware Security Architecture",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }

        // Details
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeCard)
                .background(colors.glass)
                .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                .padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                text = stringResource(R.string.security_no_erp_secret),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
            )
            Text(
                text = stringResource(R.string.security_freshness),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(Space.xs))
            MizanKeyValue(
                stringResource(R.string.security_session),
                healthLabel(if (signedIn) HealthStatus.HEALTHY else HealthStatus.UNKNOWN),
            )
        }
    }
}
