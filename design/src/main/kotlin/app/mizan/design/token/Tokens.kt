package app.mizan.design.token

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * MIZAN Modern Apple Glass & Fluid Intelligence theme.
 * Clean, translucent materials, precision borders, and high-contrast typography.
 */
data class MizanColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val glass: Color,
    val glassBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val border: Color,
    val borderStrong: Color,
    val focus: Color,
    val accent: Color,
    val onAccent: Color,
    val accentMuted: Color,
    val userBubble: Color,
    val onUserBubble: Color,
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
    background = Color(0xFFF8F9FC),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFF1F3F9),
    glass = Color(0xEBFFFFFF),
    glassBorder = Color(0x1C0F172A),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF4B5563),
    textTertiary = Color(0xFF94A3B8),
    border = Color(0x0F0F172A),
    borderStrong = Color(0x210F172A),
    focus = Color(0xFF0D9488),
    accent = Color(0xFF0D9488),
    onAccent = Color(0xFFFFFFFF),
    accentMuted = Color(0x180D9488),
    userBubble = Color(0xFF0F172A),
    onUserBubble = Color(0xFFFFFFFF),
    success = Color(0xFF0D9488),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFF0FDF4),
    warning = Color(0xFFD97706),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFFBEB),
    danger = Color(0xFFE11D48),
    onDanger = Color(0xFFFFFFFF),
    dangerContainer = Color(0xFFFFF1F2),
    info = Color(0xFF0284C7),
    onInfo = Color(0xFFFFFFFF),
    infoContainer = Color(0xFFF0F9FF),
    neutral = Color(0xFF64748B),
    scrim = Color(0x4D0F172A),
)

fun darkColors() = MizanColors(
    isDark = true,
    background = Color(0xFF0B0F17),
    surface = Color(0xFF131926),
    surfaceElevated = Color(0xFF1A2234),
    glass = Color(0xD9131926),
    glassBorder = Color(0x26FFFFFF),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    textTertiary = Color(0xFF64748B),
    border = Color(0x1FFFFFFF),
    borderStrong = Color(0x33FFFFFF),
    focus = Color(0xFF10B981),
    accent = Color(0xFF10B981),
    onAccent = Color(0xFF022C22),
    accentMuted = Color(0x2610B981),
    userBubble = Color(0xFF1E293B),
    onUserBubble = Color(0xFFF8FAFC),
    success = Color(0xFF10B981),
    onSuccess = Color(0xFF022C22),
    successContainer = Color(0xFF064E3B),
    warning = Color(0xFFF59E0B),
    onWarning = Color(0xFF451A03),
    warningContainer = Color(0xFF78350F),
    danger = Color(0xFFEF4444),
    onDanger = Color(0xFF450A0A),
    dangerContainer = Color(0xFF7F1D1D),
    info = Color(0xFF38BDF8),
    onInfo = Color(0xFF082F49),
    infoContainer = Color(0xFF0C4A6E),
    neutral = Color(0xFF94A3B8),
    scrim = Color(0xCC000000),
)

object Space {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp
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
