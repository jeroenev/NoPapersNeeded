package dev.npn.app.di

import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * App-level dependency container. One per process, created by the platform entry point
 * (MainActivity / desktop main) and handed to the root App composable.
 */
interface AppEnvironment {
    val settings: SettingsRepository
    val appScope: CoroutineScope

    /** Currently opened library, null until onboarding completes (or while switching). */
    val session: StateFlow<LibrarySession?>

    /** True while a session is being constructed/opened. */
    val opening: StateFlow<Boolean>

    /** Last open failure message, or null. Cleared when a new open starts or succeeds. */
    val openError: StateFlow<String?>

    /**
     * Open (or switch to) the library at [location] and persist it as the configured library.
     * Desktop: absolute folder path. Android: SAF tree uri string (persistable permission
     * must already be taken by the caller).
     */
    suspend fun openLibrary(location: String)

    /**
     * Fire-and-forget variant of [openLibrary] that runs on the long-lived [appScope] instead of
     * the caller's scope. UI event handlers (pickers, buttons) must use this: the composable
     * [CoroutineScope] can be cancelled by recomposition while the picker dismisses, which would
     * abort a directly-launched open. Progress and failures surface via [opening] and [openError].
     */
    fun requestOpenLibrary(location: String)

    suspend fun closeLibrary()

    /** Restore the persisted library on app start, if any. */
    suspend fun openPersistedLibrary()
}
