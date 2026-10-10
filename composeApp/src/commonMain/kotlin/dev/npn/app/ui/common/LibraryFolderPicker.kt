package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import dev.npn.app.platform.FolderAccess
import dev.npn.app.platform.FolderPick
import dev.npn.app.platform.persistDirectoryAccess
import io.github.vinceglb.filekit.PlatformFile

/**
 * The one folder picker for library locations: launches the platform directory dialog, persists
 * access in the mode the user's settings ask for, and hands the location string to [onPicked].
 * A folder that cannot be used in that mode (a cloud provider tree while in direct mode) goes to
 * [onUnusable] instead, and one whose access Android would not keep to [onAccessNotKept], so every
 * call site shows the same explanations and keeps the library it had.
 *
 * Callbacks are read through [rememberUpdatedState]: the dialog result arrives after an arbitrary
 * delay, and the settings flag may have changed meanwhile.
 */
@Composable
fun rememberLibraryFolderPicker(
    useSaf: Boolean,
    onPicked: (String) -> Unit,
    onUnusable: () -> Unit,
    onAccessNotKept: () -> Unit,
): () -> Unit {
    val currentUseSaf by rememberUpdatedState(useSaf)
    val currentOnPicked by rememberUpdatedState(onPicked)
    val currentOnUnusable by rememberUpdatedState(onUnusable)
    val currentOnAccessNotKept by rememberUpdatedState(onAccessNotKept)
    return rememberLibraryDirectoryPicker(localOnly = !useSaf) { dir ->
        if (dir == null) return@rememberLibraryDirectoryPicker
        val access = if (currentUseSaf) FolderAccess.SAF else FolderAccess.DIRECT
        when (val pick = persistDirectoryAccess(dir, access)) {
            is FolderPick.Location -> currentOnPicked(pick.value)
            FolderPick.Unusable -> currentOnUnusable()
            FolderPick.AccessNotKept -> currentOnAccessNotKept()
        }
    }
}

/** Android filters remote providers in direct mode; desktop uses its native directory picker. */
@Composable
internal expect fun rememberLibraryDirectoryPicker(
    localOnly: Boolean,
    onPicked: (PlatformFile?) -> Unit,
): () -> Unit
