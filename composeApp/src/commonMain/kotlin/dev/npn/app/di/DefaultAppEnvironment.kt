package dev.npn.app.di

import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.LibrarySessionImpl
import dev.npn.core.app.SessionDeps
import dev.npn.core.app.SettingsRepository
import dev.npn.core.watch.WatchedFolder
import dev.npn.core.watch.WatchedFolderImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    suspend fun build(
        location: String,
        scope: CoroutineScope,
    ): OpenedLibrary
}

/**
 * Platform hook: open a [WatchedFolder] for a persisted location string (desktop: absolute path;
 * android: SAF tree uri). Returns null when the location cannot be resolved.
 */
fun interface WatchedFolderFactory {
    fun open(location: String): WatchedFolder?
}

/**
 * Shared [AppEnvironment] implementation. Platform factory functions ([createAppEnvironment])
 * construct it with a platform [SettingsRepository] and a [PlatformLibraryFactory]; all session
 * open/close/swap logic lives here so both platforms behave identically.
 */
class DefaultAppEnvironment(
    override val settings: SettingsRepository,
    private val watchedFolderFactory: WatchedFolderFactory = WatchedFolderFactory { null },
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

    private val _openError = MutableStateFlow<UiText?>(null)
    override val openError: StateFlow<UiText?> = _openError.asStateFlow()

    // Closes the resources (db) backing the current session, kept so we can free them on swap.
    private var currentClose: (() -> Unit)? = null

    init {
        // Watched-folder auto-import: (re)start an importer whenever the open session or the
        // configured folder changes; collectLatest cancels the previous importer's loops.
        appScope.launch {
            val watchedFolder = settings.settings.map { it.watchedFolder }.distinctUntilChanged()
            combine(_session, watchedFolder) { s, loc -> s to loc }
                .collectLatest { (session, location) ->
                    if (session == null || location == null) return@collectLatest
                    val folder = watchedFolderFactory.open(location) ?: return@collectLatest
                    coroutineScope { buildImporter(session, folder).start(this) }
                }
        }
    }

    private fun buildImporter(
        session: dev.npn.core.app.LibrarySession,
        folder: WatchedFolder,
    ) = WatchedFolderImporter(
        ingest = session.ingest,
        folder = folder,
        deleteAfterImport = { settings.settings.first().watchedFolderDeleteAfterImport },
    )

    override suspend fun scanWatchedFolder(): Int {
        val location = settings.settings.first().watchedFolder ?: return 0
        val session = _session.value ?: return 0
        val folder = watchedFolderFactory.open(location) ?: return 0
        return buildImporter(session, folder).scanOnce().imported
    }

    override fun requestOpenLibrary(location: String) {
        // Run on the process-lived appScope, not the caller's (composable) scope, which can be
        // cancelled by recomposition while a picker dismisses.
        appScope.launch {
            runCatching { openLibrary(location) }
                .onFailure {
                    _openError.value = it.message?.let(UiText::Dynamic)
                        ?: uiText(UiTextKey.COULD_NOT_OPEN_LIBRARY)
                }
        }
    }

    override suspend fun openLibrary(location: String) = sessionMutex.withLock { openLibraryLocked(location) }

    private suspend fun openLibraryLocked(location: String) {
        _opening.value = true
        _openError.value = null
        try {
            closeCurrentInternal()

            val opened = factory.build(location, appScope)
            val session = LibrarySessionImpl.openSession(opened.deps)
            session.start(appScope)
            currentClose = opened.close
            _session.value = session
            settings.edit { it.copy(libraryLocation = location) }
        } finally {
            _opening.value = false
        }
    }

    override suspend fun closeLibrary() =
        sessionMutex.withLock {
            closeCurrentInternal()
        }

    override suspend fun openPersistedLibrary() =
        sessionMutex.withLock {
            // The app shell and an Android share intent may both request restoration during cold
            // start. The first opens it; the waiter must reuse that session rather than close and
            // rebuild it underneath the import.
            if (_session.value != null) return@withLock
            val location = settings.settings.first().libraryLocation ?: return@withLock
            // Keep the persisted location on failure; surface the error by leaving the
            // session null and rethrowing so the UI can handle it.
            openLibraryLocked(location)
        }

    private suspend fun closeCurrentInternal() {
        _session.value?.close()
        _session.value = null
        dev.npn.app.ui.common
            .clearThumbCache()
        currentClose?.invoke()
        currentClose = null
    }
}
