package app.mizan.design.token

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The whole visual language of MIZAN in one data object.
 *
 * Screens never pick a colour: they read a token. A token is a *role*
 * (`accent`, `surface`, `dangerContainer`), never a hue, so a new preset is a
 * new palette and not a new set of screens.
 *
 * Rules this file enforces:
 *
 * - every preset defines both a light and a dark palette; there is no preset
 *   that silently falls back to another one
 * - `accent` is an action colour. It is not decoration on every surface
 * - status is a word plus a colour, never colour alone
 * - `accentSecondary` and `accentTertiary` exist so gradients are tokens too
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
    val accentSecondary: Color,
    val accentTertiary: Color,
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
) {

    /** Three-stop brand gradient. Used by marks, hero cards and progress. */
    fun accentBrush(): Brush = Brush.linearGradient(listOf(accentSecondary, accent, accentTertiary))

    /** Vertical wash behind a hero surface. Subtle on purpose. */
    fun heroBrush(): Brush = Brush.verticalGradient(
        listOf(
            accentMuted,
            surface.copy(alpha = if (isDark) 0.55f else 0.75f),
        ),
    )

    fun statusColor(tone: StatusToneToken): Color = when (tone) {
        StatusToneToken.NEUTRAL -> neutral
        StatusToneToken.ACCENT -> accent
        StatusToneToken.SUCCESS -> success
        StatusToneToken.WARNING -> warning
        StatusToneToken.DANGER -> danger
        StatusToneToken.INFO -> info
    }

    fun statusContainer(tone: StatusToneToken): Color = when (tone) {
        StatusToneToken.NEUTRAL -> surfaceElevated
        StatusToneToken.ACCENT -> accentMuted
        StatusToneToken.SUCCESS -> successContainer
        StatusToneToken.WARNING -> warningContainer
        StatusToneToken.DANGER -> dangerContainer
        StatusToneToken.INFO -> infoContainer
    }

    fun onStatus(tone: StatusToneToken): Color = when (tone) {
        StatusToneToken.NEUTRAL -> textPrimary
        StatusToneToken.ACCENT -> onAccent
        StatusToneToken.SUCCESS -> onSuccess
        StatusToneToken.WARNING -> onWarning
        StatusToneToken.DANGER -> onDanger
        StatusToneToken.INFO -> onInfo
    }
}

enum class StatusToneToken { NEUTRAL, ACCENT, SUCCESS, WARNING, DANGER, INFO }

/** The presets the Account screen can select. */
object ThemePresets {
    const val CYBER_MIZAN = "cyber_mizan"
    const val EMERALD_GOV = "emerald_gov"
    const val ROYAL_INDIGO = "royal_indigo"
    const val SOVEREIGN_GOLD = "sovereign_gold"
    const val CRIMSON_LEDGER = "crimson_ledger"
    const val OBSIDIAN_DARK = "obsidian_dark"
    const val SYSTEM_DYNAMIC = "system_dynamic"
    const val DEFAULT = CYBER_MIZAN

    val all: List<String> = listOf(
        CYBER_MIZAN,
        EMERALD_GOV,
        ROYAL_INDIGO,
        SOVEREIGN_GOLD,
        CRIMSON_LEDGER,
        OBSIDIAN_DARK,
        SYSTEM_DYNAMIC,
    )
}

/**
 * What a preset author supplies. Everything else — borders, containers,
 * scrim, focus — is derived, so a palette cannot be half finished.
 */
private data class PaletteSeed(
    val accent: Color,
    val accentSecondary: Color,
    val accentTertiary: Color,
    val onAccent: Color,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val glass: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val ink: Color,
    val userBubble: Color,
    val onUserBubble: Color,
)

private fun PaletteSeed.materialize(isDark: Boolean): MizanColors {
    val status = if (isDark) DarkStatus else LightStatus
    return MizanColors(
        isDark = isDark,
        background = background,
        surface = surface,
        surfaceElevated = surfaceElevated,
        glass = glass,
        glassBorder = ink.copy(alpha = if (isDark) 0.16f else 0.11f),
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textTertiary = textTertiary,
        border = ink.copy(alpha = if (isDark) 0.13f else 0.07f),
        borderStrong = ink.copy(alpha = if (isDark) 0.26f else 0.15f),
        focus = accent,
        accent = accent,
        onAccent = onAccent,
        accentMuted = accent.copy(alpha = if (isDark) 0.18f else 0.11f),
        accentSecondary = accentSecondary,
        accentTertiary = accentTertiary,
        userBubble = userBubble,
        onUserBubble = onUserBubble,
        success = status.success,
        onSuccess = status.onSuccess,
        successContainer = status.successContainer,
        warning = status.warning,
        onWarning = status.onWarning,
        warningContainer = status.warningContainer,
        danger = status.danger,
        onDanger = status.onDanger,
        dangerContainer = status.dangerContainer,
        info = status.info,
        onInfo = status.onInfo,
        infoContainer = status.infoContainer,
        neutral = textTertiary,
        scrim = Color.Black.copy(alpha = if (isDark) 0.78f else 0.42f),
    )
}

