package dev.npn

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import dev.npn.app.App
import dev.npn.app.di.AppEnvironment
import dev.npn.app.platform.LocalDocumentScanner
import kotlinx.coroutines.CancellationException
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

    private fun handleIntent(intent: Intent?) {
        if (!IntentImport.isImportIntent(intent)) return
        val importIntent = intent ?: return
        lifecycleScope.launch {
            val result =
                try {
                    IntentImport.import(this@MainActivity, env, importIntent)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
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
}
