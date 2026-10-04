package dev.npn

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import dev.npn.app.App
import dev.npn.app.di.AppEnvironment
import dev.npn.app.platform.LocalDocumentScanner
import dev.npn.core.util.AndroidLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var env: AppEnvironment
    private lateinit var scanner: MlKitDocumentScanner

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        env = NpnApplication.environment(this)
        // Must register the ActivityResult launcher before the activity is STARTED.
        scanner = MlKitDocumentScanner(this)

        setContent {
            CompositionLocalProvider(LocalDocumentScanner provides scanner) {
                App(env)
            }
        }

        // Import share/open intents that started this activity.
        handleIntent(intent)
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
        lifecycleScope.launch {
            val result =
                runCatchingCancellable { IntentImport.import(this@MainActivity, env, importIntent) }.getOrNull()
            when {
                result == null -> {
                    toast(getString(R.string.import_library_required))
                }

                result.isEmpty -> {
                    toast(getString(R.string.import_nothing_found))
                }

                else -> {
                    toast(importSummary(result).ifEmpty { getString(R.string.import_complete) })
                }
            }
        }
    }

    private fun importSummary(result: IntentImport.Result): String =
        buildList {
            if (result.imported > 0) {
                add(
                    resources.getQuantityString(
                        R.plurals.import_summary_imported,
                        result.imported,
                        result.imported,
                    ),
                )
            }
            if (result.duplicates > 0) {
                add(
                    resources.getQuantityString(
                        R.plurals.import_summary_duplicate,
                        result.duplicates,
                        result.duplicates,
                    ),
                )
            }
            if (result.failed > 0) {
                add(
                    resources.getQuantityString(
                        R.plurals.import_summary_failed,
                        result.failed,
                        result.failed,
                    ),
                )
            }
        }.joinToString(getString(R.string.import_summary_separator))

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        var lastResumeRescan = 0L
        const val RESUME_RESCAN_MIN_INTERVAL_MS = 15_000L
    }
}
