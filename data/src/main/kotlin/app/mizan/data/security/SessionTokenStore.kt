package app.mizan.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Holds a short-lived MIZAN session token, if the service issued one.
 * ERP passwords and API keys are not accepted here.
 */
class SessionTokenStore(context: Context) {
    private val prefs: SharedPreferences? = open(context)

    fun read(): String? = prefs?.getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun write(token: String) {
        check(token.isNotBlank())
        val editor = prefs?.edit() ?: error("secure storage unavailable")
        editor.putString(KEY, token).apply()
    }

    fun clear() {
        prefs?.edit()?.remove(KEY)?.apply()
    }

    private fun open(context: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val FILE = "mizan_session"
        const val KEY = "session_token"
    }
}
