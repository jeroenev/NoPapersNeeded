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
internal actual fun rememberLibraryDirectoryPicker(onPick: (PlatformFile?) -> Unit): () -> Unit {
    // FileKit's Android directory picker does not expose intent extras.
    val contract = remember { LocalDirectoryContract() }
    val picker =
        rememberLauncherForActivityResult(contract) { uri ->
            onPick(uri?.let { PlatformFile(AndroidFile.UriWrapper(it)) })
        }
    return { picker.launch(null) }
}

/** The system folder picker, asked for folders on the device only: a library needs a path. */
private class LocalDirectoryContract : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(
        context: Context,
        input: Uri?,
    ): Intent =
        super.createIntent(context, input).apply {
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
}
