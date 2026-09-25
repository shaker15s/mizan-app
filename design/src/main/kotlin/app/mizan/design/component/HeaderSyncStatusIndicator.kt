package app.mizan.design.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mizan.design.theme.LocalMizanColors

/**
 * Visual sync-status indicator in the header that displays 'Online' or 'Last synced: [Time]'
 * using local storage state to track MIZAN service connectivity.
 */
@Composable
fun HeaderSyncStatusIndicator(
    isOnline: Boolean,
    displayText: String,
    modifier: Modifier = Modifier,
    isSyncing: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalMizanColors.current
    val haptic = LocalHapticFeedback.current

    val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    val activeColor = if (isOnline) Color(0xFF10B981) else Color(0xFFF59E0B)
    val bgTint by animateColorAsState(
        targetValue = if (isOnline) activeColor.copy(alpha = 0.12f) else colors.surfaceElevated,
        animationSpec = tween(300),
        label = "bg_tint",
    )
    val borderTint by animateColorAsState(
        targetValue = if (isOnline) activeColor.copy(alpha = 0.35f) else colors.borderStrong,
        animationSpec = tween(300),
        label = "border_tint",
    )

    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .testTag("header_sync_status_indicator")
            .defaultMinSize(minHeight = 36.dp)
            .clip(ShapePill)
            .background(bgTint)
            .border(BorderStroke(0.8.dp, borderTint), ShapePill)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = {
                            try {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            } catch (_: Throwable) {}
                            onClick()
                        },
                    )
                } else Modifier,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isSyncing) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = colors.accent,
            )
        } else {
            // Glowing Beacon Dot
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(12.dp),
            ) {
                if (isOnline) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(activeColor.copy(alpha = pulseAlpha)),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(activeColor),
                )
            }
        }

        // Live Dynamic Text: 'Online' or 'Last synced: [Time]'
        AnimatedContent(
            targetState = displayText,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
            label = "sync_text_anim",
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp,
                ),
                color = if (isOnline) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.testTag("sync_status_text"),
            )
        }

        if (onClick != null && !isSyncing) {
            Icon(
                imageVector = if (isOnline) Icons.Outlined.CloudDone else Icons.Outlined.Sync,
                contentDescription = "Sync toggle or refresh",
                tint = if (isOnline) activeColor else colors.textTertiary,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
