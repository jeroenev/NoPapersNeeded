package dev.npn.app.di

import dev.npn.app.platform.OcrModels
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SessionDeps
import dev.npn.core.app.openSession
import dev.npn.core.security.AppLock
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.util.MonotonicClock
import dev.npn.core.util.NpnLog
import dev.npn.core.util.Sensitive.Kind.FOLDER
import dev.npn.core.util.SystemMonotonicClock
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import dev.npn.core.util.sensitive
import dev.npn.core.util.warn
import dev.npn.core.watch.WatchedFolder
import dev.npn.core.watch.WatchedFolderImporter
import dev.npn.core.watch.liesInLibrary
import kotlinx.coroutines.CoroutineDispatcher
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
 * Shared [AppEnvironment] implementation. Platform factory functions ([createAppEnvironment])
 * construct it with a platform [SettingsRepository] and the platform's wiring; all session
 * open/close/swap logic lives here so both platforms behave identically.
 *
 * @param watchedFolder opens a [WatchedFolder] for a persisted location string (desktop: absolute
 *  path; Android: a path or a SAF tree uri), or null when the location cannot be resolved; one that
 *  throws is logged and treated as null.
 * @param factory builds the per-library pieces (fs, databases, engines) for a location, throwing
 *  [LibraryUnavailableException] when it can tell why the library cannot open. The environment
 *  drives them into [openSession] and closes their databases on a swap.
 * @param cloudSetup starts a setup-only engine for [newCloudSetup], when the platform has one.
 * @param readOcrModels answers [ocrModels], where the platform can say.
 */
