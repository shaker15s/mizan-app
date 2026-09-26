package app.mizan.design.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.theme.LocalReducedMotion
import app.mizan.design.token.Motion
import app.mizan.design.token.MotionToken
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * One place for how Wakeel feels.
 *
 * Every interactive surface uses [mizanTap], every list uses [mizanReveal],
 * and every waiting state uses [mizanShimmer]. When the device asks for
 * reduced motion, or the user does, these collapse to an instant, legible
 * change instead of a moving one. Nothing here animates a value the user is
 * meant to trust: a number that counts up is decoration, an amount either is
 * verified or it is not, and the UI says which.
 */

/** Standard press spring. Bouncy enough to be felt, short enough to be quick. */
val MizanPressSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** Soft spring for value changes: no overshoot, because money should not bounce. */
val MizanValueSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow,
)

/**
 * Press feedback: a spring scale, a ripple, and one haptic tick.
 *
 * `indication` is the ripple, not `null`: a control that gives no sign it was
 * touched reads as broken, even when it worked.
 */
@Composable
fun Modifier.mizanTap(
    enabled: Boolean = true,
    scaleDown: Float = 0.97f,
    role: Role? = Role.Button,
    haptic: Boolean = true,
    onClick: (() -> Unit)? = null,
): Modifier {
    val reduced = LocalReducedMotion.current
    val feedback = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduced) scaleDown else 1f,
        animationSpec = MizanPressSpring,
        label = "mizan_tap_scale",
    )
    val tail = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(
            interactionSource = interaction,
            indication = ripple(),
            enabled = enabled,
            role = role,
            onClick = {
                if (haptic) feedback.tick()
                onClick()
            },
        )
    }
    return this.scale(scale).then(tail)
}

/** A press without a click, for a container that hosts its own controls. */
@Composable
fun Modifier.mizanPressable(enabled: Boolean = true, scaleDown: Float = 0.98f): Modifier {
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduced) scaleDown else 1f,
        animationSpec = MizanPressSpring,
        label = "mizan_pressable_scale",
    )
    return this.scale(scale)
}

/**
 * Staggered reveal for list items: fade, rise, and a one percent scale so the
 * row settles rather than snaps. Delayed by [index] so a screen fills in a
 * direction instead of appearing all at once.
 */
@Composable
fun Modifier.mizanReveal(index: Int = 0, enabled: Boolean = true): Modifier {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (enabled && !reduced) 0f else 1f) }
    LaunchedEffect(enabled, index) {
        if (!enabled) return@LaunchedEffect
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = Motion.millis(MotionToken.EMPHASIZED, reduced),
                delayMillis = Motion.stagger(index, reduced),
                easing = FastOutSlowInEasing,
            ),
        )
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 18f
        scaleX = 0.985f + 0.015f * progress.value
        scaleY = scaleX
    }
}

/**
 * Waiting state. A sheen moving across the placeholder, not a spinner for
 * content that has not been asked for yet.
 */
@Composable
fun Modifier.mizanShimmer(active: Boolean = true, tint: Color? = null): Modifier {
    if (!active) return this
    val colors = LocalMizanColors.current
    val sheen = tint ?: colors.accent
    val reduced = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "mizan_shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (reduced) 1 else 1250, easing = LinearEasing),
        ),
        label = "mizan_shimmer_progress",
    )
    return this.drawWithContent {
        drawContent()
        val width = size.width
        if (width > 0f) {
            val start = -width + progress * width * 2f
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        sheen.copy(alpha = 0.10f),
                        sheen.copy(alpha = 0.16f),
                        Color.Transparent,
                    ),
                    start = Offset(start, 0f),
                    end = Offset(start + width, size.height),
                ),
            )
        }
    }
}

/** A slow breath for something that is waiting on a person. */
@Composable
fun Modifier.mizanPulse(active: Boolean = true, strength: Float = 0.35f): Modifier {
    if (!active) return this
    val reduced = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "mizan_pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f - strength,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (reduced) 1 else 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mizan_pulse_alpha",
    )
    return this.graphicsLayer { this.alpha = alpha }
}

/**
 * A light that travels once around the border, slowly.
 *
 * Reserved for the one thing on a screen that is waiting on a person: an
 * approval, a reconciliation, a case only a human can close. If everything
 * on the screen glows, nothing on it is urgent.
 */
@Composable
fun Modifier.mizanLiveBorder(
    active: Boolean = true,
    color: Color = Color.Unspecified,
    shape: Shape,
    width: Dp = 1.4.dp,
    periodMillis: Int = 4200,
): Modifier {
    if (!active) return this
    val reduced = LocalReducedMotion.current
    val colors = LocalMizanColors.current
    val tint = if (color == Color.Unspecified) colors.accent else color
    val transition = rememberInfiniteTransition(label = "mizan_live_border")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (reduced) 1 else periodMillis, easing = LinearEasing),
        ),
        label = "mizan_live_border_sweep",
    )
    return this.drawWithContent {
        drawContent()
        val outline = shape.createOutline(size, layoutDirection, this)
        // A linear gradient whose ends rotate around the centre: the highlight
        // walks around the border instead of sliding across it.
        val reach = max(size.width, size.height)
        val angle = sweep * 2.0 * PI
        val dx = (cos(angle) * reach).toFloat()
        val dy = (sin(angle) * reach).toFloat()
        drawOutline(
            outline = outline,
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, tint, tint.copy(alpha = 0.18f), Color.Transparent),
                start = Offset(center.x + dx, center.y + dy),
                end = Offset(center.x - dx, center.y - dy),
            ),
            style = Stroke(width = width.toPx()),
        )
    }
}

/**
 * A soft aura behind a hero element. Cheap: one radial gradient, no shadow
 * node, no blur, so it survives on a low-end device.
 */
@Composable
fun Modifier.mizanGlow(radius: Dp = 90.dp, strength: Float = 0.28f): Modifier {
    val colors = LocalMizanColors.current
    val color = colors.accent
    return this.drawBehind {
        if (strength <= 0f) return@drawBehind
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = strength),
                    color.copy(alpha = strength * 0.35f),
                    Color.Transparent,
                ),
                center = center,
                radius = radius.toPx(),
            ),
            radius = radius.toPx(),
            center = center,
        )
    }
}

/** Haptics as a named gesture, so a screen does not invent its own. */
@Composable
fun rememberMizanHaptics(): MizanHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { MizanHaptics(feedback) }
}

class MizanHaptics(private val feedback: HapticFeedback) {
    fun tick() = feedback.safe(HapticFeedbackType.TextHandleMove)
    fun select() = feedback.safe(HapticFeedbackType.LongPress)
    fun reject() = feedback.safe(HapticFeedbackType.LongPress)

    private fun HapticFeedback.safe(type: HapticFeedbackType) {
        try {
            performHapticFeedback(type)
        } catch (_: Throwable) {
            // Haptics are feedback, never a requirement.
        }
    }
}

private fun HapticFeedback.tick() {
    try {
        performHapticFeedback(HapticFeedbackType.TextHandleMove)
    } catch (_: Throwable) {
        // Ignored on purpose: a device without a vibrator still works.
    }
}
