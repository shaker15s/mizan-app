package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalLiquidGlass
import kotlinx.coroutines.delay

/**
 * ChatGPT-Style Streaming Text Composable:
 * Progressively reveals characters with a realistic typing delay and a subtle blinking cursor.
 */
@Composable
fun ChatGPTStreamingText(
    text: String,
    modifier: Modifier = Modifier,
    isStreamingInitially: Boolean = true,
    speedMs: Long = 16L,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    cursorColor: Color = LocalLiquidGlass.current.accentTeal,
    onStreamFinished: () -> Unit = {}
) {
    var displayedLength by remember(text) {
        mutableIntStateOf(if (isStreamingInitially) 0 else text.length)
    }
    var isDone by remember(text) {
        mutableStateOf(!isStreamingInitially)
    }

    LaunchedEffect(text, isStreamingInitially) {
        if (isStreamingInitially && displayedLength < text.length) {
            isDone = false
            // Progressive reveal simulating tokenized streaming
            while (displayedLength < text.length) {
                // Advance 1 to 3 characters for human-like fluidity
                val step = if (text.length - displayedLength > 4) (1..2).random() else 1
                displayedLength = (displayedLength + step).coerceAtMost(text.length)
                delay(speedMs)
            }
            // Keep cursor blinking for a short breather before clean completion
            delay(350)
            isDone = true
            onStreamFinished()
        } else {
            displayedLength = text.length
            isDone = true
        }
    }

    // Subtle blinking cursor animation
    val infiniteTransition = rememberInfiniteTransition(label = "cursor_blink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(480, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_alpha"
    )

    val currentText = text.take(displayedLength)

    val annotated = remember(currentText, isDone, cursorAlpha, color, cursorColor) {
        buildAnnotatedString {
            append(currentText)
            if (!isDone) {
                withStyle(
                    SpanStyle(
                        color = cursorColor.copy(alpha = cursorAlpha),
                        fontWeight = FontWeight.Black
                    )
                ) {
                    append(" ▍")
                }
            }
        }
    }

    Text(
        text = annotated,
        style = style,
        color = color,
        modifier = modifier
    )
}

/**
 * Collapsible Thinking Process Block (mimics ChatGPT o1 / o3 reasoning block)
 */
