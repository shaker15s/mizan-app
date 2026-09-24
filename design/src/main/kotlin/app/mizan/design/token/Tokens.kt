package app.mizan.design.token

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * MIZAN color is a warm instrument desk: paper and ink, with brass used
 * only for the action that matters. Status is never color alone.
 */
data class MizanColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val glass: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val border: Color,
    val borderStrong: Color,
    val focus: Color,
    val accent: Color,
    val onAccent: Color,
    val accentMuted: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val neutral: Color,
    val scrim: Color,
)

fun lightColors() = MizanColors(
    isDark = false,
    background = Color(0xFFF3F0E8),
    surface = Color(0xFFFBFAF6),
    surfaceElevated = Color(0xFFFFFFFF),
    glass = Color(0xF7FBFAF6),
    textPrimary = Color(0xFF1C1A16),
    textSecondary = Color(0xFF5E584E),
    textTertiary = Color(0xFF8A8376),
    border = Color(0xFFE4DDD0),
    borderStrong = Color(0xFFCFC6B6),
    focus = Color(0xFF8A6430),
    accent = Color(0xFF8A6430),
    onAccent = Color(0xFFFFF8EE),
    accentMuted = Color(0xFFF3E7D4),
    success = Color(0xFF1F6B43),
    onSuccess = Color(0xFFF4FFF8),
    successContainer = Color(0xFFE5F2EA),
    warning = Color(0xFF8A5A12),
    onWarning = Color(0xFFFFF8EE),
    warningContainer = Color(0xFFF8EBD8),
    danger = Color(0xFF9C3D32),
    onDanger = Color(0xFFFFF7F5),
    dangerContainer = Color(0xFFF8E4E1),
    info = Color(0xFF3D5C78),
    onInfo = Color(0xFFF5F8FB),
    infoContainer = Color(0xFFE4EDF4),
    neutral = Color(0xFF6E675C),
    scrim = Color(0x801C1A16),
)

fun darkColors() = MizanColors(
    isDark = true,
    background = Color(0xFF10140F),
    surface = Color(0xFF181C16),
    surfaceElevated = Color(0xFF22281F),
    glass = Color(0xF0181C16),
    textPrimary = Color(0xFFF4F0E6),
    textSecondary = Color(0xFFB7B1A4),
    textTertiary = Color(0xFF8A8478),
    border = Color(0xFF2E342C),
    borderStrong = Color(0xFF41483C),
    focus = Color(0xFFD7B56A),
    accent = Color(0xFFD7B56A),
    onAccent = Color(0xFF1C1408),
    accentMuted = Color(0xFF3A3120),
    success = Color(0xFF9CB896),
    onSuccess = Color(0xFF102116),
    successContainer = Color(0xFF1C2A20),
    warning = Color(0xFFE0B07A),
    onWarning = Color(0xFF2A1C0C),
    warningContainer = Color(0xFF3A2C1A),
    danger = Color(0xFFE09A8E),
    onDanger = Color(0xFF2C1210),
    dangerContainer = Color(0xFF3A221E),
    info = Color(0xFFA9C0D4),
    onInfo = Color(0xFF12202C),
    infoContainer = Color(0xFF1C2832),
    neutral = Color(0xFFA39C90),
    scrim = Color(0xCC000000),
)

object Space {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val xxxl: Dp = 48.dp
}

enum class MotionToken { INSTANT, FAST, STANDARD, EMPHASIZED, TRANSITION, MODAL, NAVIGATION, FEEDBACK }

object Motion {
    fun millis(token: MotionToken, reduced: Boolean): Int {
        if (reduced && token != MotionToken.INSTANT) return 1
        return when (token) {
            MotionToken.INSTANT -> 0
            MotionToken.FAST -> 120
            MotionToken.STANDARD -> 200
            MotionToken.EMPHASIZED -> 280
            MotionToken.TRANSITION -> 320
            MotionToken.MODAL -> 280
            MotionToken.NAVIGATION -> 240
            MotionToken.FEEDBACK -> 140
        }
    }
}
