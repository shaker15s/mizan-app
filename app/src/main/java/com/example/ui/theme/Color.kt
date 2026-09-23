package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ==========================================
// APPLE LIQUID GLASS & CHATGPT MODERN PALETTE
// ==========================================

// --- DARK LUXURY LIQUID GLASS THEME (ChatGPT Dark / iOS Glass) ---
val GptDarkBg = Color(0xFF0D0F12) // Deep OLED Apple/ChatGPT Dark
val GptDarkSurface = Color(0xFF16181D)
val GptDarkSurfaceElevated = Color(0xFF1E2129)
val GptDarkSurfaceGlass = Color(0xCC181B22) // 80% opacity for liquid glass blur
val GptDarkCardGlass = Color(0x99232834)
val GptDarkBorderGlass = Color(0x40FFFFFF) // 25% white specular highlight for glass edge
val GptDarkBorderSubtle = Color(0x1AFFFFFF) // 10% white for subtle borders

val GptDarkTextPrimary = Color(0xFFF9FAFB)
val GptDarkTextSecondary = Color(0xFF9CA3AF)
val GptDarkTextMuted = Color(0xFF6B7280)

// --- LIGHT APPLE LIQUID GLASS THEME (ChatGPT Light / iOS Frosted Glass) ---
val GptLightBg = Color(0xFFF6F8FB) // Apple Crisp Light Canvas
val GptLightSurface = Color(0xEBFFFFFF) // 92% translucent milk glass
val GptLightSurfaceElevated = Color(0xD8F8FAFC) // 85% translucent elevated glass
val GptLightSurfaceGlass = Color(0xE6FFFFFF) // 90% frosted backdrop
val GptLightCardGlass = Color(0xCCFFFFFF) // 80% translucent glassmorphic card fill
val GptLightBorderGlass = Color(0x2E0F172A) // 18% crisp translucent glass rim
val GptLightBorderSubtle = Color(0x140F172A) // 8% subtle border
val GptLightBorderSpecular = Color(0x80FFFFFF) // 50% specular top shine

val GptLightTextPrimary = Color(0xFF0F172A) // Deep Slate / Rich charcoal for high contrast
val GptLightTextSecondary = Color(0xFF475569) // Refined Cool Slate
val GptLightTextMuted = Color(0xFF94A3B8) // Muted Cool Grey

// --- ACCENT SIGNALS & GLOWS (ChatGPT Teal, Apple Cyan & Electric Emerald) ---
val GptTeal = Color(0xFF10A37F) // Iconic ChatGPT OpenAI green
val GptTealBright = Color(0xFF1CE0A8)
val GptTealGlow = Color(0x3310A37F)
val GptAppleBlue = Color(0xFF0A84FF)
val GptApplePurple = Color(0xFFBF5AF2)
val GptAppleOrange = Color(0xFFFF9F0A)
val GptAppleRed = Color(0xFFFF453A)
val GptAppleGreen = Color(0xFF30D158)

// Glass Gradients
val LiquidGlassDarkBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0x33FFFFFF), // specular top rim
        Color(0x08FFFFFF),
        Color(0x00FFFFFF)
    )
)

val LiquidGlassGlowTeal = Brush.radialGradient(
    colors = listOf(
        Color(0x4010A37F),
        Color(0x0010A37F)
    )
)

// Backwards compatibility aliases for existing codebase
val MizanDarkBg = GptDarkBg
val MizanSurface = GptDarkSurface
val MizanSurfaceVariant = GptDarkSurfaceElevated
val MizanCardBg = GptDarkCardGlass
val MizanCardBorder = GptDarkBorderSubtle

val MizanCyan = GptTealBright
val MizanCyanDim = GptTeal
val MizanGold = GptAppleOrange
val MizanBlue = GptAppleBlue
val MizanCoral = GptAppleRed
val MizanGreen = GptAppleGreen
val MizanPurple = GptApplePurple

val MizanTextPrimary = GptDarkTextPrimary
val MizanTextSecondary = GptDarkTextSecondary
val MizanTextMuted = GptDarkTextMuted

val MizanLightBg = GptLightBg
val MizanLightSurface = GptLightSurface
val MizanLightCard = GptLightCardGlass
val MizanLightText = GptLightTextPrimary
