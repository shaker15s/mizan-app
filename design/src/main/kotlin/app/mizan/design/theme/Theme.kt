package app.mizan.design.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.mizan.design.R
import app.mizan.design.token.MizanColors
import app.mizan.design.token.ThemePresets
import app.mizan.design.token.normalizePreset
import app.mizan.design.token.resolveThemeColors

val LocalMizanColors = staticCompositionLocalOf { resolveThemeColors(ThemePresets.DEFAULT, false) }
val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalUseArabicScript = staticCompositionLocalOf { false }
val LocalThemePreset = staticCompositionLocalOf { ThemePresets.DEFAULT }

private val Jakarta = FontFamily(Font(R.font.plus_jakarta_sans, FontWeight.Normal))
private val Cairo = FontFamily(Font(R.font.cairo, FontWeight.Normal))
val MizanMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

/**
 * The bundled faces are a single weight. Hierarchy is size, colour and
 * spacing, not a synthesized bold the files do not contain. Letter spacing
 * tightens as size grows, which is what makes the few weights we have read
 * like a deliberate system.
 */
fun mizanTypography(arabic: Boolean): Typography {
    val family = if (arabic) Cairo else Jakarta
    val bodyHeight = if (arabic) 26.sp else 22.sp
    val displayTracking = if (arabic) 0.sp else (-0.6).sp
    return Typography(
        displayLarge = TextStyle(fontFamily = family, fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = displayTracking),
        displayMedium = TextStyle(fontFamily = family, fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = displayTracking),
        displaySmall = TextStyle(fontFamily = family, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.4).sp),
        headlineLarge = TextStyle(fontFamily = family, fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp),
        headlineMedium = TextStyle(fontFamily = family, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
        headlineSmall = TextStyle(fontFamily = family, fontSize = 18.sp, lineHeight = 24.sp),
        titleLarge = TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 24.sp),
        titleMedium = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
        titleSmall = TextStyle(fontFamily = family, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
        bodyLarge = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = bodyHeight),
        bodyMedium = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = if (arabic) 22.sp else 20.sp),
        bodySmall = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
        labelMedium = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
        labelSmall = TextStyle(fontFamily = MizanMono, fontSize = 11.sp, lineHeight = 14.sp),
    )
}

/**
 * Material You, when the device offers it and the user asked for it.
 *
 * The dynamic scheme is mapped onto Wakeel tokens rather than used raw, so a
 * screen that reads `accentMuted` or `dangerContainer` still gets a colour
 * that belongs to the app's semantic set instead of whatever the wallpaper
 * happened to produce.
 */
fun dynamicMizanColors(scheme: ColorScheme, isDark: Boolean): MizanColors {
    val base = resolveThemeColors(ThemePresets.CYBER_Wakeel, isDark)
    return base.copy(
        background = scheme.background,
        surface = scheme.surface,
        surfaceElevated = scheme.surfaceContainerHigh,
        glass = scheme.surface.copy(alpha = if (isDark) 0.87f else 0.95f),
        textPrimary = scheme.onSurface,
        textSecondary = scheme.onSurfaceVariant,
        textTertiary = scheme.onSurfaceVariant.copy(alpha = 0.72f),
        focus = scheme.primary,
        accent = scheme.primary,
        onAccent = scheme.onPrimary,
        accentMuted = scheme.primary.copy(alpha = if (isDark) 0.20f else 0.12f),
        accentSecondary = scheme.secondary,
        accentTertiary = scheme.tertiary,
        userBubble = scheme.primaryContainer,
        onUserBubble = scheme.onPrimaryContainer,
        neutral = scheme.onSurfaceVariant,
    )
}

@Composable
fun MizanTheme(
    dark: Boolean,
    arabic: Boolean,
    reducedMotion: Boolean,
    preset: String = ThemePresets.DEFAULT,
    context: Context? = null,
    content: @Composable () -> Unit,
) {
    val normalized = normalizePreset(preset)
    val wantsDynamic = normalized == ThemePresets.SYSTEM_DYNAMIC &&
        context != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors = if (wantsDynamic) {
        val scheme = if (dark) dynamicDarkColorScheme(context!!) else dynamicLightColorScheme(context!!)
        dynamicMizanColors(scheme, dark)
    } else {
        resolveThemeColors(normalized, dark)
    }

    val material = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            secondary = colors.accentSecondary,
            tertiary = colors.accentTertiary,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceElevated,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.danger,
            onError = colors.onDanger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            secondary = colors.accentSecondary,
            tertiary = colors.accentTertiary,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceElevated,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.danger,
            onError = colors.onDanger,
        )
    }

    CompositionLocalProvider(
        LocalMizanColors provides colors,
        LocalReducedMotion provides reducedMotion,
        LocalUseArabicScript provides arabic,
        LocalThemePreset provides normalized,
    ) {
        MaterialTheme(
            colorScheme = material,
            typography = mizanTypography(arabic),
            content = content,
        )
    }
}

object MizanThemeAccess {
    val colors: MizanColors
        @Composable get() = LocalMizanColors.current
}
