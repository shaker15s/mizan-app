package app.mizan

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.fragment.app.FragmentActivity
import app.mizan.design.theme.MizanTheme
import app.mizan.feature.shell.MizanShell
import app.mizan.log.StartupTrace
import app.mizan.prefs.UserPreferences
import java.util.Locale

class MainActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.wrapMizanLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as MizanApplication
        window.decorView.post {
            if (StartupTrace.firstFrameMs == null) {
                StartupTrace.firstFrameMs =
                    android.os.SystemClock.elapsedRealtime() - StartupTrace.startedAtElapsed
            }
        }
        setContent {
            val prefs = app.graph.preferences
            val dark = when (prefs.theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            val arabic = when (prefs.language) {
                "ar" -> true
                "en" -> false
                else -> Locale.getDefault().language == "ar"
            }
            val reduced = prefs.reducedMotion || animatorScale() == 0f
            MizanTheme(dark = dark, arabic = arabic, reducedMotion = reduced) {
                MizanShell(
                    graph = app.graph,
                    activity = this,
                    onPreferencesChanged = { recreate() },
                )
            }
        }
    }

    private fun animatorScale(): Float =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}

internal fun Context.wrapMizanLocale(): Context {
    val language = getSharedPreferences(UserPreferences.FILE, Context.MODE_PRIVATE)
        .getString("language", "system")
    val locale = when (language) {
        "ar" -> Locale("ar")
        "en" -> Locale.ENGLISH
        else -> return this
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)
    return createConfigurationContext(config)
}
