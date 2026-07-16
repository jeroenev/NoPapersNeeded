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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    @Suppress("UNCHECKED_CAST")
    override val session: StateFlow<dev.npn.core.app.LibrarySession?> =
        _session.asStateFlow() as StateFlow<dev.npn.core.app.LibrarySession?>

    // Serializes open/close so two overlapping opens (e.g. requestOpenLibrary on appScope racing
    // openPersistedLibrary) cannot interleave closeCurrentInternal with _session assignment and
    // leak a db handle or close a live session.
    private val sessionMutex = Mutex()

    private val _opening = MutableStateFlow(false)
    override val opening: StateFlow<Boolean> = _opening.asStateFlow()

    private val _openError = MutableStateFlow<String?>(null)
    override val openError: StateFlow<String?> = _openError.asStateFlow()

    // Closes the resources (db) backing the current session, kept so we can free them on swap.
    private var currentClose: (() -> Unit)? = null

    override fun requestOpenLibrary(location: String) {
        // Run on the process-lived appScope, not the caller's (composable) scope, which can be
        // cancelled by recomposition while a picker dismisses.
        appScope.launch {
            runCatching { openLibrary(location) }
                .onFailure { _openError.value = it.message ?: "Could not open the library." }
        }
    }

    override suspend fun openLibrary(location: String) = sessionMutex.withLock {
        _opening.value = true
        _openError.value = null
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

    override suspend fun closeLibrary() = sessionMutex.withLock {
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
