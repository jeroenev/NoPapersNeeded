package dev.npn.app.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/** True when the app may read and write any folder on shared storage (see [StorageAccessState]). */
fun hasDirectStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

/**
 * Android 11+ has no runtime prompt for all-files access: the user flips a switch on the app's
 * system settings page, so the grant is re-read on every resume. Before Android 11 the legacy
 * storage permission is an ordinary runtime request.
 */
@Composable
actual fun rememberStorageAccess(): StorageAccessState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasDirectStorageAccess(context)) }
    LifecycleResumeEffect(Unit) {
        granted = hasDirectStorageAccess(context)
        onPauseOrDispose { }
    }
    val legacyPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val request: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openAllFilesAccessSettings(context)
        } else {
            legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    return remember(granted) { StorageAccessState(required = true, granted = granted, request = request) }
}

private fun openAllFilesAccessSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
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
