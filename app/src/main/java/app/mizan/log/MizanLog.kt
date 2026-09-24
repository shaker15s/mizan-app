package app.mizan.log

import android.util.Log
import app.mizan.BuildConfig
import app.mizan.integration.http.Redactor

/**
 * Engineering log. Not an audit record. Release builds drop debug.
 */
object MizanLog {
    fun debug(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, Redactor.redact(message))
    }

    fun info(tag: String, message: String) {
        Log.i(tag, Redactor.redact(message))
    }

    fun warn(tag: String, message: String) {
        Log.w(tag, Redactor.redact(message))
    }

    fun error(tag: String, message: String) {
        Log.e(tag, Redactor.redact(message))
    }
}

object StartupTrace {
    val startedAtElapsed: Long = android.os.SystemClock.elapsedRealtime()
    var firstFrameMs: Long? = null
}
