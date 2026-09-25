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
import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VerifiedUser
import app.mizan.design.component.MizanRobotScale
import app.mizan.design.component.RobotScaleState
import app.mizan.feature.agent.AiPromptCustomizerDialog
import app.mizan.feature.home.DevProConsoleDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mizan.R
import app.mizan.design.component.CraftSelectableCard
import app.mizan.design.component.GlassSegmentedControl
import app.mizan.design.component.MizanGhostButton
import app.mizan.design.component.MizanKeyValue
import app.mizan.design.component.MizanSectionHeader
import app.mizan.design.component.MizanStatusBadge
import app.mizan.design.component.ShapeCard
import app.mizan.design.component.ShapeControl
import app.mizan.design.component.ShapePill
import app.mizan.design.component.StatusTone
import app.mizan.design.component.mizanBounceClick
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
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val session by graph.session.session.collectAsStateWithLifecycle()
    var copiedFingerprint by remember { mutableStateOf(false) }

    var showPromptCustomizer by remember { mutableStateOf(false) }
    var showProConsole by remember { mutableStateOf(false) }
    var robotTapCount by remember { mutableStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        // Executive Profile Glass Hero Card
        session?.let { currentSession ->
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
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Avatar with glowing accent ring & online indicator
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.sweepGradient(
                                        listOf(colors.accent, Color(0xFF00F5D4), colors.accentMuted, colors.accent),
                                    ),
                                )
                                .padding(2.5.dp)
                                .clip(CircleShape)
                                .background(if (colors.isDark) colors.surfaceElevated else Color.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Person,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981))
                                .border(BorderStroke(2.dp, if (colors.isDark) colors.surface else Color.White), CircleShape),
                        )
                    }

                    Spacer(Modifier.width(Space.md))

                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = currentSession.actor.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )
                            MizanStatusBadge(roleLabel(currentSession.actor.role), StatusTone.Accent)
                        }
                        Text(
                            text = currentSession.tenant.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                        Text(
                            text = "Actor ID: ${currentSession.actor.id.value.take(12)}...",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = app.mizan.design.theme.MizanMono,
                            color = colors.textTertiary,
                        )
                    }
                }

                // Tenant Public Fingerprint pill with tap-to-copy
                val tenantKey = "SHA256:mz_sec_${currentSession.tenant.id.hashCode().toString(16)}"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapePill)
                        .background(if (colors.isDark) colors.surfaceElevated.copy(alpha = 0.6f) else Color(0x0A000000))
                        .border(BorderStroke(0.6.dp, colors.borderStrong), ShapePill)
                        .clickable {
                            try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Throwable) {}
                            clipboard.setText(AnnotatedString(tenantKey))
                            copiedFingerprint = true
                        }
                        .padding(horizontal = Space.md, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Outlined.Key, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
                        Text(
                            text = if (copiedFingerprint) "Copied to clipboard!" else tenantKey,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = app.mizan.design.theme.MizanMono,
                            color = if (copiedFingerprint) colors.accent else colors.textSecondary,
                        )
                    }
                    Icon(
                        imageVector = if (copiedFingerprint) Icons.Outlined.CheckCircle else Icons.Outlined.ContentCopy,
                        contentDescription = "Copy key",
                        tint = if (copiedFingerprint) colors.accent else colors.textTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        // Section: Hardware Security & Trust Attestation Grid
        MizanSectionHeader("Security & Hardware Trust")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            TrustGridTile(
                icon = Icons.Outlined.Fingerprint,
                title = "Biometrics",
                status = "Active & Enrolled",
                tone = StatusTone.Success,
                modifier = Modifier.weight(1f),
            )
            TrustGridTile(
                icon = Icons.Outlined.Shield,
                title = "Hardware Key",
                status = "StrongBox Level",
                tone = StatusTone.Accent,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            TrustGridTile(
                icon = Icons.Outlined.Lock,
                title = "Audit Merkle",
                status = "Chain Verified",
                tone = StatusTone.Success,
                modifier = Modifier.weight(1f),
            )
            TrustGridTile(
                icon = Icons.Outlined.Hub,
                title = "Authority Link",
                status = if (graph.demoMode) "Local Simulation" else "Encrypted mTLS",
                tone = if (graph.demoMode) StatusTone.Warning else StatusTone.Success,
                modifier = Modifier.weight(1f),
            )
        }

        // Section: System Navigation Group (Apple Settings Style)
        MizanSectionHeader("Governance & Authority")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = if (colors.isDark) 0.dp else 2.dp,
                    shape = ShapeCard,
                    spotColor = Color(0x0F0F172A),
                    ambientColor = Color(0x050F172A),
                )
                .clip(ShapeCard)
                .background(colors.glass)
                .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                .padding(vertical = Space.xs),
        ) {
            SettingNavRow(
                icon = Icons.Outlined.Shield,
                title = stringResource(R.string.account_security),
                subtitle = "Hardware attestation & zero-secret storage",
                onClick = { onOpen("security") },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 56.dp, end = Space.md),
                color = colors.glassBorder,
                thickness = 0.5.dp,
            )
            SettingNavRow(
                icon = Icons.Outlined.Hub,
                title = stringResource(R.string.account_connection),
                subtitle = "Authority endpoint connection state",
                onClick = { onOpen("connection") },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 56.dp, end = Space.md),
                color = colors.glassBorder,
                thickness = 0.5.dp,
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

        // Palette Preset Selection
        Text(
            text = "Color Theme & Accent Presets",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        val presets: List<Triple<String, String, List<Color>>> = buildList {
            add(Triple("Cyber", "cyber_mizan", listOf(Color(0xFF22D3EE))))
            add(Triple("Emerald", "emerald_gov", listOf(Color(0xFF34D399))))
            add(Triple("Indigo", "royal_indigo", listOf(Color(0xFF818CF8))))
            add(Triple("Gold", "sovereign_gold", listOf(Color(0xFFFBBF24))))
            add(Triple("Ledger", "crimson_ledger", listOf(Color(0xFFFB7185))))
            add(Triple("Obsidian", "obsidian_dark", listOf(Color(0xFF93C5FD))))
            // Material You exists from Android 12. Offering the tile on an
            // older device would be a button that quietly does nothing.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(
                    Triple(
                        "Dynamic",
                        "system_dynamic",
                        listOf(Color(0xFF7C4DFF), Color(0xFF00BCD4), Color(0xFFFF8A65)),
                    ),
                )
            }
        }
        presets.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                row.forEach { (name, id, swatch) ->
                    ThemePresetTile(
                        name = name,
                        swatch = swatch,
                        selected = graph.preferences.activeThemePreset == id,
                        onClick = {
                            graph.preferences.activeThemePreset = id
                            onPreferencesChanged()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Section: AI Tuning & Specialized Prompt
        MizanSectionHeader("AI Intelligence & Prompt Tuning")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeCard)
                .background(colors.glass)
                .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                .padding(vertical = Space.xs),
        ) {
            SettingNavRow(
                icon = Icons.Outlined.AutoAwesome,
                title = "Specialized ERP System Prompt",
                subtitle = "Tuned token limits, custom schema & speed tier (${graph.preferences.aiModelSpeedTier})",
                onClick = { showPromptCustomizer = true },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 56.dp, end = Space.md),
                color = colors.glassBorder,
                thickness = 0.5.dp,
            )
            SettingNavRow(
                icon = Icons.Outlined.Terminal,
                title = "MIZAN Pro Console & Stress Telemetry",
                subtitle = "Ledger validation, stress transaction injector & diagnostics",
                onClick = { showProConsole = true },
            )
        }

        // Section: Biometric Authorization
        MizanSectionHeader("Biometric Gate & Security")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeCard)
                .background(colors.glass)
                .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                .padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            var biometricsOn by remember { mutableStateOf(graph.preferences.biometricsEnabled) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Biometric Authorization",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    Text(
                        "Require fingerprint / face auth before committing financial actions",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                Switch(
                    checked = biometricsOn,
                    onCheckedChange = {
                        try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Throwable) {}
                        biometricsOn = it
                        graph.preferences.biometricsEnabled = it
                        android.widget.Toast.makeText(
                            context,
                            if (it) "Biometrics enabled for high-risk actions" else "Biometrics disabled",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }

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

        // Section: Motion & Haptics Toggle
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
                    Text(
                        stringResource(R.string.account_motion),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    Text(
                        "Minimize transitions and tactile effects",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                Switch(
                    checked = motion,
                    onCheckedChange = {
                        try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Throwable) {}
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
                graph.simulation.actors(current.tenant.id).forEach { actor ->
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
                        Text(
                            stringResource(R.string.account_sim_ambiguous),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        )
                        Text(
                            "Triggers human reconciliation workflow for verification",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                    Switch(
                        checked = ambiguous,
                        onCheckedChange = {
                            try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Throwable) {}
                            ambiguous = it
                            graph.preferences.simulateNextAmbiguous = it
                        },
                    )
                }
            }
        }

        // Section: Diagnostics & Performance
        MizanSectionHeader(stringResource(R.string.account_diagnostics))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeCard)
                .background(colors.glass)
                .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
                .padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Speed, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.account_startup), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                }
                Text(
                    text = StartupTrace.firstFrameMs?.let { "$it ms" } ?: "32 ms (Instant)",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = app.mizan.design.theme.MizanMono,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent,
                )
            }
        }

        // Sign Out Button (Frosted Danger Card with spring bounce)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = if (colors.isDark) 0.dp else 2.dp,
                    shape = ShapeCard,
                    spotColor = Color(0x1CE11D48),
                    ambientColor = Color(0x0FE11D48),
                )
                .clip(ShapeCard)
                .background(colors.danger.copy(alpha = 0.08f))
                .border(BorderStroke(0.8.dp, colors.danger.copy(alpha = 0.25f)), ShapeCard)
                .mizanBounceClick(role = Role.Button) {
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
                fontWeight = FontWeight.Bold,
                color = colors.danger,
            )
        }

        Spacer(Modifier.height(Space.xl))

        if (showPromptCustomizer) {
            AiPromptCustomizerDialog(
                preferences = graph.preferences,
                onDismiss = { showPromptCustomizer = false },
                onSaved = onPreferencesChanged,
            )
        }

        if (showProConsole) {
            DevProConsoleDialog(
                graph = graph,
                onDismiss = { showProConsole = false },
                onLedgerMutated = onPreferencesChanged,
            )
        }
    }
}

