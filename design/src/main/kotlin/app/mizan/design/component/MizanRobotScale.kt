package app.mizan.design.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.theme.LocalReducedMotion
import kotlinx.coroutines.launch
import kotlin.math.sin

enum class RobotScaleState {
    IDLE_BALANCED,
    VERIFYING,
    SUCCESS,
    ALERT,
}

/**
 * MIZAN Robot-Scale Emblem (ميزان روبوت ذكي)
 * A fusion between the ancient Scales of Justice and an Autonomous AI Robot:
 * - Central pillar: Sleek robotic torso, neck, and head with glowing visor eyes.
 * - Central fulcrum: Glowing cybernetic intelligence core.
 * - Balance beam & arms: Symmetrical mechanical robot arms extending to left & right.
 * - Balance pans: Precision holographic weighing dishes holding ledger nodes.
 */
@Composable
fun MizanRobotScale(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    state: RobotScaleState = RobotScaleState.IDLE_BALANCED,
    interactive: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalMizanColors.current
    val reducedMotion = LocalReducedMotion.current
    val scope = rememberCoroutineScope()

    // Interactive manual tilt offset with spring return
    val userTilt = remember { Animatable(0f) }

    // Idle sinusoidal tilt breathing
    val infiniteTransition = rememberInfiniteTransition(label = "robot_scale_idle")
    val idleTilt by infiniteTransition.animateFloat(
        initialValue = -2.2f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "idle_tilt",
    )

    // Glowing core pulse
    val corePulse by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "core_pulse",
    )

    // Eye visor scan wave
    val visorScan by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "visor_scan",
    )

    val currentTotalTilt = if (reducedMotion) 0f else (userTilt.value + if (state == RobotScaleState.VERIFYING) idleTilt * 1.8f else idleTilt)

    val accentColor = when (state) {
        RobotScaleState.IDLE_BALANCED -> colors.accent
        RobotScaleState.VERIFYING -> Color(0xFF00F2FE)
        RobotScaleState.SUCCESS -> colors.success
        RobotScaleState.ALERT -> colors.warning
    }

    val glowColor = accentColor.copy(alpha = 0.45f * corePulse)

    Box(
        modifier = modifier
            .size(size)
            .testTag("robot_scale_emblem")
            .then(
                if (interactive) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        onClick?.invoke()
                        scope.launch {
                            // Organic robotic reaction tilt sequence
                            userTilt.animateTo(
                                targetValue = if (userTilt.value >= 0f) -7f else 7f,
                                animationSpec = spring(dampingRatio = 0.45f, stiffness = 400f),
                            )
                            userTilt.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(dampingRatio = 0.6f, stiffness = 200f),
                            )
                        }
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height

            val cx = w / 2f
            val cy = h / 2f

            // --- 1. Base pedestal of the scale / robot docking feet ---
            val baseWidth = w * 0.42f
            val baseHeight = h * 0.08f
            val baseY = h * 0.88f

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        colors.borderStrong,
                        colors.surfaceElevated,
                    ),
                    startY = baseY,
                    endY = baseY + baseHeight,
                ),
                topLeft = Offset(cx - baseWidth / 2f, baseY),
                size = Size(baseWidth, baseHeight),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            )

            // Neon glowing line under base
            drawLine(
                color = accentColor.copy(alpha = 0.6f * corePulse),
                start = Offset(cx - baseWidth * 0.38f, baseY + baseHeight - 1.5f),
                end = Offset(cx + baseWidth * 0.38f, baseY + baseHeight - 1.5f),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round,
            )

            // --- 2. Central Pillar (Robot Torso & Neck) ---
            val pillarWidth = w * 0.07f
            val pillarTop = h * 0.34f
            val pillarBottom = baseY

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        colors.borderStrong,
                        colors.accentMuted,
                        colors.borderStrong,
                    ),
                    startY = pillarTop,
                    endY = pillarBottom,
                ),
                topLeft = Offset(cx - pillarWidth / 2f, pillarTop),
                size = Size(pillarWidth, pillarBottom - pillarTop),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )

            // Pillar circuit grooves
            drawLine(
                color = accentColor.copy(alpha = 0.5f),
                start = Offset(cx, pillarTop + 10f),
                end = Offset(cx, pillarBottom - 10f),
                strokeWidth = 2f,
                cap = StrokeCap.Round,
            )

            // --- 3. Central Fulcrum Core (Intelligent Cyber Heart) ---
            val coreY = h * 0.35f
            val coreRadius = w * 0.08f

            // Outer core ring
            drawCircle(
                color = colors.surface,
                radius = coreRadius,
                center = Offset(cx, coreY),
            )
            drawCircle(
                color = accentColor,
                radius = coreRadius,
                center = Offset(cx, coreY),
                style = Stroke(width = 2.5.dp.toPx()),
            )
            // Glowing core energy orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentColor,
                        accentColor.copy(alpha = 0.3f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, coreY),
                    radius = coreRadius * 1.3f,
                ),
                radius = coreRadius * corePulse,
                center = Offset(cx, coreY),
            )

            // --- 4. Robot Head (Central Stick Top / Brain) ---
            // The central stick flows seamlessly into the sleek robot head!
            val headWidth = w * 0.22f
            val headHeight = h * 0.17f
            val headY = h * 0.11f

            val headPath = Path().apply {
                // Futuristic aerodynamic robot helmet contour
                moveTo(cx - headWidth / 2f, headY + headHeight * 0.8f)
                // Left curve up to crown
                cubicTo(
                    cx - headWidth / 2f, headY + headHeight * 0.2f,
                    cx - headWidth * 0.3f, headY,
                    cx, headY,
                )
                // Right crown down to right chin
                cubicTo(
                    cx + headWidth * 0.3f, headY,
                    cx + headWidth / 2f, headY + headHeight * 0.2f,
                    cx + headWidth / 2f, headY + headHeight * 0.8f,
                )
                // Chin curve
                cubicTo(
                    cx + headWidth * 0.2f, headY + headHeight,
                    cx - headWidth * 0.2f, headY + headHeight,
                    cx - headWidth / 2f, headY + headHeight * 0.8f,
                )
                close()
            }

            // Draw robot head chassis
            drawPath(
                path = headPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        colors.surfaceElevated,
                        colors.surface,
                    ),
                    startY = headY,
                    endY = headY + headHeight,
                ),
            )
            drawPath(
                path = headPath,
                color = colors.borderStrong,
                style = Stroke(width = 1.8.dp.toPx(), join = StrokeJoin.Round),
            )

            // Cybernetic antennae / scale fin on head
            drawLine(
                color = accentColor,
                start = Offset(cx, headY),
                end = Offset(cx, headY - h * 0.045f),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = accentColor,
                radius = 3.dp.toPx(),
                center = Offset(cx, headY - h * 0.045f),
            )

            // --- Robot Visor / Glowing Cyber Eyes (دمج العصاية ودماغ الروبوت وعيونه) ---
            val visorWidth = headWidth * 0.68f
            val visorHeight = headHeight * 0.32f
            val visorY = headY + headHeight * 0.38f

            // Visor black glass frame
            drawRoundRect(
                color = Color(0xFF070B12),
                topLeft = Offset(cx - visorWidth / 2f, visorY),
                size = Size(visorWidth, visorHeight),
                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
            )

            // Dual glowing cyan robotic eyes inside visor
            val eyeRadius = visorHeight * 0.28f
            val eyeSpacing = visorWidth * 0.24f
            val eyeY = visorY + visorHeight / 2f

            // Left robotic eye
            drawCircle(
                color = accentColor,
                radius = eyeRadius,
                center = Offset(cx - eyeSpacing, eyeY),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = eyeRadius * 0.45f,
                center = Offset(cx - eyeSpacing - 1f, eyeY - 1f),
            )

            // Right robotic eye
            drawCircle(
                color = accentColor,
                radius = eyeRadius,
                center = Offset(cx + eyeSpacing, eyeY),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = eyeRadius * 0.45f,
                center = Offset(cx + eyeSpacing - 1f, eyeY - 1f),
            )

            // Visor neon horizontal sweep scanline
            val scanX = cx + (visorWidth * 0.38f * visorScan)
            drawLine(
                color = Color.White.copy(alpha = 0.7f),
                start = Offset(scanX - 6f, visorY + 2f),
                end = Offset(scanX + 6f, visorY + visorHeight - 2f),
                strokeWidth = 1.5f,
                cap = StrokeCap.Round,
            )

            // --- 5. Tilting Balance Crossbeam & Robotic Arms & Scales ---
            // Everything that tilts rotates around (cx, coreY)
            rotate(degrees = currentTotalTilt, pivot = Offset(cx, coreY)) {
                val armSpan = w * 0.44f // half span to left and right

                val leftArmTip = Offset(cx - armSpan, coreY - 4f)
                val rightArmTip = Offset(cx + armSpan, coreY - 4f)

                // Mechanical balance arm path (sculpted cybernetic arm joints)
                val beamPath = Path().apply {
                    moveTo(cx, coreY - 6f)
                    lineTo(cx - armSpan * 0.4f, coreY - 8f)
                    lineTo(leftArmTip.x, leftArmTip.y)
                    lineTo(leftArmTip.x + 8f, leftArmTip.y + 7f)
                    lineTo(cx - armSpan * 0.35f, coreY - 2f)
                    lineTo(cx, coreY)
                    lineTo(cx + armSpan * 0.35f, coreY - 2f)
                    lineTo(rightArmTip.x - 8f, rightArmTip.y + 7f)
                    lineTo(rightArmTip.x, rightArmTip.y)
                    lineTo(cx + armSpan * 0.4f, coreY - 8f)
                    close()
                }

                drawPath(
                    path = beamPath,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            accentColor,
                            colors.borderStrong,
                            colors.surfaceElevated,
                            colors.borderStrong,
                            accentColor,
                        ),
                        startX = leftArmTip.x,
                        endX = rightArmTip.x,
                    ),
                )
                drawPath(
                    path = beamPath,
                    color = colors.borderStrong,
                    style = Stroke(width = 1.5f),
                )

                // Mechanical joints / knuckles at the arm tips
                drawCircle(
                    color = accentColor,
                    radius = 4.dp.toPx(),
                    center = leftArmTip,
                )
                drawCircle(
                    color = accentColor,
                    radius = 4.dp.toPx(),
                    center = rightArmTip,
                )

                // --- Left Scale Pan & Suspension Strings ---
                val dropLen = h * 0.28f
                val panWidth = w * 0.22f
                val panHeight = h * 0.05f

                val leftPanCenter = Offset(leftArmTip.x, leftArmTip.y + dropLen)

                // Two suspension chains / laser cables
                drawLine(
                    color = colors.textTertiary.copy(alpha = 0.7f),
                    start = leftArmTip,
                    end = Offset(leftPanCenter.x - panWidth * 0.42f, leftPanCenter.y),
                    strokeWidth = 1.4f,
                )
                drawLine(
                    color = colors.textTertiary.copy(alpha = 0.7f),
                    start = leftArmTip,
                    end = Offset(leftPanCenter.x + panWidth * 0.42f, leftPanCenter.y),
                    strokeWidth = 1.4f,
                )

                // Left weighing pan dish (curved robot hand palm)
                val leftPanPath = Path().apply {
                    moveTo(leftPanCenter.x - panWidth / 2f, leftPanCenter.y)
                    cubicTo(
                        leftPanCenter.x - panWidth * 0.35f, leftPanCenter.y + panHeight,
                        leftPanCenter.x + panWidth * 0.35f, leftPanCenter.y + panHeight,
                        leftPanCenter.x + panWidth / 2f, leftPanCenter.y,
                    )
                    close()
                }
                drawPath(
                    path = leftPanPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.surfaceElevated, colors.borderStrong),
                        startY = leftPanCenter.y,
                        endY = leftPanCenter.y + panHeight,
                    ),
                )
                drawPath(
                    path = leftPanPath,
                    color = accentColor.copy(alpha = 0.8f),
                    style = Stroke(width = 1.5.dp.toPx()),
                )

                // Holographic glowing audit block resting on left pan
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(accentColor, accentColor.copy(alpha = 0.4f)),
                    ),
                    topLeft = Offset(leftPanCenter.x - 7.dp.toPx(), leftPanCenter.y - 12.dp.toPx()),
                    size = Size(14.dp.toPx(), 11.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                )

                // --- Right Scale Pan & Suspension Strings ---
                val rightPanCenter = Offset(rightArmTip.x, rightArmTip.y + dropLen)

                drawLine(
                    color = colors.textTertiary.copy(alpha = 0.7f),
                    start = rightArmTip,
                    end = Offset(rightPanCenter.x - panWidth * 0.42f, rightPanCenter.y),
                    strokeWidth = 1.4f,
                )
                drawLine(
                    color = colors.textTertiary.copy(alpha = 0.7f),
                    start = rightArmTip,
                    end = Offset(rightPanCenter.x + panWidth * 0.42f, rightPanCenter.y),
                    strokeWidth = 1.4f,
                )

                // Right weighing pan dish
                val rightPanPath = Path().apply {
                    moveTo(rightPanCenter.x - panWidth / 2f, rightPanCenter.y)
                    cubicTo(
                        rightPanCenter.x - panWidth * 0.35f, rightPanCenter.y + panHeight,
                        rightPanCenter.x + panWidth * 0.35f, rightPanCenter.y + panHeight,
                        rightPanCenter.x + panWidth / 2f, rightPanCenter.y,
                    )
                    close()
                }
                drawPath(
                    path = rightPanPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.surfaceElevated, colors.borderStrong),
                        startY = rightPanCenter.y,
                        endY = rightPanCenter.y + panHeight,
                    ),
                )
                drawPath(
                    path = rightPanPath,
                    color = accentColor.copy(alpha = 0.8f),
                    style = Stroke(width = 1.5.dp.toPx()),
                )

                // Holographic glowing audit block resting on right pan
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(accentColor, accentColor.copy(alpha = 0.4f)),
                    ),
                    topLeft = Offset(rightPanCenter.x - 7.dp.toPx(), rightPanCenter.y - 12.dp.toPx()),
                    size = Size(14.dp.toPx(), 11.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                )
            }
        }
    }
}