private object LightStatus {
    val success = Color(0xFF047857)
    val onSuccess = Color(0xFFFFFFFF)
    val successContainer = Color(0xFFE7F8F1)
    val warning = Color(0xFFB45309)
    val onWarning = Color(0xFFFFFFFF)
    val warningContainer = Color(0xFFFEF3E2)
    val danger = Color(0xFFBE123C)
    val onDanger = Color(0xFFFFFFFF)
    val dangerContainer = Color(0xFFFDE8EC)
    val info = Color(0xFF0369A1)
    val onInfo = Color(0xFFFFFFFF)
    val infoContainer = Color(0xFFE6F2FB)
}

private object DarkStatus {
    val success = Color(0xFF34D399)
    val onSuccess = Color(0xFF04231A)
    val successContainer = Color(0xFF073B2E)
    val warning = Color(0xFFFBBF24)
    val onWarning = Color(0xFF3B2503)
    val warningContainer = Color(0xFF402D06)
    val danger = Color(0xFFFB7185)
    val onDanger = Color(0xFF3F0A17)
    val dangerContainer = Color(0xFF4A0E1E)
    val info = Color(0xFF38BDF8)
    val onInfo = Color(0xFF04283A)
    val infoContainer = Color(0xFF08324A)
}

// ---------------------------------------------------------------------------
// Presets. Each one defines light and dark. None of them borrows another.
// ---------------------------------------------------------------------------

private fun cyberLight() = PaletteSeed(
    accent = Color(0xFF0E7490),
    accentSecondary = Color(0xFF22D3EE),
    accentTertiary = Color(0xFF0F766E),
    onAccent = Color(0xFFFFFFFF),
    background = Color(0xFFF4F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFEAF2F6),
    glass = Color(0xF2FFFFFF),
    textPrimary = Color(0xFF0B192C),
    textSecondary = Color(0xFF3D5165),
    textTertiary = Color(0xFF6B8296),
    ink = Color(0xFF0B192C),
    userBubble = Color(0xFF0B192C),
    onUserBubble = Color(0xFFF8FDFF),
)

private fun cyberDark() = PaletteSeed(
    accent = Color(0xFF22D3EE),
    accentSecondary = Color(0xFF67E8F9),
    accentTertiary = Color(0xFF0E9F9F),
    onAccent = Color(0xFF04212B),
    background = Color(0xFF060D16),
    surface = Color(0xFF0E1724),
    surfaceElevated = Color(0xFF152234),
    glass = Color(0xDE0E1724),
    textPrimary = Color(0xFFEAF6FB),
    textSecondary = Color(0xFF9DB4C6),
    textTertiary = Color(0xFF6C8698),
    ink = Color(0xFF000000),
    userBubble = Color(0xFF16283A),
    onUserBubble = Color(0xFFEAF6FB),
)

private fun emeraldLight() = PaletteSeed(
    accent = Color(0xFF047857),
    accentSecondary = Color(0xFF34D399),
    accentTertiary = Color(0xFF059669),
    onAccent = Color(0xFFFFFFFF),
    background = Color(0xFFF3FAF6),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFE7F4ED),
    glass = Color(0xF2FFFFFF),
    textPrimary = Color(0xFF08251B),
    textSecondary = Color(0xFF3B5A4E),
    textTertiary = Color(0xFF6A8A7C),
    ink = Color(0xFF08251B),
    userBubble = Color(0xFF08251B),
    onUserBubble = Color(0xFFF1FBF6),
)

