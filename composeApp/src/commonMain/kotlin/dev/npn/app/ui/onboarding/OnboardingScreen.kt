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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.npn.app.ui.common.SafStorageSwitch
import dev.npn.app.ui.common.StorageAccessState
import dev.npn.app.ui.common.TextInputDialog
import dev.npn.app.ui.common.createLibraryFolder
import dev.npn.app.ui.common.defaultLibraryLocation
import dev.npn.app.ui.common.rememberLibraryFolderPicker
import dev.npn.app.ui.common.rememberStorageAccess
import dev.npn.app.ui.common.resolve
import dev.npn.app.ui.common.supportsLibraryFolderCreation
import dev.npn.app.ui.common.supportsSafStorage
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_app_name
import no_papers_needed.composeapp.generated.resources.common_choose_library_folder
import no_papers_needed.composeapp.generated.resources.common_could_not_open_library
import no_papers_needed.composeapp.generated.resources.common_create
import no_papers_needed.composeapp.generated.resources.common_create_new_library
import no_papers_needed.composeapp.generated.resources.common_folder_not_on_device
import no_papers_needed.composeapp.generated.resources.common_new_library_folder_name
import no_papers_needed.composeapp.generated.resources.common_onboarding_intro
import no_papers_needed.composeapp.generated.resources.common_onboarding_recommended_help
import no_papers_needed.composeapp.generated.resources.common_onboarding_sync
import no_papers_needed.composeapp.generated.resources.common_onboarding_use_recommended
import no_papers_needed.composeapp.generated.resources.common_opening_library
import no_papers_needed.composeapp.generated.resources.common_settings_advanced
import no_papers_needed.composeapp.generated.resources.common_storage_access_grant
import no_papers_needed.composeapp.generated.resources.common_storage_access_help
import no_papers_needed.composeapp.generated.resources.common_storage_access_title
import no_papers_needed.composeapp.generated.resources.common_try_again
import no_papers_needed.composeapp.generated.resources.ux_library_name
import no_papers_needed.composeapp.generated.resources.ux_sync_help
import org.jetbrains.compose.resources.stringResource

/**
 * First-run screen: where the library lives. In direct mode (the default everywhere) the folder
 * is a plain path; Android first needs the all-files grant and proposes `Documents/NoPapersNeeded`.
 * The advanced Storage Access Framework switch is Android's escape hatch for folders that have no
 * path (cloud document providers) and is the only way to reach the SAF library implementation.
 */
@Composable
@Suppress("CyclomaticComplexMethod")
fun OnboardingScreen(env: AppEnvironment) {
    val opening by env.opening.collectAsStateWithLifecycle()
    val error by env.openError.collectAsStateWithLifecycle()
    val settings by env.settings.settings.collectAsStateWithLifecycle(null)
    val useSaf = settings?.useSafStorage == true
    val storage = rememberStorageAccess()

    var showSyncHelp by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var createParent by remember { mutableStateOf<String?>(null) }
    var createError by remember { mutableStateOf<String?>(null) }
    var pickError by remember { mutableStateOf(false) }

    // Extract the location synchronously in the picker callback, then hand the open to the
    // environment's long-lived scope. Launching on a composable scope here fails: recomposition
    // while the picker dismisses cancels it before the (suspending) library open completes.
    val pickExisting =
        rememberLibraryFolderPicker(
            useSaf = useSaf,
            onPicked = {
                pickError = false
                env.requestOpenLibrary(it)
            },
            onUnusable = { pickError = true },
        )

    // The create flow first picks the PARENT folder, then asks for a name via dialog. Like
    // [pickExisting] the open after creation runs on the environment scope, not this composable's.
    val pickCreateParent =
        rememberLibraryFolderPicker(
            useSaf = false,
            onPicked = {
                pickError = false
                createParent = it
            },
            onUnusable = { pickError = true },
        )

    val couldNotOpen = stringResource(Res.string.common_could_not_open_library)
    val recommended = if (useSaf) null else defaultLibraryLocation()

    fun openRecommended(location: String) {
        env.appScope.launch {
            val opened = createLibraryFolder(location.substringBeforeLast('/'), location.substringAfterLast('/'))
            createError = if (opened != null) null else couldNotOpen
            if (opened != null) env.requestOpenLibrary(opened)
        }
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
            TextButton(onClick = { showSyncHelp = !showSyncHelp }) {
                Text(stringResource(Res.string.ux_sync_help))
            }
            if (showSyncHelp) {
                Text(
                    text = stringResource(Res.string.common_onboarding_sync),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            when {
                opening -> {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                    Text(
                        stringResource(Res.string.common_opening_library),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                !useSaf && !storage.usable -> {
                    StorageAccessStep(storage)
                }

                else -> {
                    if (recommended != null) {
                        Button(onClick = { openRecommended(recommended) }) {
                            Text(stringResource(Res.string.common_onboarding_use_recommended))
                        }
                        Text(
                            text = stringResource(Res.string.common_onboarding_recommended_help, recommended),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    val canCreate = !useSaf && supportsLibraryFolderCreation()
                    if (canCreate && recommended == null) {
                        Button(onClick = pickCreateParent) {
                            Text(stringResource(Res.string.common_create_new_library))
                        }
                    }
                    val openButton: @Composable () -> Unit = {
                        Text(stringResource(Res.string.common_choose_library_folder))
                    }
                    if (recommended != null || canCreate) {
                        OutlinedButton(onClick = pickExisting, content = { openButton() })
                    } else {
                        Button(onClick = pickExisting, content = { openButton() })
                    }
                    if (canCreate && recommended != null) {
                        TextButton(onClick = pickCreateParent) {
                            Text(stringResource(Res.string.common_create_new_library))
                        }
                    }
                }
            }

            if (pickError) {
                Text(
                    text = stringResource(Res.string.common_folder_not_on_device),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            if (error != null) {
                Text(
                    text = error!!.resolve(),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = pickExisting) {
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

            if (supportsSafStorage()) {
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(stringResource(Res.string.common_settings_advanced))
                }
                if (showAdvanced) {
                    SafStorageSwitch(
                        checked = useSaf,
                        onCheckedChange = { enabled ->
                            pickError = false
                            env.appScope.launch { env.settings.edit { it.copy(useSafStorage = enabled) } }
                        },
                    )
                }
            }
        }
    }

    createParent?.let { parent ->
        TextInputDialog(
            title = stringResource(Res.string.common_create_new_library),
            label = stringResource(Res.string.common_new_library_folder_name),
            initial = stringResource(Res.string.ux_library_name),
            confirmLabel = stringResource(Res.string.common_create),
            onConfirm = { name ->
                createParent = null
                env.appScope.launch {
                    val location = createLibraryFolder(parent, name)
                    createError = if (location != null) null else couldNotOpen
                    if (location != null) env.requestOpenLibrary(location)
                }
            },
            onDismiss = { createParent = null },
        )
    }
}

/** Android's all-files grant, which the direct mode cannot do without (see [StorageAccessState]). */
@Composable
private fun StorageAccessStep(storage: StorageAccessState) {
    Text(
        text = stringResource(Res.string.common_storage_access_title),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(Res.string.common_storage_access_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Button(onClick = storage.request) {
        Text(stringResource(Res.string.common_storage_access_grant))
    }
}
