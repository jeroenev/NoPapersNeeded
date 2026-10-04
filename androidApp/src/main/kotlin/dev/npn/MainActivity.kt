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
import dev.npn.app.platform.LocalDocumentScanner
import dev.npn.app.platform.importIntoLibrary
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        env = NpnApplication.environment(this)

        setContent {
            CompositionLocalProvider(LocalDocumentScanner provides MlKitDocumentScanner) {
                App(env)
            }
        }

        // Import the share/open intent that started this activity. Android hands that same intent
        // back when the task is reopened from Recents, and when the activity is recreated (after
        // process death too); the first launch imported it already.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
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

    private fun handleIntent(intent: Intent?) {
        if (!IntentImport.isImportIntent(intent)) return
        val importIntent = intent ?: return
        val app = applicationContext
        // On the environment's scope, not the activity's: an activity recreated mid-import must not
        // cancel it, and the recreated one will not import the intent again.
        env.appScope.launch {
            val outcome = env.importIntoLibrary { session -> IntentImport.import(app, session, importIntent) }
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
        val language = env.settings.settings.first().appLanguage ?: return context
        val configuration =
            Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return context.createConfigurationContext(configuration)
    }

    private fun importMessage(
        context: Context,
        outcome: ForegroundImport<IntentImport.Result>,
    ): String =
        when (outcome) {
            ForegroundImport.NoLibrary -> context.getString(R.string.import_library_required)
            is ForegroundImport.Failed -> context.getString(R.string.import_failed)
            is ForegroundImport.Done -> {
                val result = outcome.result
                if (result.isEmpty) {
                    context.getString(R.string.import_nothing_found)
                } else {
                    importSummary(context, result).ifEmpty { context.getString(R.string.import_complete) }
                }
            }
        }

    private fun importSummary(
        context: Context,
        result: IntentImport.Result,
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
    }
}
