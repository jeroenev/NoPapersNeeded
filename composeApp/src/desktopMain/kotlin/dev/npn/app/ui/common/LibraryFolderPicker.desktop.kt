package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher

@Composable
internal actual fun rememberLibraryDirectoryPicker(
    localOnly: Boolean,
    onPick: (PlatformFile?) -> Unit,
): () -> Unit {
    val picker = rememberDirectoryPickerLauncher(onResult = onPick)
    return { picker.launch() }
}
