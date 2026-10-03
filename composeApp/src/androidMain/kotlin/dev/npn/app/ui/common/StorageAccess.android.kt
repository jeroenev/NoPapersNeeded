package dev.npn.app.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/** True when the app may read and write any folder on shared storage (see [StorageAccessState]). */
fun hasDirectStorageAccess(): Boolean = Environment.isExternalStorageManager()

/**
 * All-files access has no runtime prompt: the user flips a switch on the app's system settings
 * page, so the grant is re-read on every resume.
 */
@Composable
actual fun rememberStorageAccess(): StorageAccessState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasDirectStorageAccess()) }
    LifecycleResumeEffect(Unit) {
        granted = hasDirectStorageAccess()
        onPauseOrDispose { }
    }
    return remember(granted) {
        StorageAccessState(required = true, granted = granted, request = { openAllFilesAccessSettings(context) })
    }
}

private fun openAllFilesAccessSettings(context: Context) {
    val forApp =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )
    val opened = runCatching { context.startActivity(forApp) }.isSuccess
    if (!opened) {
        // Some OEM settings apps lack the per-app page; the global list always exists.
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}
