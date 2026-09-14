package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher

/**
 * The one folder picker for library locations: launches the platform directory dialog, persists
 * access in the mode the user's settings ask for, and hands the location string to [onPicked].
 * A folder that cannot be used in that mode (a cloud provider tree while in direct mode) goes to
 * [onUnusable] instead, so every call site shows the same explanation.
 *
 * Callbacks are read through [rememberUpdatedState]: the dialog result arrives after an arbitrary
 * delay, and the settings flag may have changed meanwhile.
 */
@Composable
fun rememberLibraryFolderPicker(
    useSaf: Boolean,
    onPicked: (String) -> Unit,
    onUnusable: () -> Unit,
): () -> Unit {
    val currentUseSaf by rememberUpdatedState(useSaf)
    val currentOnPicked by rememberUpdatedState(onPicked)
    val currentOnUnusable by rememberUpdatedState(onUnusable)
    val picker =
        rememberDirectoryPickerLauncher { dir ->
            if (dir == null) return@rememberDirectoryPickerLauncher
            val access = if (currentUseSaf) FolderAccess.SAF else FolderAccess.DIRECT
            val location = persistDirectoryAccess(dir, access)
            if (location == null) currentOnUnusable() else currentOnPicked(location)
        }
    return { picker.launch() }
}
