package dev.npn.app.di

import dev.npn.core.app.LibrarySession
import dev.npn.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * App-level dependency container. One per process, created by the platform entry point
 * (MainActivity / desktop main) and handed to the root App composable, whose `AppViewModel` drives
 * opening and restoring the library through it.
 */
interface AppEnvironment {
    val settings: SettingsRepository
    val appScope: CoroutineScope

    /** Currently opened library, null until onboarding completes (or while switching). */
    val session: StateFlow<LibrarySession?>

    /** True while a session is being constructed/opened. */
    val opening: StateFlow<Boolean>

    /**
     * Open (or switch to) the library at [location] and persist it as the configured library.
     * Desktop: absolute folder path. Android: a device-storage path, or a SAF tree uri whose
     * persistable permission the caller has already taken.
     *
     * Throws when the library cannot be opened or [location] cannot be persisted, a
     * [LibraryUnavailableException] when the platform knows why. A failed switch goes back to the
     * library that was open before, so [session] is then that library again rather than null.
     */
    suspend fun openLibrary(location: String)

    suspend fun closeLibrary()

    /**
     * Restore the persisted library on app start, if any; a no-op when one is already open or none
     * is configured. Throws like [openLibrary] when the saved library cannot be opened.
     */
    suspend fun openPersistedLibrary()

    /**
     * One-shot sweep of the configured watched folder into the open library (no-op when either
     * is missing). The environment also runs a continuous watcher while a session is open; this
     * explicit hook exists for background workers that must complete a sweep before returning.
     * Returns the number of newly imported documents.
     */
    suspend fun scanWatchedFolder(): Int
}
