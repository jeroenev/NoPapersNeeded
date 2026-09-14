package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher

@Composable
internal actual fun rememberLibraryDirectoryPicker(
    localOnly: Boolean,
    onPicked: (PlatformFile?) -> Unit,
): () -> Unit {
    val picker = rememberDirectoryPickerLauncher(onResult = onPicked)
    return { picker.launch() }
}
