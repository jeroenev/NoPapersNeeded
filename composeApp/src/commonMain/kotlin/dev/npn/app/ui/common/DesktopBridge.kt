package dev.npn.app.ui.common

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Desktop-only shell bridge between the native window layer (desktop entry point) and the shared
 * Compose UI. All state here is inert on non-desktop platforms.
 *
 * - [windowTitle]: lets the shared UI drive the native window title (e.g. the open document).
 */
object DesktopBridge {
    val windowTitle = MutableStateFlow<String?>(null)
}
