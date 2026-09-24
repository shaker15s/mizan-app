package app.mizan.design.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
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
import app.mizan.design.token.darkColors
import app.mizan.design.token.lightColors

val LocalMizanColors = staticCompositionLocalOf { lightColors() }
val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalUseArabicScript = staticCompositionLocalOf { false }

private val Jakarta = FontFamily(Font(R.font.plus_jakarta_sans, FontWeight.Normal))
private val Cairo = FontFamily(Font(R.font.cairo, FontWeight.Normal))
val MizanMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

/**
 * The bundled faces are a single weight. Hierarchy is size and color,
 * not a synthesized bold that the files do not contain.
 */
fun mizanTypography(arabic: Boolean): Typography {
    val family = if (arabic) Cairo else Jakarta
    val bodyHeight = if (arabic) 26.sp else 22.sp
    return Typography(
        displaySmall = TextStyle(fontFamily = family, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.4).sp),
        headlineMedium = TextStyle(fontFamily = family, fontSize = 22.sp, lineHeight = 28.sp),
        headlineSmall = TextStyle(fontFamily = family, fontSize = 18.sp, lineHeight = 24.sp),
        titleLarge = TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 24.sp),
        titleMedium = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 22.sp),
        titleSmall = TextStyle(fontFamily = family, fontSize = 13.sp, lineHeight = 18.sp),
        bodyLarge = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = bodyHeight),
        bodyMedium = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = if (arabic) 22.sp else 20.sp),
        bodySmall = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 18.sp),
        labelMedium = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = TextStyle(fontFamily = MizanMono, fontSize = 11.sp, lineHeight = 14.sp),
    )
}

@Composable
fun MizanTheme(
    dark: Boolean,
    arabic: Boolean,
    reducedMotion: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = if (dark) darkColors() else lightColors()
    val material = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
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
