package app.mizan.design.component

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
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
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.theme.LocalReducedMotion
import app.mizan.design.theme.MizanMono
import app.mizan.design.token.Motion
import app.mizan.design.token.MotionToken
import app.mizan.design.token.Space

val ShapeContainer = RoundedCornerShape(8.dp)
val ShapeCard = RoundedCornerShape(12.dp)
val ShapeFloating = RoundedCornerShape(16.dp)
val ShapeControl = RoundedCornerShape(10.dp)
val ShapeChip = RoundedCornerShape(6.dp)

enum class StatusTone { Neutral, Accent, Success, Warning, Danger, Info }

@Composable
fun MizanSurface(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalMizanColors.current
    Column(
        modifier = modifier
            .clip(ShapeCard)
            .background(if (elevated) colors.surfaceElevated else colors.surface)
            .border(BorderStroke(1.dp, colors.border), ShapeCard)
            .padding(Space.lg),
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
            .clip(ShapeChip)
            .background(bg)
            .padding(horizontal = Space.sm, vertical = Space.xs)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(fg),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
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
            .background(bg)
            .padding(horizontal = Space.lg, vertical = Space.sm)
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
) {
    val colors = LocalMizanColors.current
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
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
            .heightIn(min = 48.dp)
            .clip(ShapeControl)
            .background(bg)
            .then(if (border != null) Modifier.border(1.dp, border, ShapeControl) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = content.copy(alpha = 0.2f)),
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = Space.lg, vertical = Space.md),
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
    MizanButtonBase(text, onClick, colors.accent, colors.onAccent, null, modifier, enabled, loading)
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(Space.xl)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
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
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(ShapeControl)
            .background(colors.surfaceElevated)
            .border(1.dp, colors.borderStrong, ShapeControl)
            .padding(horizontal = Space.lg, vertical = Space.md)
            .semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.textTertiary)
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
            .clickable(role = Role.Button, onClick = onClick)
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
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = Space.lg, vertical = Space.sm),
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

@Composable
fun buttonPadding(): PaddingValues = PaddingValues(0.dp)
