package app.mizan.design.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.token.Space

/**
 * Visual Progress Indicator showing ERP request success rates with Apple-glass aesthetics.
 * Built with animated Canvas arcs, glowing gradient track, and interactive breakdown metrics.
 */
@Composable
fun ErpSuccessProgressGauge(
    successRate: Float, // 0.0f to 1.0f
    totalRequests: Int,
    verifiedCount: Int,
    pendingCount: Int,
    failureCount: Int,
    modifier: Modifier = Modifier,
    title: String = "ERP Execution Success Rate",
    subtitle: String = "Live audit verification score across tenant instances",
    onInspectClick: (() -> Unit)? = null,
) {
    val colors = LocalMizanColors.current
    var animatedProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(successRate) {
        animatedProgress = successRate.coerceIn(0f, 1f)
    }

    val progressAnim by animateFloatAsState(
        targetValue = animatedProgress,
        animationSpec = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
        label = "gauge_progress",
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1f,
        animationSpec = spring(),
        label = "gauge_press",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(
                elevation = if (colors.isDark) 0.dp else 4.dp,
                shape = ShapeCard,
                spotColor = Color(0x140F172A),
                ambientColor = Color(0x080F172A),
            )
            .clip(ShapeCard)
            .background(
                if (colors.isDark) SolidColor(colors.glass) else Brush.verticalGradient(
                    listOf(Color(0xFAFFFFFF), Color(0xEDFFFFFF)),
                ),
            )
            .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
            .then(
                if (onInspectClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = onInspectClick,
                    )
                } else Modifier,
            )
            .padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            Box(
                modifier = Modifier
                    .clip(ShapePill)
                    .background(colors.accentMuted)
                    .border(BorderStroke(0.6.dp, colors.accent.copy(alpha = 0.3f)), ShapePill)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(colors.accent),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "REAL-TIME",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = colors.accent,
                    )
                }
            }
        }

        // Center Gauge & Percentage Display
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Arc Canvas
            Box(
                modifier = Modifier.size(130.dp),
                contentAlignment = Alignment.Center,
            ) {
                val gaugeAccent = if (successRate >= 0.9f) colors.accent else if (successRate >= 0.75f) colors.warning else colors.danger
                val trackColor = if (colors.isDark) Color(0x22FFFFFF) else Color(0x18000000)

                Canvas(modifier = Modifier.size(120.dp)) {
                    val strokeWidth = 12.dp.toPx()
                    val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                    val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)

                    // Background full track (240 degrees sweep starting from 150)
                    drawArc(
                        color = trackColor,
                        startAngle = 150f,
                        sweepAngle = 240f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )

                    // Foreground active progress arc
                    val sweep = 240f * progressAnim
                    if (sweep > 0f) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                0.0f to gaugeAccent.copy(alpha = 0.85f),
                                0.6f to gaugeAccent,
                                1.0f to Color(0xFF00F5D4),
                            ),
                            startAngle = 150f,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        )
                    }
                }

                // Center numeric display
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val percent = (progressAnim * 100f)
                    Text(
                        text = if (totalRequests == 0) "100%" else String.format("%.1f%%", percent),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "HEALTH",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textTertiary,
                    )
                }
            }

            // Stat breakdown columns
            Column(
                modifier = Modifier.padding(start = Space.sm),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatPill(
                    label = "Total Requests",
                    value = "$totalRequests",
                    dotColor = colors.accent,
                )
                StatPill(
                    label = "Verified Safe",
                    value = "$verifiedCount",
                    dotColor = Color(0xFF10B981),
                )
                StatPill(
                    label = "Pending Action",
                    value = "$pendingCount",
                    dotColor = colors.warning,
                )
                if (failureCount > 0) {
                    StatPill(
                        label = "Blocked / Failed",
                        value = "$failureCount",
                        dotColor = colors.danger,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, dotColor: Color) {
    val colors = LocalMizanColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
        )
    }
}

/**
 * Audit Trail Execution Receipt item with card-based layout, status badges,
 * cryptographic verification stamps, and animated micro-interaction disclosures.
 */
