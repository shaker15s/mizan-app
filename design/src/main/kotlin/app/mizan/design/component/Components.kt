package app.mizan.design.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.mizan.design.motion.mizanPulse
import app.mizan.design.motion.mizanReveal
import app.mizan.design.motion.mizanShimmer
import app.mizan.design.motion.mizanTap
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.theme.LocalReducedMotion
import app.mizan.design.theme.MizanMono
import app.mizan.design.token.Motion
import app.mizan.design.token.MotionToken
import app.mizan.design.token.Space

val ShapeContainer = RoundedCornerShape(18.dp)
val ShapeCard = RoundedCornerShape(22.dp)
val ShapeFloating = RoundedCornerShape(28.dp)
val ShapeControl = RoundedCornerShape(14.dp)
val ShapePill = RoundedCornerShape(100.dp)
val ShapeChip = RoundedCornerShape(10.dp)
val ShapeBubbleUser = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
val ShapeBubbleAgent = RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)

enum class StatusTone { Neutral, Accent, Success, Warning, Danger, Info }

/**
 * High-end iOS style tactile press modifier with spring scale-down and haptic feedback.
 */
@Composable
fun Modifier.mizanBounceClick(
    enabled: Boolean = true,
    scaleDown: Float = 0.96f,
    role: Role? = Role.Button,
    onClick: (() -> Unit)? = null,
): Modifier = mizanTap(
    enabled = enabled,
    scaleDown = scaleDown,
    role = role,
    onClick = onClick,
)

@Composable
fun MizanSurface(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalMizanColors.current
    Column(
        modifier = modifier
            .shadow(
                elevation = if (colors.isDark) 0.dp else if (elevated) 4.dp else 2.dp,
                shape = ShapeCard,
                spotColor = Color(0x0F0F172A),
                ambientColor = Color(0x080F172A),
            )
            .clip(ShapeCard)
            .background(
                if (colors.isDark) {
                    SolidColor(if (elevated) colors.surfaceElevated else colors.glass)
                } else {
                    if (elevated) SolidColor(colors.surfaceElevated) else Brush.verticalGradient(
                        listOf(Color(0xF7FFFFFF), Color(0xEBFFFFFF)),
                    )
                },
            )
            .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
            .padding(Space.md),
        content = content,
    )
}

@Composable
fun MizanGlassCard(
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalMizanColors.current
    Column(
        modifier = modifier
            .shadow(
                elevation = if (colors.isDark) 0.dp else 3.dp,
                shape = ShapeCard,
                spotColor = Color(0x0F0F172A),
                ambientColor = Color(0x080F172A),
            )
            .clip(ShapeCard)
            .background(
                if (colors.isDark) SolidColor(colors.glass) else Brush.verticalGradient(
                    listOf(Color(0xF7FFFFFF), Color(0xEBFFFFFF)),
                ),
            )
            .border(border ?: BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
            .padding(Space.md),
        content = content,
    )
}

@Composable
fun MizanSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalMizanColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        if (action != null && onAction != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelLarge,
                color = colors.accent,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = Space.sm)
                    .semantics { contentDescription = action },
            )
        }
    }
}

