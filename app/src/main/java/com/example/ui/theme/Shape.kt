package com.example.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Custom Material 3 Shapes Configuration for Apple-grade Glassmorphism
 * All UI cards, sheets, and primary surfaces strictly use highly rounded corners (24dp+).
 */
val GlassShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),       // 24dp+ across all UI cards
    extraLarge = RoundedCornerShape(32.dp)    // 32dp for hero cards, sheets & dialogs
)

// Standardized Glassmorphism Shape Tokens
val GlassCardShape = RoundedCornerShape(24.dp)
val GlassCardShapeLarge = RoundedCornerShape(28.dp)
val GlassSheetShape = RoundedCornerShape(32.dp)
val GlassPillShape = CircleShape
