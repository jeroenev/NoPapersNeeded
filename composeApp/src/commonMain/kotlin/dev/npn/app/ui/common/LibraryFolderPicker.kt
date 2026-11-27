package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import dev.npn.app.platform.FolderAccess
import dev.npn.app.platform.FolderPick
import dev.npn.app.platform.persistDirectoryAccess
import io.github.vinceglb.filekit.PlatformFile

/**
 * The one folder picker for library locations: launches the platform directory dialog, resolves the
 * folder to its path on the device, and hands that location string to [onPick]. A folder with no
 * such path (a cloud provider's tree) goes to [onUnusable] instead, so every call site shows the same
 * explanation and keeps the library it had.
 *
 * Callbacks are read through [rememberUpdatedState]: the dialog result arrives after an arbitrary
 * delay.
 */
@Composable
fun rememberLibraryFolderPicker(
    onPick: (String) -> Unit,
    onUnusable: () -> Unit,
): () -> Unit {
    val currentOnPick by rememberUpdatedState(onPick)
    val currentOnUnusable by rememberUpdatedState(onUnusable)
    return rememberLibraryDirectoryPicker { dir ->
        if (dir == null) return@rememberLibraryDirectoryPicker
        when (val pick = persistDirectoryAccess(dir, FolderAccess.DIRECT)) {
            is FolderPick.Location -> currentOnPick(pick.value)
            FolderPick.Unusable, FolderPick.AccessNotKept -> currentOnUnusable()
        }
    }
}

/** Android offers local folders only; desktop uses its native directory picker. */
@Composable
internal expect fun rememberLibraryDirectoryPicker(onPick: (PlatformFile?) -> Unit): () -> Unit
