package dev.npn

import android.app.Application
import dev.npn.app.di.AppEnvironment
import dev.npn.app.di.createAppEnvironment

/**
 * Holds the process-wide [AppEnvironment] singleton. Activity recreation (rotation, process
 * restart) and WorkManager workers all obtain the same instance, so the opened library session,
 * OCR queue and coroutine scope are shared instead of rebuilt per component.
 *
 * FileKit is initialized automatically by its androidx.startup [InitializationProvider] (merged in
 * from filekit-core's manifest), so no explicit FileKit.init call is needed here.
 */
class NpnApplication : Application() {
    /** Lazily created so the first component that needs it (activity or worker) triggers it. */
    val environment: AppEnvironment by lazy { createAppEnvironment(this) }

    override fun onCreate() {
        super.onCreate()
        NpnWork.schedulePeriodic(this)
    }

    companion object {
        /**
         * Obtain the singleton environment from any Android component (activity, worker). The
         * manifest names this class as the application, so every component's application context
         * is this instance.
         */
        fun environment(context: android.content.Context): AppEnvironment =
            (context.applicationContext as NpnApplication).environment
    }
}
