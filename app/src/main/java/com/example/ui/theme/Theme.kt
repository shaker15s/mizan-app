package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

data class LiquidGlassColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceGlass: Color,
    val cardGlass: Color,
    val cardBackground: Color,
    val borderGlass: Color,
    val borderSubtle: Color,
    val borderSpecular: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accentTeal: Color,
    val accentTealGlow: Color,
    val accentBlue: Color,
    val accentPurple: Color,
    val accentCoral: Color,
    val accentGreen: Color,
    val accentOrange: Color,
    val chatUserBubble: Color,
    val chatAssistantBubble: Color,
    val backgroundGradient: Brush
)

val LocalLiquidGlass = staticCompositionLocalOf {
    liquidGlassDarkColors()
}

fun liquidGlassDarkColors() = LiquidGlassColors(
    isDark = true,
    bg = GptDarkBg,
    surface = GptDarkSurface,
    surfaceElevated = GptDarkSurfaceElevated,
    surfaceGlass = GptDarkSurfaceGlass,
    cardGlass = Color(0x991E2430),
    cardBackground = GptDarkCardGlass,
    borderGlass = Color(0x33FFFFFF),
    borderSubtle = Color(0x1AFFFFFF),
    borderSpecular = Color(0x40FFFFFF),
    textPrimary = GptDarkTextPrimary,
    textSecondary = GptDarkTextSecondary,
    textMuted = GptDarkTextMuted,
    accentTeal = GptTealBright,
    accentTealGlow = GptTealGlow,
    accentBlue = GptAppleBlue,
    accentPurple = GptApplePurple,
    accentCoral = GptAppleRed,
    accentGreen = GptAppleGreen,
    accentOrange = GptAppleOrange,
    chatUserBubble = Color(0xFF2E3544),
    chatAssistantBubble = Color(0x801A202C),
    backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0D0F14),
            Color(0xFF090B0E),
            Color(0xFF060709)
        )
    )
)

fun liquidGlassLightColors() = LiquidGlassColors(
    isDark = false,
    bg = GptLightBg,
    surface = GptLightSurface,
    surfaceElevated = GptLightSurfaceElevated,
    surfaceGlass = GptLightSurfaceGlass,
    cardGlass = GptLightCardGlass,
    cardBackground = GptLightSurface,
    borderGlass = GptLightBorderGlass,
    borderSubtle = GptLightBorderSubtle,
    borderSpecular = GptLightBorderSpecular,
    textPrimary = GptLightTextPrimary,
    textSecondary = GptLightTextSecondary,
    textMuted = GptLightTextMuted,
    accentTeal = GptTeal,
    accentTealGlow = Color(0x2610A37F),
    accentBlue = GptAppleBlue,
    accentPurple = GptApplePurple,
    accentCoral = GptAppleRed,
    accentGreen = GptAppleGreen,
    accentOrange = GptAppleOrange,
    chatUserBubble = Color(0xE6E2E8F0),
    chatAssistantBubble = Color(0xF2FFFFFF),
    backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFFF8FAFC),
            Color(0xFFF1F5F9),
            Color(0xFFE2E8F0)
        )
    )
)

private val DarkColorScheme = darkColorScheme(
    primary = GptTealBright,
    onPrimary = Color(0xFF002E24),
    primaryContainer = Color(0x331CE0A8),
    onPrimaryContainer = GptTealBright,
    secondary = GptAppleOrange,
    onSecondary = Color(0xFF432C00),
    secondaryContainer = Color(0x33FF9F0A),
    onSecondaryContainer = GptAppleOrange,
    tertiary = GptAppleBlue,
    onTertiary = Color(0xFF003549),
    tertiaryContainer = Color(0x330A84FF),
    onTertiaryContainer = GptAppleBlue,
    background = GptDarkBg,
    onBackground = GptDarkTextPrimary,
    surface = GptDarkSurfaceGlass, // Semi-transparent glassmorphic surface
    onSurface = GptDarkTextPrimary,
    surfaceVariant = GptDarkSurfaceElevated,
    onSurfaceVariant = GptDarkTextSecondary,
    outline = GptDarkBorderGlass,
    outlineVariant = GptDarkBorderSubtle,
    error = GptAppleRed,
    onError = Color(0xFF600008)
)

private val LightColorScheme = lightColorScheme(
    primary = GptTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0x1F10A37F), // Translucent glass container
    onPrimaryContainer = Color(0xFF0F766E),
    secondary = GptAppleBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0x1F0A84FF),
    onSecondaryContainer = Color(0xFF0369A1),
    tertiary = GptAppleOrange,
    onTertiary = Color.White,
    tertiaryContainer = Color(0x1FFF9F0A),
    onTertiaryContainer = Color(0xFF9A3412),
    background = GptLightBg,
    onBackground = GptLightTextPrimary,
    surface = GptLightSurface, // 92% Semi-transparent frosted glass
    onSurface = GptLightTextPrimary,
    surfaceVariant = GptLightSurfaceElevated, // 85% Translucent elevated glass
    onSurfaceVariant = GptLightTextSecondary,
    outline = GptLightBorderGlass, // 18% Specular glass border
    outlineVariant = GptLightBorderSubtle,
    error = GptAppleRed,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false, // Day/Light theme is the default (نهاري)
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val glassColors = if (darkTheme) liquidGlassDarkColors() else liquidGlassLightColors()

    CompositionLocalProvider(LocalLiquidGlass provides glassColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = GlassShapes, // Custom Material 3 Shapes with 24dp+ rounded corners
            content = content
        )
    }
}
