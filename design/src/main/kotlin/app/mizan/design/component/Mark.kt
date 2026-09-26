package app.mizan.design.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mizan.design.motion.mizanGlow
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.theme.LocalReducedMotion
import app.mizan.design.token.Space

/**
 * The Wakeel mark: the و of وكيل, wired to the two nodes of the ERP it acts on.
 *
 * It is drawn, not bitmapped, so it is crisp at 18 dp in a row and at 160 dp
 * on the sign-in screen. Geometry is a 64 unit square, the same square
 * `tools/render_brand.py` rasterises for the launcher icon, so the app icon
 * and the in-app mark are the same object.
 *
 * The only motion is the system node exhaling: a ring that leaves the node and
 * fades. It says "the agent is talking to the systems", and it is not a
 * progress indicator -- it does not stop when work finishes.
 */
@Composable
fun WakeelMark(
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    gradient: Boolean = true,
    animated: Boolean = true,
) {
    val colors = LocalMizanColors.current
    val reduced = LocalReducedMotion.current
    val brush: Brush = if (gradient) colors.accentBrush() else SolidColor(colors.accent)
    val nodeBrush: Brush = if (gradient) {
        Brush.linearGradient(listOf(colors.info, colors.info.copy(alpha = 0.72f)))
    } else {
        SolidColor(colors.info)
    }

    val pulse = if (animated && !reduced) {
        val transition = rememberInfiniteTransition(label = "wakeel_mark_pulse")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            ),
            label = "wakeel_mark_pulse_value",
        )
        value
    } else {
        0f
    }

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "Wakeel" },
    ) {
        val u = size.toPx() / 64f
        drawWakeelMark(brush = brush, nodeBrush = nodeBrush, unit = u, pulse = pulse)
    }
}

/** The geometry, in one place, shared by the mark and the emblem. */
private fun DrawScope.drawWakeelMark(brush: Brush, nodeBrush: Brush, unit: Float, pulse: Float) {
    val ox = (size.width - 64f * unit) / 2f
    val oy = (size.height - 64f * unit) / 2f

    fun px(x: Float) = ox + x * unit
    fun py(y: Float) = oy + y * unit

    // seal: a wide soft band and a crisp hairline
    drawCircle(
        brush = brush,
        radius = 29f * unit,
        center = Offset(px(32f), py(32f)),
        alpha = 0.32f,
        style = Stroke(width = 2.8f * unit),
    )
    drawCircle(
        brush = brush,
        radius = 29f * unit,
        center = Offset(px(32f), py(32f)),
        style = Stroke(width = 0.95f * unit),
    )

    // the و: the loop, the tail that leaves its right side, the hook it ends on
    drawCircle(
        brush = brush,
        radius = 7.6f * unit,
        center = Offset(px(24.6f), py(24.6f)),
        style = Stroke(width = 4.6f * unit),
    )
    drawLine(
        brush = brush,
        start = Offset(px(31.7f), py(27.2f)),
        end = Offset(px(30.4f), py(40f)),
        strokeWidth = 4.4f * unit,
        cap = StrokeCap.Round,
    )
    drawArc(
        brush = brush,
        startAngle = 333.4f,
        sweepAngle = 175f,
        useCenter = false,
        topLeft = Offset(px(27.2f) - 3.6f * unit, py(41.6f) - 3.6f * unit),
        size = Size(7.2f * unit, 7.2f * unit),
        style = Stroke(width = 2.1f * unit, cap = StrokeCap.Butt),
    )

    // the systems: two nodes, each wired back to the agent. One system is a
    // server; two is an ERP.
    drawLine(
        brush = brush,
        start = Offset(px(30.8f), py(20.2f)),
        end = Offset(px(45f), py(20.4f)),
        strokeWidth = 1.6f * unit,
        cap = StrokeCap.Round,
        alpha = 0.55f,
    )
    drawLine(
        brush = brush,
        start = Offset(px(30.9f), py(40.6f)),
        end = Offset(px(41.4f), py(37.8f)),
        strokeWidth = 1.5f * unit,
        cap = StrokeCap.Round,
        alpha = 0.50f,
    )
    drawCircle(nodeBrush, radius = 3.3f * unit, center = Offset(px(45.2f), py(20.4f)))
    drawCircle(nodeBrush, radius = 2.6f * unit, center = Offset(px(43.6f), py(37.4f)))

    // the exhale: a ring leaving the first node and fading out
    if (pulse > 0f) {
        drawCircle(
            brush = nodeBrush,
            radius = (6.7f + 2.6f * pulse) * unit,
            center = Offset(px(45.2f), py(20.4f)),
            alpha = 0.45f * (1f - pulse),
            style = Stroke(width = 1.4f * unit),
        )
    } else {
        drawCircle(
            brush = nodeBrush,
            radius = 6.7f * unit,
            center = Offset(px(45.2f), py(20.4f)),
            alpha = 0.45f,
            style = Stroke(width = 1.4f * unit),
        )
    }
}

/**
 * The hero emblem: frosted container, ambient glow, and the mark inside it.
 * Used on sign-in, the biometric gate, and any empty state that needs a face.
 */
@Composable
fun WakeelEmblem(
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
) {
    val colors = LocalMizanColors.current
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier = modifier
            .size(size)
            .mizanGlow(radius = size * 1.15f, strength = if (colors.isDark) 0.22f else 0.16f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .shadow(
                    elevation = if (colors.isDark) 0.dp else 10.dp,
                    shape = shape,
                    spotColor = Color(0x1A0F172A),
                    ambientColor = Color(0x0F0F172A),
                )
                .size(size)
                .clip(shape)
                .background(
                    if (colors.isDark) {
                        Brush.verticalGradient(
                            colors = listOf(
                                colors.glass.copy(alpha = 0.95f),
                                colors.surfaceElevated.copy(alpha = 0.90f),
                            ),
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFFFFFFFF), colors.surfaceElevated),
                        )
                    },
                )
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.verticalGradient(
                            colors = listOf(
                                if (colors.isDark) Color.White.copy(alpha = 0.35f) else Color.White,
                                colors.accent.copy(alpha = if (colors.isDark) 0.25f else 0.40f),
                            ),
                        ),
                    ),
                    shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            WakeelMark(size = size * 0.5f)
        }
    }
}

/**
 * Wordmark: the mark and the name, for headers that need a signature.
 * The name is text, not a logotype, because the bundled faces are one weight
 * and a fake logotype in the wrong weight looks worse than honest type.
 */
@Composable
fun WakeelWordmark(
    modifier: Modifier = Modifier,
    markSize: Dp = 26.dp,
    label: String = "Wakeel",
    tint: Color = LocalMizanColors.current.textPrimary,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        WakeelMark(size = markSize)
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = tint,
            letterSpacing = 2.sp,
        )
    }
}
