package dev.npn.app.di

import dev.npn.core.app.LibrarySessionImpl
import dev.npn.core.app.SessionDeps
import dev.npn.core.app.SettingsRepository
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn
import dev.npn.core.watch.WatchedFolder
import dev.npn.core.watch.WatchedFolderImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext

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
    private val log: NpnLog = NpnLog.None,
    private val factory: PlatformLibraryFactory,
) : AppEnvironment {
    override val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _session = MutableStateFlow<LibrarySessionImpl?>(null)

    override val session: StateFlow<dev.npn.core.app.LibrarySession?> = _session.asStateFlow()

    // Serializes open/close so two overlapping opens (e.g. requestOpenLibrary on appScope racing
    // openPersistedLibrary) cannot interleave closeCurrentInternal with _session assignment and
    // leak a db handle or close a live session.
    private val sessionMutex = Mutex()

    private val _opening = MutableStateFlow(false)
    override val opening: StateFlow<Boolean> = _opening.asStateFlow()

    // Closes the resources (db) backing the current session, kept so we can free them on swap.
    private var currentClose: (() -> Unit)? = null

    // Where the current session is open, so a switch that fails can go back there.
    private var currentLocation: String? = null

    // Every watched-folder sweep, continuous or on demand, runs under this one lock, so the same
    // file is never imported twice at once.
    private val watchedFolderSweeps = Mutex()

    /** The continuous importer and what it sweeps for, so an on-demand sweep reuses its memory. */
    private class RunningImporter(
        val session: dev.npn.core.app.LibrarySession,
        val location: String,
        val importer: WatchedFolderImporter,
    )

    private val runningImporter = MutableStateFlow<RunningImporter?>(null)

    init {
        // Watched-folder auto-import: (re)start an importer whenever the open session or the
        // configured folder changes; collectLatest cancels the previous importer's loop.
        appScope.launch {
            val watchedFolder = settings.settings.map { it.watchedFolder }.distinctUntilChanged()
            combine(_session, watchedFolder) { s, loc -> s to loc }
                .collectLatest { (session, location) ->
                    if (session == null || location == null) return@collectLatest
                    val folder = watchedFolderFactory.open(location) ?: return@collectLatest
                    val importer = buildImporter(session, folder)
                    runningImporter.value = RunningImporter(session, location, importer)
                    try {
                        coroutineScope { importer.start(this) }
                    } finally {
                        runningImporter.value = null
                    }
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
        log = log,
        sweepLock = watchedFolderSweeps,
    )

    override suspend fun scanWatchedFolder(): Int {
        val location = settings.settings.first().watchedFolder ?: return 0
        val session = _session.value ?: return 0
        val running = runningImporter.value?.takeIf { it.session === session && it.location == location }
        val importer = running?.importer ?: buildImporter(session, watchedFolderFactory.open(location) ?: return 0)
        return importer.scanOnce().imported
    }

    override suspend fun openLibrary(location: String) = sessionMutex.withLock { openLibraryLocked(location) }

    /**
     * Swap the open session for one at [location]. If that fails, the user goes back to the library
     * they had rather than to onboarding: the previous location is reopened, best effort, and the
     * failure is rethrown for the caller to report.
     */
    private suspend fun openLibraryLocked(location: String) {
        _opening.value = true
        try {
            val previous = currentLocation
            closeCurrentInternal()
            runCatchingCancellable { startSession(location) }.onFailure { failure ->
                if (previous != null && previous != location) reopen(previous)
                throw failure
            }
            settings.edit { it.copy(libraryLocation = location) }
        } finally {
            _opening.value = false
        }
    }

    /**
     * Open and start a session at [location]. Whatever was built before a failure is closed again:
     * left open, each Retry would leak another Room instance on the same file.
     */
    private suspend fun startSession(location: String) {
        val opened = factory.build(location, appScope)
        var session: LibrarySessionImpl? = null
        var started = false
        try {
            session = LibrarySessionImpl.openSession(opened.deps)
            session.start()
            started = true
        } finally {
            if (!started) {
                withContext(NonCancellable) { session?.close() }
                opened.close()
            }
        }
        currentClose = opened.close
        currentLocation = location
        _session.value = session
    }

    /** Go back to [location] after a failed switch; a failure here leaves the user on onboarding. */
    private suspend fun reopen(location: String) {
        runCatchingCancellable { startSession(location) }
            .onFailure { log.warn("Could not reopen $location after a failed library switch", it) }
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
            // Keep the persisted location on failure: the session stays null and the failure is
            // rethrown, so the user can retry it or pick the folder again.
            openLibraryLocked(location)
        }

    private suspend fun closeCurrentInternal() {
        _session.value?.close()
        _session.value = null
        dev.npn.app.ui.common
            .clearThumbCache()
        currentClose?.invoke()
        currentClose = null
        currentLocation = null
    }
}
