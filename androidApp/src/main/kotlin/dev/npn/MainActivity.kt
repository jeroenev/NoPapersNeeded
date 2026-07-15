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
            val result = runCatching { IntentImport.import(this@MainActivity, env, importIntent) }
                .getOrNull()
            when {
                result == null ->
                    toast("Set up a library first, then share again")

                result.total == 0 ->
                    toast("Nothing to import")

                else -> {
                    val parts = buildList {
                        if (result.imported > 0) add("${result.imported} imported")
                        if (result.duplicates > 0) add("${result.duplicates} duplicate")
                        if (result.failed > 0) add("${result.failed} failed")
                    }
                    toast(parts.joinToString(", ").ifEmpty { "Import complete" })
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
