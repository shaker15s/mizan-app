package app.mizan.design.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
 * The MIZAN mark: a balance at rest inside a seal.
 *
 * It is drawn, not bitmapped, so it is crisp at 18 dp in a row and at 160 dp
 * on the sign-in screen. Geometry is a 64 unit square, the same square
 * `tools/render_brand.py` rasterises for the launcher icon, so the app icon
 * and the in-app mark are the same object.
 *
 * The only motion is the beam settling by one and a half degrees. It is a
 * metronome for "the system is weighing this", never a progress indicator.
 */
@Composable
fun MizanMark(
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    gradient: Boolean = true,
    animated: Boolean = true,
) {
    val colors = LocalMizanColors.current
    val reduced = LocalReducedMotion.current
    val brush: Brush = if (gradient) colors.accentBrush() else SolidColor(colors.accent)

    val settle = if (animated && !reduced) {
        val transition = rememberInfiniteTransition(label = "mizan_mark_settle")
        val value by transition.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "mizan_mark_settle_value",
        )
        value
    } else {
        0f
    }

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "MIZAN" },
    ) {
        val u = size.toPx() / 64f
        drawMizanMark(brush = brush, unit = u, settle = settle)
    }
}

/** The geometry, in one place, shared by the mark and the emblem. */
private fun DrawScope.drawMizanMark(brush: Brush, unit: Float, settle: Float) {
    val ox = (size.width - 64f * unit) / 2f
    val oy = (size.height - 64f * unit) / 2f

    fun px(x: Float) = ox + x * unit
    fun py(y: Float) = oy + y * unit

    // seal: a wide soft band and a crisp hairline
    drawCircle(
        brush = brush,
        radius = 29.2f * unit,
        center = Offset(px(32f), py(32f)),
        alpha = 0.34f,
        style = Stroke(width = 3.2f * unit),
    )
    drawCircle(
        brush = brush,
        radius = 29.2f * unit,
        center = Offset(px(32f), py(32f)),
        style = Stroke(width = 1.3f * unit),
    )

    // beam, caps and cables tilt together around the fulcrum
    rotate(degrees = settle * 1.4f, pivot = Offset(px(32f), py(22f))) {
        drawRoundRect(
            brush = brush,
            topLeft = Offset(px(12.2f), py(20.1f)),
            size = Size(39.6f * unit, 3.8f * unit),
            cornerRadius = CornerRadius(1.9f * unit, 1.9f * unit),
        )
        drawCircle(brush = brush, radius = 2.9f * unit, center = Offset(px(13f), py(22f)))
        drawCircle(brush = brush, radius = 2.9f * unit, center = Offset(px(51f), py(22f)))
        drawLine(
            brush = brush,
            start = Offset(px(13f), py(24.6f)),
            end = Offset(px(13f), py(32.4f)),
            strokeWidth = 1.7f * unit,
            cap = StrokeCap.Round,
            alpha = 0.9f,
        )
        drawLine(
            brush = brush,
            start = Offset(px(51f), py(24.6f)),
            end = Offset(px(51f), py(32.4f)),
            strokeWidth = 1.7f * unit,
            cap = StrokeCap.Round,
            alpha = 0.9f,
        )
    }

    // pans hang level and trade places by a hair
    val panRadius = 6.5f * unit
    drawArc(
        brush = brush,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(px(13f) - panRadius, py(32.4f) - panRadius - settle * 0.9f * unit),
        size = Size(panRadius * 2f, panRadius * 2f),
        style = Fill,
    )
    drawArc(
        brush = brush,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(px(51f) - panRadius, py(32.4f) - panRadius + settle * 0.9f * unit),
        size = Size(panRadius * 2f, panRadius * 2f),
        style = Fill,
    )

    // fulcrum
    val fulcrum = Path().apply {
        moveTo(px(32f), py(21.6f))
        lineTo(px(25.4f), py(31.2f))
        lineTo(px(38.6f), py(31.2f))
        close()
    }
    drawPath(path = fulcrum, brush = brush)

    // pillar, base, plinth
    drawRoundRect(
        brush = brush,
        topLeft = Offset(px(30.1f), py(30.6f)),
        size = Size(3.8f * unit, 14.6f * unit),
        cornerRadius = CornerRadius(1.9f * unit, 1.9f * unit),
    )
    drawRoundRect(
        brush = brush,
        topLeft = Offset(px(20f), py(45.2f)),
        size = Size(24f * unit, 4.4f * unit),
        cornerRadius = CornerRadius(2.2f * unit, 2.2f * unit),
    )
    drawRoundRect(
        brush = brush,
        topLeft = Offset(px(25f), py(51.4f)),
        size = Size(14f * unit, 2.6f * unit),
        cornerRadius = CornerRadius(1.3f * unit, 1.3f * unit),
        alpha = 0.6f,
    )
}

/**
 * The hero emblem: frosted container, ambient glow, and the mark inside it.
 * Used on sign-in, the biometric gate, and any empty state that needs a face.
 */
@Composable
fun MizanHeroEmblem(
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
            MizanMark(size = size * 0.5f)
        }
    }
}

/**
 * Wordmark: the mark and the name, for headers that need a signature.
 * The name is text, not a logotype, because the bundled faces are one weight
 * and a fake logotype in the wrong weight looks worse than honest type.
 */
@Composable
fun MizanWordmark(
    modifier: Modifier = Modifier,
    markSize: Dp = 26.dp,
    label: String = "MIZAN",
    tint: Color = LocalMizanColors.current.textPrimary,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        MizanMark(size = markSize)
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = tint,
            letterSpacing = 2.sp,
        )
    }
}
