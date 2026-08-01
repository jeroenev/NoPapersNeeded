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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.npn.app.di.AppEnvironment
import dev.npn.app.ui.common.TextInputDialog
import dev.npn.app.ui.common.createLibraryFolder
import dev.npn.app.ui.common.persistDirectoryAccess
import dev.npn.app.ui.common.resolve
import dev.npn.app.ui.common.supportsLibraryFolderCreation
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_app_name
import no_papers_needed.composeapp.generated.resources.common_choose_library_folder
import no_papers_needed.composeapp.generated.resources.common_could_not_open_library
import no_papers_needed.composeapp.generated.resources.common_create
import no_papers_needed.composeapp.generated.resources.common_create_new_library
import no_papers_needed.composeapp.generated.resources.common_new_library_folder_name
import no_papers_needed.composeapp.generated.resources.common_onboarding_intro
import no_papers_needed.composeapp.generated.resources.common_onboarding_sync
import no_papers_needed.composeapp.generated.resources.common_opening_library
import no_papers_needed.composeapp.generated.resources.common_try_again
import org.jetbrains.compose.resources.stringResource

@Composable
@Suppress("LongMethod")
fun OnboardingScreen(env: AppEnvironment) {
    val opening by env.opening.collectAsStateWithLifecycle()
    val error by env.openError.collectAsStateWithLifecycle()

    var createParent by remember { mutableStateOf<String?>(null) }
    var createError by remember { mutableStateOf<String?>(null) }

    // Extract the path synchronously in the picker callback, then hand the open to the
    // environment's long-lived scope. Launching on a composable scope here fails: recomposition
    // while the picker dismisses cancels it before the (suspending) library open completes.
    val picker =
        rememberDirectoryPickerLauncher { dir ->
            if (dir == null) return@rememberDirectoryPickerLauncher
            env.requestOpenLibrary(persistDirectoryAccess(dir))
        }

    // The create flow first picks the PARENT folder, then asks for a name via dialog. Like [picker]
    // the open after creation runs on the environment scope, not this composable's.
    val createPicker =
        rememberDirectoryPickerLauncher { dir ->
            if (dir == null) return@rememberDirectoryPickerLauncher
            createParent = persistDirectoryAccess(dir)
        }

    Column(
        modifier =
            Modifier
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
                text = stringResource(Res.string.common_app_name),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(Res.string.common_onboarding_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(Res.string.common_onboarding_sync),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (opening) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                Text(
                    stringResource(Res.string.common_opening_library),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Button(
                    onClick = { picker.launch() },
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(stringResource(Res.string.common_choose_library_folder))
                }
                if (supportsLibraryFolderCreation()) {
                    Button(onClick = { createPicker.launch() }) {
                        Text(stringResource(Res.string.common_create_new_library))
                    }
                }
            }

            if (error != null) {
                Text(
                    text = error!!.resolve(),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = { picker.launch() }) {
                    Text(stringResource(Res.string.common_try_again))
                }
            }

            createError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    createParent?.let { parent ->
        val createFailed = stringResource(Res.string.common_could_not_open_library)
        TextInputDialog(
            title = stringResource(Res.string.common_create_new_library),
            label = stringResource(Res.string.common_new_library_folder_name),
            initial = "My documents",
            confirmLabel = stringResource(Res.string.common_create),
            onConfirm = { name ->
                createParent = null
                env.appScope.launch {
                    val location = createLibraryFolder(parent, name)
                    createError = if (location != null) null else createFailed
                    if (location != null) env.requestOpenLibrary(location)
                }
            },
            onDismiss = { createParent = null },
        )
    }
}
