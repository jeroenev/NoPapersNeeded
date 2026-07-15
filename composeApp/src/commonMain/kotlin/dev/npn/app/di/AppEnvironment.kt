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

    /**
     * Open (or switch to) the library at [location] and persist it as the configured library.
     * Desktop: absolute folder path. Android: SAF tree uri string (persistable permission
     * must already be taken by the caller).
     */
    suspend fun openLibrary(location: String)

    suspend fun closeLibrary()

    /** Restore the persisted library on app start, if any. */
    suspend fun openPersistedLibrary()
}
