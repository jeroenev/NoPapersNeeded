package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/**
 * Whether the app may read and write plain folders on device storage ([FolderAccess.DIRECT]).
 *
 * Desktop always may. Android needs a grant: "All files access" (`MANAGE_EXTERNAL_STORAGE`) from
 * Android 11 on, the legacy storage runtime permission before that. Without it the process can
 * still create files under `Documents/`, but it cannot see the ones a sync tool wrote next to
 * them, which is precisely the case the direct mode exists for, so the UI treats "not granted"
 * as "not usable yet" rather than silently opening a half-visible library.
 */
@Stable
class StorageAccessState(
    /** False when the platform never asks (desktop). */
    val required: Boolean,
    val granted: Boolean,
    /** Open the platform's grant flow; [granted] refreshes when the app comes back. */
    val request: () -> Unit,
) {
    val usable: Boolean get() = !required || granted
}

@Composable
expect fun rememberStorageAccess(): StorageAccessState
