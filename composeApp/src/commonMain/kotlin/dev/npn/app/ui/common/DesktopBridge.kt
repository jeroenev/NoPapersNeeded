package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Desktop-only shell bridge between the native window layer (desktop entry point) and the shared
 * Compose UI. All state here is inert on non-desktop platforms.
 *
 * - [windowTitle]: lets the shared UI drive the native window title (e.g. the open document). The
 *   window reads it as state and is its only writer.
 * - [droppedFiles]: what was dropped on the window, PDFs and images, for the shared UI to import and
 *   report on like any other import.
 * - [dragging]: whether files are being dragged over the window now, so the shared UI shows the
 *   window as the drop target it is.
 */
object DesktopBridge {
    val windowTitle = MutableStateFlow<String?>(null)

    val droppedFiles = Channel<ImportRequest>(Channel.UNLIMITED)

    val dragging = MutableStateFlow(false)
}

/** Make [title] the native window title (desktop); null leaves the title to a screen below. */
@Composable
fun PublishWindowTitle(title: String?) {
    DisposableEffect(title) {
        if (title != null) DesktopBridge.windowTitle.value = title
        onDispose {}
    }
}
