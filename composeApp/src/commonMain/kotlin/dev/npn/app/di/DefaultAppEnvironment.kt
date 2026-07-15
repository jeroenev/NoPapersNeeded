package dev.npn.app.di

import dev.npn.core.app.LibrarySessionImpl
import dev.npn.core.app.SessionDeps
import dev.npn.core.app.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Platform-built pieces for one library, returned by [PlatformLibraryFactory.build]. The
 * environment drives the [SessionDeps] into [LibrarySessionImpl.openSession] and calls [close] on
 * swap. [close] closes the Room database (the platform factory owns that, since the Room runtime is
 * on the platform source set's classpath, not composeApp commonMain's).
 */
class OpenedLibrary(
    val deps: SessionDeps,
    val close: () -> Unit,
)

/** Platform hook: build the per-library pieces (fs, db, engines, dirs) for [location]. */
fun interface PlatformLibraryFactory {
    suspend fun build(location: String, scope: CoroutineScope): OpenedLibrary
}

/**
 * Shared [AppEnvironment] implementation. Platform factory functions ([createAppEnvironment])
 * construct it with a platform [SettingsRepository] and a [PlatformLibraryFactory]; all session
 * open/close/swap logic lives here so both platforms behave identically.
 */
class DefaultAppEnvironment(
    override val settings: SettingsRepository,
    private val factory: PlatformLibraryFactory,
) : AppEnvironment {

    override val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _session = MutableStateFlow<LibrarySessionImpl?>(null)
    override val session: StateFlow<dev.npn.core.app.LibrarySession?>
        get() = @Suppress("UNCHECKED_CAST") (_session.asStateFlow() as StateFlow<dev.npn.core.app.LibrarySession?>)

    private val _opening = MutableStateFlow(false)
    override val opening: StateFlow<Boolean> = _opening.asStateFlow()

    // Closes the resources (db) backing the current session, kept so we can free them on swap.
    private var currentClose: (() -> Unit)? = null

    override suspend fun openLibrary(location: String) {
        _opening.value = true
        try {
            // Close any existing session/db first.
            closeCurrentInternal()

            val opened = factory.build(location, appScope)
            val session = LibrarySessionImpl.openSession(opened.deps)
            session.start(appScope)
            currentClose = opened.close
            _session.value = session
            settings.setLibraryLocation(location)
        } finally {
            _opening.value = false
        }
    }

    override suspend fun closeLibrary() {
        closeCurrentInternal()
    }

    override suspend fun openPersistedLibrary() {
        val location = settings.libraryLocation.first() ?: return
        // Keep the persisted location even on failure (task note): surface failure by leaving the
        // session null and rethrowing so the UI can handle it.
        openLibrary(location)
    }

    private fun closeCurrentInternal() {
        _session.value?.close()
        _session.value = null
        currentClose?.invoke()
        currentClose = null
    }
}