@Composable
fun ChatGPTThinkingBlock(
    isThinking: Boolean,
    thoughtDurationSec: Double,
    thoughtProcess: String?,
    isArabic: Boolean,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val glass = LocalLiquidGlass.current

    val infiniteTransition = rememberInfiniteTransition(label = "thinking_spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "thinking_rotation"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = glass.surfaceElevated.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, glass.borderGlass),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { if (thoughtProcess != null) isExpanded = !isExpanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(glass.accentTeal.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isThinking) Icons.Default.AutoAwesome else Icons.Default.Psychology,
                            contentDescription = null,
                            tint = glass.accentTeal,
                            modifier = Modifier
                                .size(15.dp)
                                .then(if (isThinking) Modifier.rotate(rotation).scale(pulseScale) else Modifier)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = if (isThinking) {
                            if (isArabic) "جاري التفكير وتحليل قيود الأمان..." else "Thinking & evaluating bounds..."
                        } else {
                            if (isArabic) "تم التفكير لمدة ${"%.1f".format(thoughtDurationSec)} ثانية" else "Thought for ${"%.1f".format(thoughtDurationSec)}s"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isThinking) glass.accentTeal else glass.textSecondary
                    )
                }

                if (thoughtProcess != null && !isThinking) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isExpanded) (if (isArabic) "إخفاء" else "Hide") else (if (isArabic) "عرض" else "Show"),
                            fontSize = 11.sp,
                            color = glass.textMuted
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = glass.textMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded && thoughtProcess != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .background(glass.bg.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = thoughtProcess.orEmpty(),
                        fontSize = 11.sp,
                        color = glass.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

/**
 * Animated Audio Waveform for Active Voice Input
 */
@Composable
fun ChatGPTVoiceWaveform(
    isArabic: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalLiquidGlass.current
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_anim")

    // Heights for 5 animated bars simulating audio spectrum
    val h1 by infiniteTransition.animateFloat(
        initialValue = 6f, targetValue = 22f,
        animationSpec = infiniteRepeatable(tween(320, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "h1"
    )
    val h2 by infiniteTransition.animateFloat(
        initialValue = 16f, targetValue = 8f,
        animationSpec = infiniteRepeatable(tween(260, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "h2"
    )
    val h3 by infiniteTransition.animateFloat(
        initialValue = 10f, targetValue = 28f,
        animationSpec = infiniteRepeatable(tween(380, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "h3"
    )
    val h4 by infiniteTransition.animateFloat(
        initialValue = 24f, targetValue = 10f,
        animationSpec = infiniteRepeatable(tween(290, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "h4"
    )
    val h5 by infiniteTransition.animateFloat(
        initialValue = 8f, targetValue = 20f,
        animationSpec = infiniteRepeatable(tween(340, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "h5"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Pulsing Mic Emblem
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(glass.accentCoral.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = glass.accentCoral,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = if (isArabic) "جاري الاستماع الصوتي..." else "Listening to your voice...",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = glass.accentCoral
                )
                Text(
                    text = if (isArabic) "تحدث بالأمر أو استفسار Odoo ERP" else "Say your ERP command clearly",
                    fontSize = 10.sp,
                    color = glass.textMuted
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Waveform bars
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(28.dp)
            ) {
                listOf(h1, h2, h3, h4, h5).forEach { h ->
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(h.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(glass.accentCoral)
                    )
                }
            }
        }

        // Stop / Cancel button
        IconButton(
            onClick = onStop,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(glass.surfaceElevated)
                .border(1.dp, glass.borderGlass, CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = "Stop Voice Input",
                tint = glass.accentCoral,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * ChatGPT-Style Floating Liquid Glass Input Bar:
 * Floats at the bottom with high-opacity glassmorphism, rounded send button,
 * voice input button, and optional presets button.
 */
@Composable
fun ChatGPTFloatingInputBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSend: () -> Unit,
    isArabic: Boolean,
    isReasoning: Boolean,
    onVoiceClick: () -> Unit,
    isVoiceActive: Boolean,
    onVoiceStop: () -> Unit,
    onAttachClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val glass = LocalLiquidGlass.current
    val hasText = query.isNotBlank()

    // Smooth color animation for the send button
    val sendButtonBg by animateColorAsState(
        targetValue = if (hasText) glass.accentTeal else glass.surfaceElevated.copy(alpha = 0.5f),
        animationSpec = tween(220),
        label = "send_btn_bg"
    )

    val sendIconTint by animateColorAsState(
        targetValue = if (hasText) Color.White else glass.textMuted.copy(alpha = 0.5f),
        animationSpec = tween(220),
        label = "send_icon_tint"
    )

    val sendButtonScale by animateFloatAsState(
        targetValue = if (hasText) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.7f),
        label = "send_btn_scale"
    )

    // Specular border gradient reflecting light like Apple glass
    val specularBorderBrush = remember(glass.isDark) {
        Brush.verticalGradient(
            colors = if (glass.isDark) {
                listOf(Color(0x55FFFFFF), Color(0x15FFFFFF), Color(0x05FFFFFF))
            } else {
                listOf(Color(0x90FFFFFF), Color(0x35000000), Color(0x10000000))
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Surface(
            color = glass.surfaceElevated.copy(alpha = if (glass.isDark) 0.88f else 0.94f),
            shape = RoundedCornerShape(32.dp),
            border = BorderStroke(1.2.dp, specularBorderBrush),
            shadowElevation = 14.dp,
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 14.dp,
                    shape = RoundedCornerShape(32.dp),
                    ambientColor = if (glass.isDark) Color(0x66000000) else Color(0x20000000),
                    spotColor = if (glass.isDark) Color(0x88000000) else Color(0x33000000)
                )
        ) {
            if (isVoiceActive) {
                ChatGPTVoiceWaveform(
                    isArabic = isArabic,
                    onStop = onVoiceStop
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Quick Action / Plus button (ChatGPT mobile style)
                    if (onAttachClick != null) {
                        IconButton(
                            onClick = onAttachClick,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(glass.surfaceElevated.copy(alpha = 0.7f))
                                .border(1.dp, glass.borderGlass, CircleShape)
                                .testTag("btn_input_attach")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Quick Tools & Presets",
                                tint = glass.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    // Text Input Area
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 38.dp, max = 110.dp)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            .testTag("input_agent_query"),
                        textStyle = TextStyle(
                            color = glass.textPrimary,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Default
                        ),
                        cursorBrush = SolidColor(glass.accentTeal),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = if (isArabic) "اسأل وكيل ميزان أو أصدر أمر Odoo..." else "Message Mizan AI agent...",
                                        style = TextStyle(
                                            color = glass.textMuted,
                                            fontSize = 13.sp
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    // Voice Input Icon Button (ChatGPT mobile microphone)
                    IconButton(
                        onClick = onVoiceClick,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(glass.surfaceElevated.copy(alpha = 0.7f))
                            .border(1.dp, glass.borderGlass, CircleShape)
                            .testTag("btn_voice_input")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice Dictation",
                            tint = glass.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Rounded Send Button (ChatGPT style circle with upward arrow)
                    IconButton(
                        onClick = {
                            if (hasText && !isReasoning) {
                                onSend()
                            }
                        },
                        enabled = hasText && !isReasoning,
                        modifier = Modifier
                            .size(38.dp)
                            .scale(sendButtonScale)
                            .clip(CircleShape)
                            .background(sendButtonBg)
                            .border(1.dp, if (hasText) glass.accentTeal.copy(alpha = 0.5f) else Color.Transparent, CircleShape)
                            .testTag("btn_send_intent")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send Intent",
                            tint = sendIconTint,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
