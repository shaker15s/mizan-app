package app.mizan.prefs

import android.content.Context

class UserPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var theme: String
        get() = prefs.getString(KEY_THEME, "light") ?: "light"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    var reducedMotion: Boolean
        get() = prefs.getBoolean(KEY_MOTION, false)
        set(value) = prefs.edit().putBoolean(KEY_MOTION, value).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING, value).apply()

    var simulateNextAmbiguous: Boolean
        get() = prefs.getBoolean(KEY_AMBIGUOUS, false)
        set(value) = prefs.edit().putBoolean(KEY_AMBIGUOUS, value).apply()

    var serviceUrlOverride: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    var lastSyncTimestampMillis: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_TIME, value).apply()

    var isServiceOnline: Boolean
        get() = prefs.getBoolean(KEY_IS_ONLINE, true)
        set(value) = prefs.edit().putBoolean(KEY_IS_ONLINE, value).apply()

    var biometricsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRICS, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRICS, value).apply()

    var activeThemePreset: String
        get() = prefs.getString(KEY_THEME_PRESET, "cyber_mizan") ?: "cyber_mizan"
        set(value) = prefs.edit().putString(KEY_THEME_PRESET, value).apply()

    var customSystemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()

    var aiModelSpeedTier: String
        get() = prefs.getString(KEY_AI_SPEED_TIER, "fast_tuned") ?: "fast_tuned"
        set(value) = prefs.edit().putString(KEY_AI_SPEED_TIER, value).apply()

    var developerProMode: Boolean
        get() = prefs.getBoolean(KEY_DEV_PRO_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEV_PRO_MODE, value).apply()

    companion object {
        const val FILE = "mizan_prefs"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_THEME = "theme"
        private const val KEY_MOTION = "reduced_motion"
        private const val KEY_ONBOARDING = "onboarding_done"
        private const val KEY_AMBIGUOUS = "simulate_ambiguous"
        private const val KEY_URL = "service_url"
        private const val KEY_LAST_SYNC_TIME = "last_sync_timestamp"
        private const val KEY_IS_ONLINE = "is_service_online"
        private const val KEY_BIOMETRICS = "biometrics_enabled"
        private const val KEY_THEME_PRESET = "theme_preset"
        private const val KEY_SYSTEM_PROMPT = "custom_system_prompt"
        private const val KEY_AI_SPEED_TIER = "ai_speed_tier"
        private const val KEY_DEV_PRO_MODE = "developer_pro_mode"

        const val DEFAULT_SYSTEM_PROMPT = """You are MIZAN, an autonomous governed ERP authority assistant.
Your sole mission is to parse business requests into deterministic ERP proposals for Odoo/ERP backends.
Enforce strict separation-of-duties (SoD), financial risk tiers, and cryptographic audit proofs.
Supported Tools:
- STOCK_AVAILABILITY (args: sku)
- CUSTOMER_SEARCH (args: query)
- CREATE_DRAFT_ORDER (args: customerName, amount, items)
- CANCEL_ORDER (args: orderId, reason)
- CREATE_INVOICE (args: orderId)
- REGISTER_PAYMENT (args: invoiceId, amount)
- SALES_SUMMARY (args: period)
Rules:
1. Never hallucinate default amounts or missing customer names.
2. If fields are missing, declare them immediately.
3. Keep tokens minimal: output structured arguments only."""
    }
}