@Composable
fun MizanStatusBadge(label: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val colors = LocalMizanColors.current
    val (fg, bg) = when (tone) {
        StatusTone.Neutral -> colors.neutral to colors.background
        StatusTone.Accent -> colors.accent to colors.accentMuted
        StatusTone.Success -> colors.success to colors.successContainer
        StatusTone.Warning -> colors.warning to colors.warningContainer
        StatusTone.Danger -> colors.danger to colors.dangerContainer
        StatusTone.Info -> colors.info to colors.infoContainer
    }
    Row(
        modifier = modifier
            .clip(ShapePill)
            .background(bg)
            .border(BorderStroke(0.6.dp, fg.copy(alpha = 0.25f)), ShapePill)
            .padding(horizontal = Space.sm, vertical = 2.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val urgent = tone == StatusTone.Warning || tone == StatusTone.Danger
        Box(
            modifier = Modifier
                .size(6.dp)
                .mizanPulse(active = urgent, strength = 0.55f)
                .clip(RoundedCornerShape(3.dp))
                .background(fg),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

@Composable
fun MizanBanner(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val colors = LocalMizanColors.current
    val bg = when (tone) {
        StatusTone.Warning -> colors.warningContainer
        StatusTone.Danger -> colors.dangerContainer
        StatusTone.Info -> colors.infoContainer
        StatusTone.Success -> colors.successContainer
        else -> colors.accentMuted
    }
    val fg = when (tone) {
        StatusTone.Warning -> colors.warning
        StatusTone.Danger -> colors.danger
        StatusTone.Info -> colors.info
        StatusTone.Success -> colors.success
        else -> colors.accent
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = fg,
        modifier = modifier
            .fillMaxWidth()
            .mizanReveal(index = 0)
            .clip(ShapeCard)
            .background(bg)
            .border(BorderStroke(0.8.dp, fg.copy(alpha = 0.30f)), ShapeCard)
            .padding(horizontal = Space.lg, vertical = Space.md)
            .semantics { contentDescription = text },
    )
}

@Composable
private fun MizanButtonBase(
    text: String,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    border: Color?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    gradient: Brush? = null,
) {
    val colors = LocalMizanColors.current
    val reduced = LocalReducedMotion.current
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !loading) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "btn_scale",
    )
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> colors.border
            pressed -> container.copy(alpha = 0.86f)
            else -> container
        },
        animationSpec = tween(Motion.millis(MotionToken.FEEDBACK, reduced)),
        label = "button",
    )
    val fg = if (enabled) content else colors.textTertiary
    Row(
        modifier = modifier
            .scale(scale)
            .animateContentSize(animationSpec = tween(Motion.millis(MotionToken.FAST, reduced)))
            .heightIn(min = 48.dp)
            .shadow(
                elevation = when {
                    !enabled || container == Color.Transparent -> 0.dp
                    colors.isDark -> 0.dp
                    pressed -> 6.dp
                    else -> 2.dp
                },
                shape = ShapePill,
                spotColor = Color(0x140F172A),
                ambientColor = Color(0x080F172A),
            )
            .clip(ShapePill)
            .then(
                if (gradient != null && enabled) {
                    Modifier.background(gradient, ShapePill)
                } else {
                    Modifier.background(bg, ShapePill)
                },
            )
            .then(if (border != null) Modifier.border(0.8.dp, border, ShapePill) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = content.copy(alpha = 0.2f)),
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = {
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } catch (_: Throwable) {}
                    onClick()
                },
            )
            .padding(horizontal = Space.lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = fg)
            Spacer(Modifier.width(Space.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, textAlign = TextAlign.Center)
    }
}

@Composable
fun MizanPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = LocalMizanColors.current
    MizanButtonBase(
        text = text,
        onClick = onClick,
        container = colors.accent,
        content = colors.onAccent,
        border = null,
        modifier = modifier,
        enabled = enabled,
        loading = loading,
        gradient = colors.accentBrush(),
    )
}

@Composable
fun MizanSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalMizanColors.current
    MizanButtonBase(text, onClick, colors.surface, colors.textPrimary, colors.borderStrong, modifier, enabled)
}

@Composable
fun MizanDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalMizanColors.current
    MizanButtonBase(text, onClick, colors.danger, colors.onDanger, null, modifier, enabled)
}

@Composable
fun MizanGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalMizanColors.current
    MizanButtonBase(text, onClick, Color.Transparent, colors.accent, null, modifier, enabled)
}

@Composable
fun MizanEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalMizanColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .mizanReveal(index = 0)
            .padding(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary, modifier = Modifier.semantics { heading() })
        Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Space.sm))
            MizanPrimaryButton(actionLabel, onAction)
        }
    }
}

@Composable
fun MizanErrorState(
    title: String,
    body: String,
    retryLabel: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val colors = LocalMizanColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Space.xl)
            .semantics { contentDescription = title },
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        MizanStatusBadge(title, StatusTone.Danger)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Space.sm))
        MizanPrimaryButton(retryLabel, onRetry)
        if (secondaryLabel != null && onSecondary != null) {
            MizanSecondaryButton(secondaryLabel, onSecondary)
        }
    }
}

@Composable
fun MizanLoadingState(label: String, modifier: Modifier = Modifier) {
    val colors = LocalMizanColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Space.xl)
            .semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
        // The skeleton shows the shape of what is coming, so the screen does
        // not jump when the rows arrive.
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (index == 2) 0.62f else 1f)
                    .height(if (index == 0) 18.dp else 12.dp)
                    .clip(ShapeChip)
                    .mizanShimmer(active = true)
                    .background(colors.surfaceElevated),
            )
        }
    }
}

@Composable
fun MizanListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    tone: StatusTone = StatusTone.Neutral,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalMizanColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.sm))
            MizanStatusBadge(trailing, tone)
        }
    }
}

@Composable
fun MizanMonoText(text: String, modifier: Modifier = Modifier) {
    val colors = LocalMizanColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MizanMono),
        color = colors.textSecondary,
        modifier = modifier.semantics { contentDescription = text },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun MizanCommandField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalMizanColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(ShapePill)
            .background(colors.surfaceElevated.copy(alpha = 0.85f))
            .border(BorderStroke(0.8.dp, colors.glassBorder), ShapePill)
            .padding(horizontal = Space.lg, vertical = Space.sm)
            .semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = colors.textTertiary)
                }
                inner()
            }
        },
    )
}

@Composable
fun MizanIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val colors = LocalMizanColors.current
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(ShapeControl)
            .background(if (selected) colors.accentMuted else Color.Transparent)
            .mizanBounceClick(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) colors.accent else colors.textSecondary)
    }
}

