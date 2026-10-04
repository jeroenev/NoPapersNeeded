package dev.npn.core.util

import android.util.Log

/** [NpnLog] onto logcat, all under one tag so `adb logcat -s npn` shows everything the app logged. */
object AndroidLog : NpnLog {
    private const val TAG = "npn"

    override fun log(
        level: NpnLog.Level,
        message: String,
        error: Throwable?,
    ) {
        when (level) {
            NpnLog.Level.INFO -> Log.i(TAG, message, error)
            NpnLog.Level.WARN -> Log.w(TAG, message, error)
            NpnLog.Level.ERROR -> Log.e(TAG, message, error)
        }
    }
}
