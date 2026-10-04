package dev.npn.app.ui.common

import dev.npn.core.fs.ExternalSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Desktop-only shell bridge between the native window layer (desktop entry point) and the shared
 * Compose UI. All state here is inert on non-desktop platforms.
 *
 * - [windowTitle]: lets the shared UI drive the native window title (e.g. the open document). The
 *   window reads it as state and is its only writer.
 * - [droppedFiles]: files dropped on the window (a file and the name to give it), for the shared UI
 *   to import and report on like any other import.
 */
object DesktopBridge {
    val windowTitle = MutableStateFlow<String?>(null)

    val droppedFiles = Channel<List<Pair<ExternalSource, String>>>(Channel.UNLIMITED)
}
