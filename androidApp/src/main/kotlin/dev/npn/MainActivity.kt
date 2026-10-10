package dev.npn

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import dev.npn.app.App
import dev.npn.app.di.AppEnvironment
import dev.npn.app.platform.ForegroundImport
import dev.npn.app.platform.LaunchImports
import dev.npn.app.platform.LocalDocumentScanner
import dev.npn.app.platform.importIntoLibrary
import dev.npn.core.app.ImportTally
import dev.npn.core.util.AndroidLog
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var env: AppEnvironment

    /** The token of this activity's launch import ([LaunchImports]), saved with its state while it runs. */
    private var launchImport: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        env = NpnApplication.environment(this)

        setContent {
            CompositionLocalProvider(LocalDocumentScanner provides MlKitDocumentScanner) {
                App(env)
            }
        }

        // Import the share/open intent that started this activity, once (see LaunchImports): again
        // after a recreation or a return from Recents only if a process death cut that import short.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val unfinished = savedInstanceState?.unfinishedLaunchImport()
        if (launchImports.importsLaunchIntent(savedInstanceState != null, fromHistory, unfinished)) {
            handleIntent(intent, launch = true)
        } else {
            launchImport = unfinished?.token
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        launchImports.unfinished(launchImport)?.let {
            outState.putString(STATE_LAUNCH_IMPORT, it.token)
            outState.putString(STATE_LAUNCH_IMPORT_PROCESS, it.process)
        }
    }

    private fun Bundle.unfinishedLaunchImport(): LaunchImports.Unfinished? {
        val token = getString(STATE_LAUNCH_IMPORT) ?: return null
        val process = getString(STATE_LAUNCH_IMPORT_PROCESS) ?: return null
        return LaunchImports.Unfinished(token, process)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, launch = false)
    }

    /**
     * Syncthing and other apps change the library while this app is in the background, and the
     * filesystem watch cannot be relied on to have seen it (the process may have been dead, and
     * inotify on the FUSE mount is best effort). A rescan on every return to the foreground is the
     * documented Syncthing coexistence rule; it is rate-limited so a quick app switch is free.
     */
    override fun onResume() {
        super.onResume()
        val session = env.session.value ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastResumeRescan < RESUME_RESCAN_MIN_INTERVAL_MS) return
        lastResumeRescan = now
        env.appScope.launch { AndroidLog.runLogged("Rescan on resume") { session.ingest.rescan() } }
    }

    /** Imports [intent]'s files; a [launch] import is the one this activity was launched with. */
    private fun handleIntent(
        intent: Intent?,
        launch: Boolean,
    ) {
        if (!IntentImport.isImportIntent(intent)) return
        val importIntent = intent ?: return
        val app = applicationContext
        val token = if (launch) launchImports.start().also { launchImport = it } else null
        // On the environment's scope, not the activity's: an activity recreated mid-import must not
        // cancel it, and one recreated in this same process does not import the intent again.
        env.appScope.launch {
            val outcome =
                try {
                    env.importIntoLibrary { session -> IntentImport.import(app, session, importIntent) }
                } finally {
                    token?.let(launchImports::finish)
                }
            if (outcome is ForegroundImport.Failed) AndroidLog.warn("Share import failed", outcome.error)
            val message = importMessage(inAppLanguage(app), outcome)
            withContext(Dispatchers.Main) { toast(app, message) }
            // The import is reported; the OCR and enrichment it queued finish in the background.
            if (outcome is ForegroundImport.Done && outcome.result.imported > 0) NpnWork.drainQueues(app)
        }
    }

    /**
     * [context] in the app's own language. The user may have set one apart from the system's, and
     * the activity that applies it to its own resources may be gone by the time an import ends.
     */
    private suspend fun inAppLanguage(context: Context): Context {
        val language =
            env.settings.settings
                .first()
                .appLanguage ?: return context
        val configuration =
            Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return context.createConfigurationContext(configuration)
    }

    private fun importMessage(
        context: Context,
        outcome: ForegroundImport<ImportTally>,
    ): String =
        when (outcome) {
            ForegroundImport.NoLibrary -> {
                context.getString(R.string.import_library_required)
            }

            is ForegroundImport.Failed -> {
                context.getString(R.string.import_failed)
            }

            is ForegroundImport.Done -> {
                val result = outcome.result
                if (result.total == 0) {
                    context.getString(R.string.import_nothing_found)
                } else {
                    importSummary(context, result).ifEmpty { context.getString(R.string.import_complete) }
                }
            }
        }

    private fun importSummary(
        context: Context,
        result: ImportTally,
    ): String =
        buildList {
            if (result.imported > 0) {
                add(
                    context.resources.getQuantityString(
                        R.plurals.import_summary_imported,
                        result.imported,
                        result.imported,
                    ),
                )
            }
            if (result.duplicates > 0) {
                add(
                    context.resources.getQuantityString(
                        R.plurals.import_summary_duplicate,
                        result.duplicates,
                        result.duplicates,
                    ),
                )
            }
            if (result.failed > 0) {
                add(
                    context.resources.getQuantityString(
                        R.plurals.import_summary_failed,
                        result.failed,
                        result.failed,
                    ),
                )
            }
        }.joinToString(context.getString(R.string.import_summary_separator))

    private fun toast(
        context: Context,
        message: String,
    ) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        var lastResumeRescan = 0L
        const val RESUME_RESCAN_MIN_INTERVAL_MS = 15_000L

        val launchImports = LaunchImports()
        const val STATE_LAUNCH_IMPORT = "dev.npn.launchImport"
        const val STATE_LAUNCH_IMPORT_PROCESS = "dev.npn.launchImportProcess"
    }
}