@Composable
fun AuditTrailReceiptCard(
    toolName: String,
    intent: String,
    phaseLabel: String,
    statusTone: StatusTone,
    receiptId: String,
    erpRecordId: String?,
    policyRule: String?,
    traceId: String,
    timestampFormatted: String,
    modifier: Modifier = Modifier,
    isBiometricVerified: Boolean = true,
    isSimulation: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalMizanColors.current
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1f,
        animationSpec = spring(),
        label = "audit_card_scale",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .animateContentSize()
            .shadow(
                elevation = if (colors.isDark) 0.dp else 2.dp,
                shape = ShapeCard,
                spotColor = Color(0x0D0F172A),
                ambientColor = Color(0x050F172A),
            )
            .clip(ShapeCard)
            .background(
                if (colors.isDark) SolidColor(colors.glass) else Brush.verticalGradient(
                    listOf(Color(0xFAFFFFFF), Color(0xEDFFFFFF)),
                ),
            )
            .border(BorderStroke(0.8.dp, colors.glassBorder), ShapeCard)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    expanded = !expanded
                    onClick?.invoke()
                },
            )
            .padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        // Top Row: Tool icon + Title + Status Tone Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(ShapeControl)
                    .background(colors.accentMuted)
                    .border(BorderStroke(0.6.dp, colors.accent.copy(alpha = 0.3f)), ShapeControl),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when {
                        toolName.contains("Purchase", ignoreCase = true) || toolName.contains("Order", ignoreCase = true) -> Icons.Outlined.ReceiptLong
                        toolName.contains("Stock", ignoreCase = true) || toolName.contains("Inventory", ignoreCase = true) -> Icons.Outlined.Hub
                        toolName.contains("Ledger", ignoreCase = true) || toolName.contains("Journal", ignoreCase = true) -> Icons.Outlined.Shield
                        else -> Icons.Outlined.CheckCircle
                    },
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(Space.sm))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = intent.ifBlank { toolName },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = erpRecordId?.let { "ERP Ref: $it" } ?: "Execution: ${receiptId.take(12)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Space.xs))
            MizanStatusBadge(phaseLabel, statusTone)
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }

        // Sub-row: Timestamp + Simulation Tag + Hash Chip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isBiometricVerified) {
                    Box(
                        modifier = Modifier
                            .clip(ShapePill)
                            .background(colors.accentMuted)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Fingerprint,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(11.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "ATTESTED",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = colors.accent,
                            )
                        }
                    }
                }
                if (isSimulation) {
                    Box(
                        modifier = Modifier
                            .clip(ShapePill)
                            .background(colors.warning.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "SIMULATION",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = colors.warning,
                        )
                    }
                }
            }
            Text(
                text = timestampFormatted,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        }

        // Expandable Audit Details & Cryptographic Trace
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Space.xs)
                    .clip(ShapeControl)
                    .background(colors.surfaceElevated)
                    .border(BorderStroke(0.6.dp, colors.border), ShapeControl)
                    .padding(Space.sm),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                policyRule?.let {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Policy Engine", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
                        Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Trace ID", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
                    Row(
                        modifier = Modifier
                            .clip(ShapePill)
                            .background(colors.accentMuted.copy(alpha = 0.5f))
                            .clickable {
                                clipboard.setText(AnnotatedString(traceId))
                                copied = true
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (copied) "COPIED!" else traceId.take(16) + "…",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = colors.accent,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "Copy trace ID",
                            tint = colors.accent,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                }
                erpRecordId?.let {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("ERP System Key", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
                        Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = colors.accent)
                    }
                }
            }
        }
    }
}

/**
 * Apple-style glass segmented pill selector with smooth transitions.
 */
@Composable
fun GlassSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMizanColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShapePill)
            .background(if (colors.isDark) Color(0x331E293B) else Color(0x14000000))
            .border(BorderStroke(0.6.dp, colors.glassBorder), ShapePill)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selectedIndex
            val itemBg = if (isSelected) {
                if (colors.isDark) colors.surfaceElevated else Color.White
            } else Color.Transparent

            val textColor = if (isSelected) colors.textPrimary else colors.textSecondary

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(ShapePill)
                    .background(itemBg)
                    .then(
                        if (isSelected) Modifier.shadow(
                            elevation = 2.dp,
                            shape = ShapePill,
                            spotColor = Color(0x140F172A),
                        ) else Modifier,
                    )
                    .clickable(role = Role.Tab, onClick = { onSelect(index) })
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = textColor,
                )
            }
        }
    }
}
