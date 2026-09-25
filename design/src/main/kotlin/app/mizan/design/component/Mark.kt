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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mizan.design.theme.LocalMizanColors

/** Geometric robot-scale mark: fusion of robot head/eyes and balance scale arms. */
@Composable
fun MizanMark(modifier: Modifier = Modifier) {
    val color = LocalMizanColors.current.accent
    val surface = LocalMizanColors.current.surface
    Canvas(modifier.size(26.dp)) {
        val stroke = size.minDimension * 0.085f
        val cx = size.width / 2f
        val headY = size.height * 0.12f
        val headRadius = size.width * 0.14f
        val coreY = size.height * 0.38f
        val foot = size.height * 0.88f

        // Central pillar (Robot torso)
        drawLine(color, Offset(cx, headY), Offset(cx, foot), stroke, StrokeCap.Round)
        // Base pedestal
        drawLine(color, Offset(cx - size.width * 0.22f, foot), Offset(cx + size.width * 0.22f, foot), stroke, StrokeCap.Round)
        // Horizontal balance arms (Robot arms)
        drawLine(color, Offset(size.width * 0.08f, coreY), Offset(size.width * 0.92f, coreY), stroke * 1.1f, StrokeCap.Round)
        // Left scale cables & dish
        drawLine(color.copy(alpha = 0.7f), Offset(size.width * 0.08f, coreY), Offset(size.width * 0.18f, coreY + size.height * 0.26f), stroke * 0.8f, StrokeCap.Round)
        drawCircle(color, stroke * 1.2f, Offset(size.width * 0.18f, coreY + size.height * 0.26f))
        // Right scale cables & dish
        drawLine(color.copy(alpha = 0.7f), Offset(size.width * 0.92f, coreY), Offset(size.width * 0.82f, coreY + size.height * 0.26f), stroke * 0.8f, StrokeCap.Round)
        drawCircle(color, stroke * 1.2f, Offset(size.width * 0.82f, coreY + size.height * 0.26f))

        // Robot Head at top of central pillar
        drawCircle(surface, headRadius, Offset(cx, headY))
        drawCircle(color, headRadius, Offset(cx, headY), style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
        // Visor eyes
        drawCircle(color, stroke * 0.85f, Offset(cx - headRadius * 0.42f, headY))
        drawCircle(color, stroke * 0.85f, Offset(cx + headRadius * 0.42f, headY))
    }
}

/**
 * Apple/Craft style luminous hero emblem with subtle ambient pulse and frosted glass backdrop.
 */
@Composable
fun MizanHeroEmblem(
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
) {
    val colors = LocalMizanColors.current
    val infiniteTransition = rememberInfiniteTransition(label = "emblem_glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow_alpha",
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        // Ambient radial glow behind
        Canvas(modifier = Modifier.size(size * 1.5f)) {
            val radius = this.size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors.accent.copy(alpha = glowAlpha * 0.45f),
                        colors.accent.copy(alpha = glowAlpha * 0.15f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = radius,
                ),
            )
        }

        // Frosted squircle container
        val shape = RoundedCornerShape(size * 0.28f)
        Box(
            modifier = Modifier
                .shadow(
                    elevation = if (colors.isDark) 0.dp else 8.dp,
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
                            colors = listOf(
                                Color(0xFFFFFFFF),
                                Color(0xFFF1F4F9),
                            ),
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
            MizanRobotScale(
                size = size * 0.85f,
                state = RobotScaleState.IDLE_BALANCED,
                interactive = true,
            )
        }
    }
}

