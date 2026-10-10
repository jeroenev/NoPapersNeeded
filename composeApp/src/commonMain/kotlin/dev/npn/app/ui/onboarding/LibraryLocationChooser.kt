package dev.npn.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.npn.app.platform.supportsSafStorage
import dev.npn.app.ui.common.SafStorageSwitch
import dev.npn.app.ui.common.StorageAccessState
import dev.npn.app.ui.common.TestTags
import dev.npn.app.ui.common.rememberLibraryFolderPicker
import dev.npn.app.ui.common.rememberStorageAccess
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_folder_access_not_kept_library
import no_papers_needed.composeapp.generated.resources.common_folder_not_on_device
import no_papers_needed.composeapp.generated.resources.common_settings_advanced
import no_papers_needed.composeapp.generated.resources.common_storage_access_grant
import no_papers_needed.composeapp.generated.resources.common_storage_access_help
import no_papers_needed.composeapp.generated.resources.common_storage_access_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Where a library is chosen, for onboarding and for a saved library that failed to open: Android's
 * all-files grant that a folder on device storage needs, the folder pickers, the advanced Storage
 * Access Framework switch, and why a picked folder was not taken: it is not on the device, or
 * Android would not let the app keep its access to it.
 *
 * [choices] lays out the screen's own buttons, given the two pickers to launch: an existing library
 * folder, in the mode the switch selects ([onPick]), and the folder to create a new library in,
 * always on device storage ([onPickParent]).
 *
 * In direct mode the grant step stands in for [choices] until the grant is in place: picking a
 * folder the app then cannot read would only fail again. [requireStorageAccess] asks for the grant
 * in either mode, for a saved library on device storage that lost it.
 */
@Composable
internal fun LibraryLocationChooser(
    useSaf: Boolean,
    onUseSafChange: (Boolean) -> Unit,
    onPick: (location: String) -> Unit,
    modifier: Modifier = Modifier,
    onPickParent: (location: String) -> Unit = {},
    requireStorageAccess: Boolean = false,
    choices: @Composable (pickLibrary: () -> Unit, pickParent: () -> Unit) -> Unit,
) {
    val storage = rememberStorageAccess()
    // Why the last folder picked was not taken, until another one is.
    var refusal by remember { mutableStateOf<StringResource?>(null) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val pickLibrary =
        rememberLibraryFolderPicker(
            useSaf = useSaf,
            onPicked = {
                refusal = null
                onPick(it)
            },
            onUnusable = { refusal = Res.string.common_folder_not_on_device },
            onAccessNotKept = { refusal = Res.string.common_folder_access_not_kept_library },
        )
    val pickParent =
        rememberLibraryFolderPicker(
            useSaf = false,
            onPicked = {
                refusal = null
                onPickParent(it)
            },
            onUnusable = { refusal = Res.string.common_folder_not_on_device },
            onAccessNotKept = { refusal = Res.string.common_folder_access_not_kept_library },
        )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!storage.usable && (!useSaf || requireStorageAccess)) {
            StorageAccessStep(storage)
        }
        // A folder picked through the Storage Access Framework needs no grant.
        if (storage.usable || useSaf) {
            choices(pickLibrary, pickParent)
        }
        refusal?.let { reason ->
            Text(
                text = stringResource(reason),
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
                        refusal = null
                        onUseSafChange(enabled)
                    },
                )
            }
        }
    }
}

/** Android's all-files grant, which the direct mode cannot do without (see [StorageAccessState]). */
@Composable
private fun StorageAccessStep(
    storage: StorageAccessState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
        Button(onClick = storage.request, modifier = Modifier.testTag(TestTags.STORAGE_ACCESS_GRANT)) {
            Text(stringResource(Res.string.common_storage_access_grant))
        }
    }
}
