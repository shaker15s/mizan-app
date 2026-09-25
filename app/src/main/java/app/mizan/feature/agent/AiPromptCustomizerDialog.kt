package app.mizan.feature.agent

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mizan.design.component.MizanPrimaryButton
import app.mizan.design.component.ShapeCard
import app.mizan.design.component.ShapeControl
import app.mizan.design.component.ShapePill
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.theme.MizanMono
import app.mizan.design.token.Space
import app.mizan.prefs.UserPreferences

@Composable
fun AiPromptCustomizerDialog(
    preferences: UserPreferences,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val colors = LocalMizanColors.current
    val context = LocalContext.current

    var currentPrompt by remember { mutableStateOf(preferences.customSystemPrompt) }
    var selectedTier by remember { mutableStateOf(preferences.aiModelSpeedTier) }

    val estimatedTokens = remember(currentPrompt) {
        // Approximate token counting (~4 characters per token for English, ~2 for Arabic/technical)
        (currentPrompt.length / 3.5).toInt().coerceAtLeast(10)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceElevated,
        modifier = Modifier.testTag("ai_prompt_customizer_dialog"),
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
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column {
                    Text(
                        text = "AI System Prompt & Harness",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Specialized ERP model tuning",
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
                // Token Efficiency Badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ShapePill)
                        .background(colors.accentMuted)
                        .padding(horizontal = Space.md, vertical = Space.xs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Speed,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Token Footprint:",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = "~$estimatedTokens tokens (Ultra-lean)",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent,
                        fontFamily = MizanMono,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Preset selector pills
                Text(
                    text = "Quick Presets:",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    PromptPresetPill(
                        label = "Concise Turbo",
                        active = currentPrompt == MizanAiHarness.SYSTEM_PROMPT_CONCISE_ERP,
                        onClick = {
                            currentPrompt = MizanAiHarness.SYSTEM_PROMPT_CONCISE_ERP
                            selectedTier = "fast_tuned"
                        },
                        modifier = Modifier.weight(1f),
                    )
                    PromptPresetPill(
                        label = "Sovereign Gov",
                        active = currentPrompt == MizanAiHarness.SYSTEM_PROMPT_GOVERNED,
                        onClick = {
                            currentPrompt = MizanAiHarness.SYSTEM_PROMPT_GOVERNED
                            selectedTier = "balanced"
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                // Prompt Editor Text Box
                Text(
                    text = "System Prompt Definition:",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(ShapeControl)
                        .background(colors.surface)
                        .border(BorderStroke(0.8.dp, colors.borderStrong), ShapeControl)
                        .padding(Space.sm),
                ) {
                    BasicTextField(
                        value = currentPrompt,
                        onValueChange = { currentPrompt = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("system_prompt_input"),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = MizanMono,
                            fontSize = 11.5.sp,
                            color = colors.textPrimary,
                            lineHeight = 16.sp,
                        ),
                        cursorBrush = SolidColor(colors.accent),
                    )
                }

                // AI Speed Tier selector
                Text(
                    text = "Harness Execution Mode:",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    SpeedTierPill(
                        label = "Fast-Tuned (0ms)",
                        selected = selectedTier == "fast_tuned",
                        onClick = { selectedTier = "fast_tuned" },
                        modifier = Modifier.weight(1f),
                    )
                    SpeedTierPill(
                        label = "Balanced",
                        selected = selectedTier == "balanced",
                        onClick = { selectedTier = "balanced" },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            MizanPrimaryButton(
                text = "Save & Apply",
                onClick = {
                    preferences.customSystemPrompt = currentPrompt
                    preferences.aiModelSpeedTier = selectedTier
                    Toast.makeText(context, "System prompt updated successfully", Toast.LENGTH_SHORT).show()
                    onSaved()
                    onDismiss()
                },
                modifier = Modifier.testTag("save_system_prompt_button"),
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textSecondary)
            }
        },
    )
}

@Composable
private fun PromptPresetPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMizanColors.current
    Box(
        modifier = modifier
            .clip(ShapePill)
            .background(if (active) colors.accent else colors.surface)
            .border(BorderStroke(0.6.dp, if (active) colors.accent else colors.border), ShapePill)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) colors.onAccent else colors.textPrimary,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SpeedTierPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMizanColors.current
    Box(
        modifier = modifier
            .clip(ShapeControl)
            .background(if (selected) colors.accentMuted else colors.surface)
            .border(BorderStroke(0.8.dp, if (selected) colors.accent else colors.border), ShapeControl)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.accent else colors.textPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
