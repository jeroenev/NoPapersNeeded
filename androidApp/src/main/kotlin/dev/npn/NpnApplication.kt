package dev.npn

import android.app.Application
import android.content.Context
import dev.npn.app.di.AppEnvironment
import dev.npn.app.di.createAppEnvironment
import dev.npn.core.util.AndroidLog
import dev.npn.core.util.error

/**
 * Holds the process-wide [AppEnvironment] singleton. Every activity (including one recreated on
 * rotation) and every WorkManager worker in the process obtains the same instance, so the opened
 * library session, OCR queue and coroutine scope are shared instead of rebuilt per component.
 *
 * FileKit is initialized automatically by its androidx.startup [InitializationProvider] (merged in
 * from filekit-core's manifest), so no explicit FileKit.init call is needed here.
 */
class NpnApplication : Application() {
    /** Lazily created so the first component that needs it (activity or worker) triggers it. */
    val environment: AppEnvironment by lazy { createAppEnvironment(this) }

    override fun onCreate() {
        super.onCreate()
        // Every later warning and error goes to logcat and to a size-capped file under filesDir,
        // so the diagnostics bundle can show what happened before a crash.
        AndroidLog.install(this)
        installCrashHandler()
        NpnWork.schedulePeriodic(this)
    }

    /**
     * Put an uncaught exception in the file log before the process dies. The previous handler still
     * runs, so the platform's own crash reporting keeps working where it exists.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            AndroidLog.error("Uncaught exception on ${thread.name}", failure)
            previous?.uncaughtException(thread, failure)
        }
    }

    companion object {
        /**
         * Obtain the singleton environment from any Android component (activity, worker). The
         * manifest names this class as the application, so every component's application context
         * is this instance.
         */
        fun environment(context: Context): AppEnvironment = (context.applicationContext as NpnApplication).environment
    }
}