private fun emeraldDark() = PaletteSeed(
    accent = Color(0xFF34D399),
    accentSecondary = Color(0xFF6EE7B7),
    accentTertiary = Color(0xFF0F9E76),
    onAccent = Color(0xFF04231A),
    background = Color(0xFF05130E),
    surface = Color(0xFF0C1F18),
    surfaceElevated = Color(0xFF123026),
    glass = Color(0xDE0C1F18),
    textPrimary = Color(0xFFE9FBF3),
    textSecondary = Color(0xFF9DBCAA),
    textTertiary = Color(0xFF6B8E7C),
    ink = Color(0xFF000000),
    userBubble = Color(0xFF123026),
    onUserBubble = Color(0xFFE9FBF3),
)

private fun indigoLight() = PaletteSeed(
    accent = Color(0xFF4338CA),
    accentSecondary = Color(0xFF818CF8),
    accentTertiary = Color(0xFF6D28D9),
    onAccent = Color(0xFFFFFFFF),
    background = Color(0xFFF6F6FD),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFEDECFA),
    glass = Color(0xF2FFFFFF),
    textPrimary = Color(0xFF141338),
    textSecondary = Color(0xFF464466),
    textTertiary = Color(0xFF737198),
    ink = Color(0xFF141338),
    userBubble = Color(0xFF141338),
    onUserBubble = Color(0xFFF5F5FF),
)

private fun indigoDark() = PaletteSeed(
    accent = Color(0xFF818CF8),
    accentSecondary = Color(0xFFA5B4FC),
    accentTertiary = Color(0xFF7C3AED),
    onAccent = Color(0xFF0B0A24),
    background = Color(0xFF08081A),
    surface = Color(0xFF12122B),
    surfaceElevated = Color(0xFF1B1B3C),
    glass = Color(0xDE12122B),
    textPrimary = Color(0xFFF0F0FF),
    textSecondary = Color(0xFFA9A8CC),
    textTertiary = Color(0xFF7A79A0),
    ink = Color(0xFF000000),
    userBubble = Color(0xFF1B1B3C),
    onUserBubble = Color(0xFFF0F0FF),
)

private fun goldLight() = PaletteSeed(
    accent = Color(0xFFB45309),
    accentSecondary = Color(0xFFFBBF24),
    accentTertiary = Color(0xFFD97706),
    onAccent = Color(0xFFFFFFFF),
    background = Color(0xFFFDFBF5),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFF7F0E2),
    glass = Color(0xF2FFFFFF),
    textPrimary = Color(0xFF241B0E),
    textSecondary = Color(0xFF5A4A34),
    textTertiary = Color(0xFF8A7860),
    ink = Color(0xFF241B0E),
    userBubble = Color(0xFF241B0E),
    onUserBubble = Color(0xFFFDF6E9),
)

private fun goldDark() = PaletteSeed(
    accent = Color(0xFFFBBF24),
    accentSecondary = Color(0xFFFDE68A),
    accentTertiary = Color(0xFFD97706),
    onAccent = Color(0xFF3B2503),
    background = Color(0xFF14100A),
    surface = Color(0xFF1E1810),
    surfaceElevated = Color(0xFF2B2216),
    glass = Color(0xDE1E1810),
    textPrimary = Color(0xFFFDF6E9),
    textSecondary = Color(0xFFC6B79C),
    textTertiary = Color(0xFF93866F),
    ink = Color(0xFF000000),
    userBubble = Color(0xFF2B2216),
    onUserBubble = Color(0xFFFDF6E9),
)

private fun crimsonLight() = PaletteSeed(
    accent = Color(0xFFBE123C),
    accentSecondary = Color(0xFFFB7185),
    accentTertiary = Color(0xFFE11D48),
    onAccent = Color(0xFFFFFFFF),
    background = Color(0xFFFDF6F7),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFAE9ED),
    glass = Color(0xF2FFFFFF),
    textPrimary = Color(0xFF2B0A14),
    textSecondary = Color(0xFF5F3441),
    textTertiary = Color(0xFF8E6A75),
    ink = Color(0xFF2B0A14),
    userBubble = Color(0xFF2B0A14),
    onUserBubble = Color(0xFFFEF2F4),
)

private fun crimsonDark() = PaletteSeed(
    accent = Color(0xFFFB7185),
    accentSecondary = Color(0xFFFDA4AF),
    accentTertiary = Color(0xFFE11D48),
    onAccent = Color(0xFF3F0A17),
    background = Color(0xFF14080C),
    surface = Color(0xFF1E1015),
    surfaceElevated = Color(0xFF2C1620),
    glass = Color(0xDE1E1015),
    textPrimary = Color(0xFFFEF2F4),
    textSecondary = Color(0xFFC7A2AC),
    textTertiary = Color(0xFF957681),
    ink = Color(0xFF000000),
    userBubble = Color(0xFF2C1620),
    onUserBubble = Color(0xFFFEF2F4),
)