@Composable
fun LtrText(text: String, modifier: Modifier = Modifier, color: Color = LocalMizanColors.current.textSecondary) {
    val direction = LocalLayoutDirection.current
    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            text = text,
            modifier = modifier,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MizanMono),
            color = color,
            textAlign = if (direction == LayoutDirection.Rtl) TextAlign.End else TextAlign.Start,
        )
    }
}

@Composable
fun MizanKeyValue(label: String, value: String, mono: Boolean = false) {
    val colors = LocalMizanColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textTertiary)
        if (mono) LtrText(value, color = colors.textPrimary) else {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
        }
    }
}

@Composable
fun MizanGlassDock(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = LocalMizanColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.glass)
            .border(BorderStroke(0.8.dp, colors.glassBorder), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .padding(horizontal = Space.md, vertical = Space.sm),
    ) {
        content()
    }
}

@Composable
fun SuggestionRow(suggestions: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier) {
    val colors = LocalMizanColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        suggestions.forEach { (label, action) ->
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClick = action)
                    .padding(vertical = Space.sm),
            )
        }
    }
}

/**
 * Craft iOS style feature card with frosted glass, hairline border, and tinted icon container.
 */
@Composable
fun CraftFeatureCard(
    title: String,
    body: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    trailingBadge: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalMizanColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (colors.isDark) 0.dp else 3.dp,
                shape = ShapeCard,
                spotColor = Color(0x0F0F172A),
                ambientColor = Color(0x050F172A),
            )
            .clip(ShapeCard)
            .background(
                if (colors.isDark) SolidColor(colors.glass) else Brush.verticalGradient(
                    listOf(Color(0xFAFFFFFF), Color(0xEDFFFFFF)),
                ),
            )
            .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
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
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailingBadge != null) {
            Spacer(Modifier.width(Space.sm))
            MizanStatusBadge(trailingBadge, StatusTone.Accent)
        }
    }
}

/**
 * Craft iOS style selectable card (for workspace, role, or policy selection).
 */
@Composable
fun CraftSelectableCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMizanColors.current
    val borderColor = if (selected) colors.accent else colors.glassBorder
    val borderWidth = if (selected) 1.5.dp else 0.8.dp
    val bg: Brush = if (colors.isDark) {
        SolidColor(if (selected) colors.accentMuted.copy(alpha = 0.2f) else colors.glass)
    } else {
        if (selected) SolidColor(colors.accentMuted.copy(alpha = 0.12f)) else Brush.verticalGradient(
            listOf(Color(0xFAFFFFFF), Color(0xEDFFFFFF)),
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (colors.isDark) 0.dp else if (selected) 4.dp else 2.dp,
                shape = ShapeCard,
                spotColor = if (selected) colors.accent.copy(alpha = 0.2f) else Color(0x0F0F172A),
                ambientColor = Color(0x050F172A),
            )
            .clip(ShapeCard)
            .background(bg)
            .border(BorderStroke(borderWidth, borderColor), ShapeCard)
            .mizanBounceClick(role = Role.RadioButton, onClick = onSelect)
            .padding(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(ShapeControl)
                .background(if (selected) colors.accent else colors.surfaceElevated)
                .border(BorderStroke(0.6.dp, if (selected) colors.accent else colors.borderStrong), ShapeControl),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) colors.onAccent else colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.width(Space.sm))
        // Radio indicator
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (selected) colors.accent else Color.Transparent)
                .border(
                    BorderStroke(1.2.dp, if (selected) colors.accent else colors.textTertiary),
                    RoundedCornerShape(11.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.onAccent),
                )
            }
        }
    }
}

/**
 * Craft iOS style animated page indicator dots/pills.
 */
@Composable
fun CraftPageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMizanColors.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until pageCount) {
            val isActive = i == currentPage
            val width = if (isActive) 24.dp else 8.dp
            val color = if (isActive) colors.accent else colors.borderStrong
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(ShapePill)
                    .background(color),
            )
        }
    }
}

/**
 * Floating Apple/Craft frosted dock with hairline border and bottom inset padding.
 */
@Composable
fun CraftFloatingDock(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalMizanColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (colors.isDark) 0.dp else 8.dp,
                shape = ShapeFloating,
                spotColor = Color(0x140F172A),
                ambientColor = Color(0x0A0F172A),
            )
            .clip(ShapeFloating)
            .background(
                if (colors.isDark) SolidColor(colors.surfaceElevated.copy(alpha = 0.94f)) else Brush.verticalGradient(
                    listOf(Color(0xF8FFFFFF), Color(0xEEFFFFFF)),
                ),
            )
            .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeFloating)
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        content = content,
    )
}

@Composable
fun buttonPadding(): PaddingValues = PaddingValues(0.dp)

