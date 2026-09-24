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

    companion object {
        const val FILE = "mizan_prefs"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_THEME = "theme"
        private const val KEY_MOTION = "reduced_motion"
        private const val KEY_ONBOARDING = "onboarding_done"
        private const val KEY_AMBIGUOUS = "simulate_ambiguous"
        private const val KEY_URL = "service_url"
    }
}
