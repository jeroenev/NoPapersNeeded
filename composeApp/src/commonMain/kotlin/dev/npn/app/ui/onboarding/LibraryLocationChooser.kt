package dev.npn.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.StorageAccessState
import dev.npn.app.ui.common.TestTags
import dev.npn.app.ui.common.rememberLibraryFolderPicker
import dev.npn.app.ui.common.rememberStorageAccess
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_folder_not_on_device
import no_papers_needed.composeapp.generated.resources.common_storage_access_grant
import no_papers_needed.composeapp.generated.resources.common_storage_access_help
import no_papers_needed.composeapp.generated.resources.common_storage_access_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Where a library is chosen, for onboarding and for a saved library that failed to open: Android's
 * all-files grant that a folder on device storage needs, the folder pickers, and why a picked folder
 * was not taken: it is not on the device.
 *
 * [choices] lays out the screen's own buttons, given the pickers to launch ([LibraryPickers]): an
 * existing library folder ([onPick]), and the folder to create a new library in ([onPickParent]).
 *
 * The grant step stands in for [choices] until the grant is in place: picking a folder the app then
 * cannot read would only fail again. With [grantInChoices] the choices are always shown and ask for
 * the grant themselves, beside the choice that needs it ([LibraryPickers.storage]).
 */
@Composable
internal fun LibraryLocationChooser(
    onPick: (location: String) -> Unit,
    modifier: Modifier = Modifier,
    onPickParent: (location: String) -> Unit = {},
    grantInChoices: Boolean = false,
    choices: @Composable (LibraryPickers) -> Unit,
) {
    val storage = rememberStorageAccess()
    // Why the last folder picked was not taken, until another one is.
    var refusal by remember { mutableStateOf<StringResource?>(null) }
    val pickLibrary =
        rememberLibraryFolderPicker(
            onPick = {
                refusal = null
                onPick(it)
            },
            onUnusable = { refusal = Res.string.common_folder_not_on_device },
        )
    val pickParent =
        rememberLibraryFolderPicker(
            onPick = {
                refusal = null
                onPickParent(it)
            },
            onUnusable = { refusal = Res.string.common_folder_not_on_device },
        )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!storage.usable && !grantInChoices) {
            StorageAccessStep(storage)
        }
        if (storage.usable || grantInChoices) {
            choices(LibraryPickers(pickLibrary, pickParent, storage))
        }
        refusal?.let { reason ->
            Text(
                text = stringResource(reason),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * What a [LibraryLocationChooser]'s choices launch: the picker of an existing library folder
 * ([pickLibrary]), the picker of the folder to make a new one in ([pickParent]), and Android's
 * all-files grant ([storage]), for choices that ask for it themselves.
 */
@Stable
internal class LibraryPickers(
    val pickLibrary: () -> Unit,
    val pickParent: () -> Unit,
    val storage: StorageAccessState,
)

/**
 * Android's all-files grant, which a library on device storage cannot do without (see
 * [StorageAccessState]), under its own title unless [showTitle] is off (a choice that asks for it
 * under its own).
 */
@Composable
internal fun StorageAccessStep(
    storage: StorageAccessState,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showTitle) {
            Text(
                text = stringResource(Res.string.common_storage_access_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
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
