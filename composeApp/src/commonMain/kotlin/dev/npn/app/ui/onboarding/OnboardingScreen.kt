package dev.npn.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.npn.app.di.AppEnvironment
import dev.npn.app.ui.common.persistDirectoryAccess
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(env: AppEnvironment) {
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberDirectoryPickerLauncher { dir ->
        if (dir == null) return@rememberDirectoryPickerLauncher
        scope.launch {
            opening = true
            error = null
            val result = runCatching {
                val location = persistDirectoryAccess(dir)
                env.openLibrary(location)
            }
            opening = false
            result.onFailure { error = it.message ?: "Could not open the folder." }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "No papers needed",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Your documents live in a plain folder of PDFs. That folder is the " +
                    "source of truth: every device with it has everything.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Point Syncthing (or any sync tool) at the folder to keep devices in " +
                    "sync. Tags, dates and status are stored inside the folder too, so nothing " +
                    "is lost and sync conflicts on app data cannot happen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (opening) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                Text("Opening library...", style = MaterialTheme.typography.bodyMedium)
            } else {
                Button(
                    onClick = { picker.launch() },
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text("Choose library folder")
                }
            }

            if (error != null) {
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = { picker.launch() }) { Text("Try again") }
            }
        }
    }
}
