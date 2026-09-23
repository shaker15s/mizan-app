package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalLiquidGlass

/**
 * Top Glassmorphic Search Bar for filtering and looking up Odoo audit logs
 * by ID (#Index, record ID, trace ID) or status (CONFIRMED, PENDING, HALTED, RECONCILED).
 */
@Composable
fun AuditSearchBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedStatusFilter: String,
    onStatusFilterChange: (String) -> Unit,
    resultCount: Int,
    totalCount: Int,
    isArabic: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalLiquidGlass.current
    val isFilteringActive = searchQuery.isNotBlank() || selectedStatusFilter != "ALL"

    LiquidGlassCard(
        shape = RoundedCornerShape(24.dp),
        accentBorder = if (isFilteringActive) glass.accentTeal.copy(alpha = 0.55f) else glass.borderGlass,
        elevation = 6.dp,
        modifier = modifier.testTag("audit_search_bar_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Main Glass Input Pill
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = glass.surfaceElevated.copy(alpha = if (glass.isDark) 0.65f else 0.85f),
                border = BorderStroke(1.dp, glass.borderGlass),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Search Icon Badge
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(glass.accentTeal.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = glass.accentTeal,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // Text Field
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("input_audit_search"),
                        textStyle = TextStyle(
                            color = glass.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        cursorBrush = SolidColor(glass.accentTeal),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = if (isArabic) {
                                            "ابحث بالمعرّف (#ID, Trace) أو الحالة (Confirmed, Halted)..."
                                        } else {
                                            "Search audit logs by ID, Trace, or Status..."
                                        },
                                        style = TextStyle(
                                            color = glass.textMuted,
                                            fontSize = 12.sp
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    // Clear button
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchQueryChange("") },
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(glass.surfaceGlass)
                                .testTag("btn_clear_audit_search_text")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear text",
                                tint = glass.textMuted,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }

            // Status Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Filter Label Icon
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = null,
                    tint = glass.textMuted,
                    modifier = Modifier.size(15.dp)
                )

                // Quick Status Options
                val statusOptions = listOf(
                    "ALL" to (if (isArabic) "الكل" else "All"),
                    "CONFIRMED" to (if (isArabic) "مؤكد في أودو" else "Confirmed"),
                    "PENDING" to (if (isArabic) "قيد الاعتماد" else "Pending"),
                    "HALTED" to (if (isArabic) "متوقف (سياسة)" else "Halted"),
                    "RECONCILED" to (if (isArabic) "تمت التسوية" else "Reconciled")
                )

                statusOptions.forEach { (statusCode, label) ->
                    val isSelected = selectedStatusFilter == statusCode
                    val chipBg by animateColorAsState(
                        targetValue = if (isSelected) glass.accentTeal else glass.surfaceElevated.copy(alpha = 0.5f),
                        label = "chip_bg"
                    )
                    val chipTextColor by animateColorAsState(
                        targetValue = if (isSelected) Color.White else glass.textSecondary,
                        label = "chip_text"
                    )

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = chipBg,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) glass.accentTeal else glass.borderGlass
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onStatusFilterChange(statusCode) }
                            .testTag("status_chip_$statusCode")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelected && statusCode != "ALL") {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = chipTextColor
                            )
                        }
                    }
                }
            }

            // Results Counter & Reset Action
            AnimatedVisibility(
                visible = isFilteringActive,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (resultCount > 0) glass.accentTeal else glass.accentCoral)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isArabic) {
                                "تم العثور على $resultCount من $totalCount سجل تدقيق"
                            } else {
                                "Found $resultCount of $totalCount audit logs"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (resultCount > 0) glass.accentTeal else glass.accentCoral,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Text(
                        text = if (isArabic) "إلغاء الفلترة" else "Reset filter",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = glass.accentCoral,
                        modifier = Modifier
                            .clickable { onClear() }
                            .padding(4.dp)
                            .testTag("btn_reset_audit_filters")
                    )
                }
            }
        }
    }
}
