package dev.npn.core.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * [NpnLog] onto logcat, all under one tag so `adb logcat -s npn` shows everything the app logged.
 *
 * [install] attaches a size-capped file under the app's `filesDir`, so a warning or crash survives
 * the process and reaches the user's diagnostics bundle; before that it writes to logcat only.
 * The file is best effort, like the desktop one: a log call must never throw.
 */
object AndroidLog : NpnLog {
    private const val TAG = "npn"
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "npn.log"

    @Volatile
    private var file: RotatingLogFile? = null

    /**
     * Keep every later entry in a size-capped file under [context]'s `noBackupFilesDir`;
     * idempotent. The log names files, folders and endpoints, so it must stay off Auto Backup.
     */
    @Synchronized
    fun install(context: Context) {
        if (file != null) return
        file =
            RotatingLogFile(
                File(File(context.applicationContext.noBackupFilesDir, LOG_DIR), LOG_FILE).toPath(),
            )
    }

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
        // The file exists for failures, not for a per-item play-by-play: keep its writes rare.
        if (level != NpnLog.Level.INFO) {
            file?.append(formatLogEntry(level, message, error, SystemEpochClock.nowMillis()))
        }
    }

    /** The recent log, for the user's diagnostics bundle; empty until [install] has run. */
    @Synchronized
    fun tail(maxBytes: Long = RotatingLogFile.DEFAULT_TAIL_BYTES): String = file?.tail(maxBytes).orEmpty()
}
