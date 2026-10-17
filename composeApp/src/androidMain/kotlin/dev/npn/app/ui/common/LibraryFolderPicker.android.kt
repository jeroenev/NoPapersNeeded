package dev.npn.app.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile

@Composable
internal actual fun rememberLibraryDirectoryPicker(
    localOnly: Boolean,
    onPick: (PlatformFile?) -> Unit,
): () -> Unit {
    // FileKit's Android directory picker does not expose intent extras.
    val contract = remember(localOnly) { LibraryDirectoryContract(localOnly) }
    val picker =
        rememberLauncherForActivityResult(contract) { uri ->
            onPick(uri?.let { PlatformFile(AndroidFile.UriWrapper(it)) })
        }
    return { picker.launch(null) }
}

private class LibraryDirectoryContract(
    private val localOnly: Boolean,
) : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(
        context: Context,
        input: Uri?,
    ): Intent =
        super.createIntent(context, input).apply {
            putExtra(Intent.EXTRA_LOCAL_ONLY, localOnly)
        }
}
