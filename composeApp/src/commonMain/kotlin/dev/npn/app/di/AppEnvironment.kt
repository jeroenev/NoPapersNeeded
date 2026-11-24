package dev.npn.app.di

import dev.npn.app.platform.OcrModels
import dev.npn.core.app.LibrarySession
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.sync.CloudSetup
import dev.npn.core.util.NpnLog
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

    /** Where app-level work that nobody awaits reports a failure. */
    val log: NpnLog

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

    /**
     * A cloud setup engine of its own, for choosing a cloud library before any library is open
     * (onboarding), or null where the platform wired none. It shares the device's rclone
     * configuration with every session's engine, so the remote it creates is there for the library
     * that opens next; the caller closes it before that library's engine starts.
     */
    fun newCloudSetup(): CloudSetupEngine? = null

    /**
     * Which text recognition models this device has, asked afresh, for first run's language step;
     * null where the platform takes no language setting (Android) or cannot say.
     */
    suspend fun ocrModels(): OcrModels? = null
}

/** A [setup] engine that runs until [close]; see [AppEnvironment.newCloudSetup]. */
class CloudSetupEngine(
    val setup: CloudSetup,
    private val onClose: () -> Unit,
) : AutoCloseable {
    override fun close() = onClose()
}