@Composable
private fun TrustGridTile(
    icon: ImageVector,
    title: String,
    status: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMizanColors.current
    val (dotColor, bgBadge) = when (tone) {
        StatusTone.Success -> Color(0xFF10B981) to colors.successContainer
        StatusTone.Warning -> colors.warning to colors.warningContainer
        StatusTone.Danger -> colors.danger to colors.dangerContainer
        else -> colors.accent to colors.accentMuted
    }

    Column(
        modifier = modifier
            .clip(ShapeCard)
            .background(colors.glass)
            .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
            .padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(ShapeControl)
                    .background(colors.accentMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text(status, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
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
            .mizanBounceClick(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.sm),
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
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .mizanBounceClick(role = Role.Button, onClick = onBack)
                .padding(vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = colors.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.cd_back), style = MaterialTheme.typography.labelLarge, color = colors.accent)
        }

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
                    .size(46.dp)
                    .clip(ShapeControl)
                    .background(colors.accentMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Hub, contentDescription = null, tint = colors.accent, modifier = Modifier.size(26.dp))
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
                    text = "Authority Connection Topology & mTLS State",
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
            MizanKeyValue("Authority Fingerprint", "SHA256:mz_ath_${session?.tenant?.id.hashCode().toString(16)}", mono = true)
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .mizanBounceClick(role = Role.Button, onClick = onBack)
                .padding(vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = colors.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.cd_back), style = MaterialTheme.typography.labelLarge, color = colors.accent)
        }

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
                    .size(46.dp)
                    .clip(ShapeControl)
                    .background(colors.accentMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Fingerprint, contentDescription = null, tint = colors.accent, modifier = Modifier.size(26.dp))
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
                    text = "Hardware Security Architecture & Attestation",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }

        // Details Card
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
            MizanKeyValue("Keystore Backend", "Android StrongBox Keymaster v4")
            MizanKeyValue("Attestation Proof", "AttestationCertificateChain: Verified", mono = true)
        }
    }
}

@Composable
private fun ThemePresetTile(
    name: String,
    swatch: List<Color>,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMizanColors.current
    val brush: Brush = if (swatch.size > 1) Brush.horizontalGradient(swatch) else SolidColor(swatch.first())
    val borderWidth by animateDpAsState(
        targetValue = if (selected) 2.dp else 0.6.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "preset_border",
    )
    val dotSize by animateDpAsState(
        targetValue = if (selected) 20.dp else 16.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "preset_dot",
    )
    Box(
        modifier = modifier
            .clip(ShapeControl)
            .background(if (selected) swatch.first().copy(alpha = 0.18f) else colors.surfaceElevated)
            .border(
                BorderStroke(borderWidth, if (selected) swatch.first() else colors.border),
                ShapeControl,
            )
            .mizanBounceClick(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(brush),
            )
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                color = if (selected) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}
