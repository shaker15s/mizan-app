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

fun cyberColors(isDark: Boolean): MizanColors = if (isDark) {
    MizanColors(
        isDark = true,
        background = Color(0xFF080C14),
        surface = Color(0xFF0F172A),
        surfaceElevated = Color(0xFF152238),
        glass = Color(0xEB0E1726),
        glassBorder = Color(0x3800F2FE),
        textPrimary = Color(0xFFF0FDF4),
        textSecondary = Color(0xFF94A3B8),
        textTertiary = Color(0xFF64748B),
        border = Color(0x2600F2FE),
        borderStrong = Color(0x4D00F2FE),
        focus = Color(0xFF00F2FE),
        accent = Color(0xFF00F2FE),
        onAccent = Color(0xFF041E28),
        accentMuted = Color(0x2E00F2FE),
        userBubble = Color(0xFF1E293B),
        onUserBubble = Color(0xFFF0FDF4),
        success = Color(0xFF00F2FE),
        onSuccess = Color(0xFF041E28),
        successContainer = Color(0xFF083344),
        warning = Color(0xFFFBBF24),
        onWarning = Color(0xFF451A03),
        warningContainer = Color(0xFF78350F),
        danger = Color(0xFFF43F5E),
        onDanger = Color(0xFF4C0519),
        dangerContainer = Color(0xFF881337),
        info = Color(0xFF38BDF8),
        onInfo = Color(0xFF082F49),
        infoContainer = Color(0xFF0C4A6E),
        neutral = Color(0xFF94A3B8),
        scrim = Color(0xE6000000),
    )
} else {
    MizanColors(
        isDark = false,
        background = Color(0xFFF3F7FA),
        surface = Color(0xFFFFFFFF),
        surfaceElevated = Color(0xFFE8F1F5),
        glass = Color(0xF2FFFFFF),
        glassBorder = Color(0x2E0891B2),
        textPrimary = Color(0xFF0B192C),
        textSecondary = Color(0xFF334155),
        textTertiary = Color(0xFF64748B),
        border = Color(0x1F0891B2),
        borderStrong = Color(0x3D0891B2),
        focus = Color(0xFF0891B2),
        accent = Color(0xFF0891B2),
        onAccent = Color(0xFFFFFFFF),
        accentMuted = Color(0x1A0891B2),
        userBubble = Color(0xFF0B192C),
        onUserBubble = Color(0xFFFFFFFF),
        success = Color(0xFF059669),
        onSuccess = Color(0xFFFFFFFF),
        successContainer = Color(0xFFECFDF5),
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
        scrim = Color(0x590B192C),
    )
}

fun goldColors(isDark: Boolean): MizanColors = if (isDark) {
    MizanColors(
        isDark = true,
        background = Color(0xFF0C0E14),
        surface = Color(0xFF161922),
        surfaceElevated = Color(0xFF202430),
        glass = Color(0xEB161922),
        glassBorder = Color(0x38F59E0B),
        textPrimary = Color(0xFFFFFBEB),
        textSecondary = Color(0xFFD1D5DB),
        textTertiary = Color(0xFF9CA3AF),
        border = Color(0x26F59E0B),
        borderStrong = Color(0x4DF59E0B),
        focus = Color(0xFFF59E0B),
        accent = Color(0xFFF59E0B),
        onAccent = Color(0xFF451A03),
        accentMuted = Color(0x2EF59E0B),
        userBubble = Color(0xFF282C37),
        onUserBubble = Color(0xFFFFFBEB),
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
        neutral = Color(0xFF9CA3AF),
        scrim = Color(0xE6000000),
    )
} else {
    MizanColors(
        isDark = false,
        background = Color(0xFFFDFCF7),
        surface = Color(0xFFFFFFFF),
        surfaceElevated = Color(0xFFF8F5EB),
        glass = Color(0xF2FFFFFF),
        glassBorder = Color(0x33B45309),
        textPrimary = Color(0xFF1E1B18),
        textSecondary = Color(0xFF4B453D),
        textTertiary = Color(0xFF78716C),
        border = Color(0x24B45309),
        borderStrong = Color(0x42B45309),
        focus = Color(0xFFB45309),
        accent = Color(0xFFB45309),
        onAccent = Color(0xFFFFFFFF),
        accentMuted = Color(0x1AB45309),
        userBubble = Color(0xFF292524),
        onUserBubble = Color(0xFFFFFFFF),
        success = Color(0xFF059669),
        onSuccess = Color(0xFFFFFFFF),
        successContainer = Color(0xFFECFDF5),
        warning = Color(0xFFD97706),
        onWarning = Color(0xFFFFFFFF),
        warningContainer = Color(0xFFFFFBEB),
        danger = Color(0xFFE11D48),
        onDanger = Color(0xFFFFFFFF),
        dangerContainer = Color(0xFFFFF1F2),
        info = Color(0xFF0284C7),
        onInfo = Color(0xFFFFFFFF),
        infoContainer = Color(0xFFF0F9FF),
        neutral = Color(0xFF78716C),
        scrim = Color(0x591E1B18),
    )
}

fun resolveThemeColors(preset: String, isDark: Boolean): MizanColors = when (preset) {
    "cyber_mizan" -> cyberColors(isDark)
    "sovereign_gold" -> goldColors(isDark)
    "emerald_gov" -> if (isDark) darkColors() else lightColors()
    "obsidian_dark" -> if (isDark) darkColors() else lightColors()
    else -> if (isDark) darkColors() else lightColors()
}

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