/** True black, for OLED. Ice blue accent so it does not look like a bug. */
private fun obsidianLight() = PaletteSeed(
    accent = Color(0xFF334155),
    accentSecondary = Color(0xFF94A3B8),
    accentTertiary = Color(0xFF0F172A),
    onAccent = Color(0xFFFFFFFF),
    background = Color(0xFFF6F7F9),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFECEEF2),
    glass = Color(0xF2FFFFFF),
    textPrimary = Color(0xFF0B0D10),
    textSecondary = Color(0xFF454A52),
    textTertiary = Color(0xFF767C86),
    ink = Color(0xFF0B0D10),
    userBubble = Color(0xFF0B0D10),
    onUserBubble = Color(0xFFF8F9FB),
)

private fun obsidianDark() = PaletteSeed(
    accent = Color(0xFF93C5FD),
    accentSecondary = Color(0xFFE2E8F0),
    accentTertiary = Color(0xFF38BDF8),
    onAccent = Color(0xFF04121F),
    background = Color(0xFF000000),
    surface = Color(0xFF0A0A0A),
    surfaceElevated = Color(0xFF151515),
    glass = Color(0xE00A0A0A),
    textPrimary = Color(0xFFF5F7FA),
    textSecondary = Color(0xFFA6ADB8),
    textTertiary = Color(0xFF737A85),
    ink = Color(0xFF000000),
    userBubble = Color(0xFF151515),
    onUserBubble = Color(0xFFF5F7FA),
)

/** Resolves a preset. Every branch returns a real palette. */
fun resolveThemeColors(preset: String, isDark: Boolean): MizanColors = when (preset) {
    ThemePresets.CYBER_MIZAN -> if (isDark) cyberDark().materialize(true) else cyberLight().materialize(false)
    ThemePresets.EMERALD_GOV -> if (isDark) emeraldDark().materialize(true) else emeraldLight().materialize(false)
    ThemePresets.ROYAL_INDIGO -> if (isDark) indigoDark().materialize(true) else indigoLight().materialize(false)
    ThemePresets.SOVEREIGN_GOLD -> if (isDark) goldDark().materialize(true) else goldLight().materialize(false)
    ThemePresets.CRIMSON_LEDGER -> if (isDark) crimsonDark().materialize(true) else crimsonLight().materialize(false)
    ThemePresets.OBSIDIAN_DARK -> if (isDark) obsidianDark().materialize(true) else obsidianLight().materialize(false)
    else -> if (isDark) cyberDark().materialize(true) else cyberLight().materialize(false)
}

/** Older builds and saved preferences may hold these names. */
fun normalizePreset(preset: String?): String = when (preset) {
    ThemePresets.CYBER_MIZAN,
    ThemePresets.EMERALD_GOV,
    ThemePresets.ROYAL_INDIGO,
    ThemePresets.SOVEREIGN_GOLD,
    ThemePresets.CRIMSON_LEDGER,
    ThemePresets.OBSIDIAN_DARK,
    ThemePresets.SYSTEM_DYNAMIC,
    -> preset
    else -> ThemePresets.DEFAULT
}

// ---------------------------------------------------------------------------
// Spacing, shape, elevation
// ---------------------------------------------------------------------------

object Space {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp
    val huge: Dp = 48.dp
}

object Elevation {
    val flat: Dp = 0.dp
    val card: Dp = 1.dp
    val raised: Dp = 4.dp
    val floating: Dp = 10.dp
    val modal: Dp = 20.dp
}

// ---------------------------------------------------------------------------
// Motion
// ---------------------------------------------------------------------------

enum class MotionToken {
    INSTANT,
    FAST,
    STANDARD,
    EMPHASIZED,
    TRANSITION,
    MODAL,
    NAVIGATION,
    FEEDBACK,
}

object Motion {
    /**
     * Reduced motion collapses every duration to 1 ms. It does not remove the
     * animation, because a state that appears instantly is its own confusion;
     * it makes the change legible instead of decorative.
     */
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

    /** Staggered reveal delay for list items, capped so long lists stay snappy. */
    fun stagger(index: Int, reduced: Boolean): Int {
        if (reduced) return 0
        return (index * 26).coerceAtMost(240)
    }
}