class DefaultAppEnvironment(
    override val settings: SettingsRepository,
    private val watchedFolder: (location: String) -> WatchedFolder?,
    override val log: NpnLog = NpnLog.None,
    /** Where [appScope]'s coroutines run. */
    appDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cloudSetup: (() -> CloudSetupEngine)? = null,
    private val readOcrModels: (suspend () -> OcrModels)? = null,
    /** The monotonic clock the app lock measures background and idle time with. */
    clock: MonotonicClock = SystemMonotonicClock,
    private val factory: suspend (location: String, scope: CoroutineScope) -> SessionDeps,
) : AppEnvironment {
    override val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + appDispatcher)

    /**
     * The app lock, process-wide: an activity recreated for a configuration change keeps the state
     * it had, and every screen is behind the same lock. It starts locked when the saved setting is
     * on, which is what a cold start shows.
     */
    override val lock: AppLock = AppLock(settings.settings.value.appLock, clock)

    private val _session = MutableStateFlow<LibrarySession?>(null)

    override val session: StateFlow<LibrarySession?> = _session.asStateFlow()

    // Serializes open/close so two overlapping opens (a user's switch racing openPersistedLibrary)
    // cannot interleave closeCurrentInternal with _session assignment and leak a db handle or close
    // a live session.
    private val sessionMutex = Mutex()

    private val _opening = MutableStateFlow(false)
    override val opening: StateFlow<Boolean> = _opening.asStateFlow()

    // The pieces backing the current session, kept so their databases are freed on a swap.
    private var currentDeps: SessionDeps? = null

    // Where the current session is open, so a switch that fails can go back there.
    private var currentLocation: String? = null

    // Every watched-folder sweep, continuous or on demand, runs under this one lock, so the same
    // file is never imported twice at once.
    private val watchedFolderSweeps = Mutex()

    /** The continuous importer and what it sweeps for, so an on-demand sweep reuses its memory. */
    private class RunningImporter(
        val session: LibrarySession,
        val location: String,
        val importer: WatchedFolderImporter,
    )

    private val runningImporter = MutableStateFlow<RunningImporter?>(null)

    private fun openWatchedFolder(location: String): WatchedFolder? =
        log.runLogged("Opening the watched folder") { watchedFolder(location) }

    init {
        // The app lock follows the saved setting: enabling it, changing its secret or its timeout
        // applies without reopening the app, and disabling it unlocks and forgets the hash.
        appScope.launch {
            settings.settings
                .map { it.appLock }
                .distinctUntilChanged()
                .collect { lock.updateSettings(it) }
        }
        // Watched-folder auto-import: (re)start an importer whenever the open session or the
        // configured folder changes; collectLatest cancels the previous importer's loop.
        appScope.launch {
            val configuredFolder = settings.settings.map { it.watchedFolder }.distinctUntilChanged()
            combine(_session, configuredFolder) { s, loc -> s to loc }
                .collectLatest { (session, location) ->
                    if (session == null || location == null) return@collectLatest
                    val folder = openWatchedFolder(location) ?: return@collectLatest
                    val importer = buildImporter(session, folder, location)
                    runningImporter.value = RunningImporter(session, location, importer)
                    try {
                        // An importer that fails is logged and stays stopped until the session or the
                        // folder changes, rather than end this loop and every importer after it.
                        log.runLogged("Watched folder import") { coroutineScope { importer.start(this) } }
                    } finally {
                        runningImporter.value = null
                    }
                }
        }
    }

    /**
     * An importer of the folder at [location] into [session]. The library it sweeps into is the saved
     * one: a session is open only once its location is saved.
     */
    private fun buildImporter(
        session: LibrarySession,
        folder: WatchedFolder,
        location: String,
    ) = WatchedFolderImporter(
        ingest = session.ingest,
        folder = folder,
        deleteAfterImport = { settings.settings.first().watchedFolderDeleteAfterImport },
        log = log,
        sweepLock = watchedFolderSweeps,
        inLibrary = {
            settings.settings
                .first()
                .libraryLocation
                ?.let { liesInLibrary(location, it) } == true
        },
    )

    override fun newCloudSetup(): CloudSetupEngine? = cloudSetup?.invoke()

    override suspend fun ocrModels(): OcrModels? = readOcrModels?.invoke()

    override suspend fun scanWatchedFolder(): Int {
        val location = settings.settings.first().watchedFolder ?: return 0
        val session = _session.value ?: return 0
        val running = runningImporter.value?.takeIf { it.session === session && it.location == location }
        val importer = running?.importer ?: buildImporter(session, openWatchedFolder(location) ?: return 0, location)
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
        } finally {
            _opening.value = false
        }
    }

    /**
     * Open and start a session at [location], save [location] as the library to open next time, and
     * only then make the session the open one. Whatever was built before a failure is closed again:
     * left open, each Retry would leak another Room instance on the same file, and a library whose
     * location could not be saved would be in use now and forgotten at the next start. The location
     * is written only when it changes, so a configuration that cannot be written still opens the
     * library it names.
     */
    private suspend fun startSession(location: String) {
        val deps = factory(location, appScope)
        var session: LibrarySession? = null
        var kept = false
        try {
            session = openSession(deps)
            session.start()
            if (settings.settings.first().libraryLocation != location) {
                settings.edit { it.copy(libraryLocation = location) }
            }
            kept = true
        } finally {
            if (!kept) {
                withContext(NonCancellable) { session?.close() }
                deps.closeDb()
            }
        }
        currentDeps = deps
        currentLocation = location
        _session.value = session
    }

    /** Go back to [location] after a failed switch; a failure here leaves the user on onboarding. */
    private suspend fun reopen(location: String) {
        runCatchingCancellable { startSession(location) }
            .onFailure { log.warn("Could not reopen ${sensitive(location, FOLDER)} after a failed library switch", it) }
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

    /**
     * Close the open session and free its databases. It stops being the open one first, so nothing
     * picks up a session that is closing (the watched-folder importer stops at once, too). The whole
     * transition happens even for a caller cancelled meanwhile (an Android viewmodel cleared
     * mid-switch): stopped halfway, it would leave the session or its databases open.
     */
    private suspend fun closeCurrentInternal() {
        withContext(NonCancellable) {
            val closing = _session.value
            val deps = currentDeps
            _session.value = null
            currentDeps = null
            currentLocation = null
            try {
                closing?.close()
            } finally {
                deps?.closeDb()
            }
        }
    }
}
