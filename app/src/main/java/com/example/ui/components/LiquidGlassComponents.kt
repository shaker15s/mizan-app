package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalLiquidGlass

/**
 * Apple-style Liquid Glass Card with specular highlights, frosted blur layer, and soft rounded curvature
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    elevation: Dp = 8.dp,
    accentBorder: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val glass = LocalLiquidGlass.current

    val specularBrush = remember(glass.isDark) {
        Brush.verticalGradient(
            colors = if (glass.isDark) {
                listOf(
                    Color(0x38FFFFFF), // specular shine at upper rim
                    Color(0x0FFFFFFF),
                    Color(0x00FFFFFF)
                )
            } else {
                listOf(
                    Color(0x80FFFFFF),
                    Color(0x20FFFFFF),
                    Color(0x00FFFFFF)
                )
            }
        )
    }

    val finalBorderColor = accentBorder ?: glass.borderGlass

    Surface(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = if (glass.isDark) Color(0x60000000) else Color(0x1A000000),
                spotColor = if (glass.isDark) Color(0x80000000) else Color(0x2A000000)
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    )
                } else Modifier
            ),
        shape = shape,
        color = glass.cardGlass,
        border = BorderStroke(1.2.dp, finalBorderColor)
    ) {
        Box {
            // Specular glass highlight reflection layer
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(specularBrush)
            )

            // Actual Card Content
            content()
        }
    }
}

/**
 * Liquid Glass Capsule / Pill (e.g., for Chips, Filters, Badges)
 */
@Composable
fun LiquidGlassPill(
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    selectedColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val glass = LocalLiquidGlass.current
    val shape = CircleShape
    val activeColor = selectedColor ?: glass.accentTeal

    val containerColor = if (isSelected) {
        activeColor.copy(alpha = if (glass.isDark) 0.22f else 0.16f)
    } else {
        glass.surfaceElevated.copy(alpha = if (glass.isDark) 0.6f else 0.8f)
    }

    val borderColor = if (isSelected) {
        activeColor.copy(alpha = 0.85f)
    } else {
        glass.borderSubtle
    }

    Surface(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    )
                } else Modifier
            ),
        shape = shape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        content()
    }
}

/**
 * ChatGPT-Style Thinking & Reasoning Indicator (Fluid Pulse Animation)
 */
@Composable
fun ThinkingIndicator(
    textEn: String = "Reasoning & Verifying ERP Bounds...",
    textAr: String = "جاري التفكير والتأكد من قيود أودو...",
    isArabic: Boolean = false,
    modifier: Modifier = Modifier
) {
    val glass = LocalLiquidGlass.current
    val infiniteTransition = rememberInfiniteTransition(label = "thinking_anim")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -200f,
        targetValue = 400f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            glass.accentTeal.copy(alpha = 0.2f),
            glass.accentTeal.copy(alpha = 0.9f),
            glass.accentTeal.copy(alpha = 0.2f)
        ),
        start = Offset(shimmerOffset, 0f),
        end = Offset(shimmerOffset + 150f, 0f)
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = glass.surfaceElevated.copy(alpha = 0.8f),
        border = BorderStroke(1.dp, glass.accentTeal.copy(alpha = 0.4f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pulsing OpenAI Sparkle Icon
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                    }
                    .clip(CircleShape)
                    .background(glass.accentTeal.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Thinking",
                    tint = glass.accentTeal,
                    modifier = Modifier.size(14.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Shimmering Reasoning Status Text
            Text(
                text = if (isArabic) textAr else textEn,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = glass.textPrimary
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Wave Dots Animation
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { index ->
                    val dotAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = index * 200, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dot_$index"
                    )
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(glass.accentTeal.copy(alpha = dotAlpha))
                    )
                }
            }
        }
    }
}
