package dev.npn.app

import androidx.lifecycle.viewModelScope
import dev.npn.app.di.AppEnvironment
import dev.npn.app.di.CloudSetupEngine
import dev.npn.app.di.LibraryUnavailableException
import dev.npn.app.di.OpenFailureReason
import dev.npn.app.platform.cloudLibrariesDir
import dev.npn.app.platform.createLibraryFolder
import dev.npn.app.ui.common.ImportRequest
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiMessage
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.asUiText
import dev.npn.app.ui.common.clearThumbCache
import dev.npn.app.ui.common.importBatch
import dev.npn.app.ui.common.offerSearchTip
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.nav.QueueStatus
import dev.npn.app.ui.nav.queueStatus
import dev.npn.app.ui.settings.CloudSetupActions
import dev.npn.app.ui.settings.CloudSetupController
import dev.npn.app.ui.settings.CloudSetupState
import dev.npn.app.ui.settings.newCloudRemoteName
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.RebuildOutcome
import dev.npn.core.fs.VerifiedPublicationUnavailableException
import dev.npn.core.index.MetadataWriteBlocked
import dev.npn.core.index.WriteBlock
import dev.npn.core.settings.aiConfigured
import dev.npn.core.sync.CloudSetup
import dev.npn.core.util.reason
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_onboarding_cloud_connect_failed
import no_papers_needed.composeapp.generated.resources.common_save_setting_failed
import no_papers_needed.composeapp.generated.resources.library_drop_needs_library
import no_papers_needed.composeapp.generated.resources.library_import_failed
import no_papers_needed.composeapp.generated.resources.library_rebuild_failed
import no_papers_needed.composeapp.generated.resources.library_rebuild_not_completed
import no_papers_needed.composeapp.generated.resources.library_refresh_failed
import no_papers_needed.composeapp.generated.resources.library_show
import no_papers_needed.composeapp.generated.resources.onboarding_landed_reading
import no_papers_needed.composeapp.generated.resources.onboarding_landed_search

/** A library that did not open, as the user is told: a localized [reason], with [details] for support. */
data class OpenFailure(
    val reason: OpenFailureReason,
    val details: String? = null,
)

/** What the app shell shows. Every failure to open a library is carried here, and nowhere else. */
sealed interface AppState {
    /** Opening the library this device used last, at start. */
    data object Restoring : AppState

    /** A library is being opened: the first one, another one, or the saved one again. */
    data object Opening : AppState

    /** No library yet: onboarding, saying why the last attempt from there failed, if it did. */
    data class NoLibrary(
        val failure: OpenFailure? = null,
    ) : AppState

    /** The saved library could not be opened; the user can retry it or choose a folder again. */
    data class RestoreFailed(
        val failure: OpenFailure,
    ) : AppState

    /** [session] is open. A [failure] means a switch to another library failed and this one is back. */
    data class Open(
        val session: LibrarySession,
        val failure: OpenFailure? = null,
    ) : AppState
}

/**
 * Opens, restores and switches the library for the app shell, and owns what it shows meanwhile
 * ([state]). The session itself belongs to [env], which a share intent or a background worker may
 * also open, so [state] follows [AppEnvironment.session] rather than keeping its own copy.
 *
 * Folder work for a new library runs on [io]; [createFolder] is the platform's
 * [createLibraryFolder].
 *
 * A library kept in a cloud drive is chosen through the same setup wizard as cloud sync in settings
 * ([cloudSetup]), run on a setup engine of its own since no library is open yet. Its local working
 * copy is a folder the app makes under [cloudLibraries], named after the remote, so the user never
 * picks one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(
    private val env: AppEnvironment,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val createFolder: suspend (parent: String, name: String) -> String? = ::createLibraryFolder,
    private val cloudLibraries: () -> String = ::cloudLibrariesDir,
    newRemoteName: (type: String) -> String = ::newCloudRemoteName,
) : MessagesViewModel() {
    /** Where the shell stands, apart from what [env] says about the session. */
    private sealed interface Phase {
        data object Restoring : Phase

        data class Ready(
            val failure: OpenFailure? = null,
        ) : Phase

        data class RestoreFailed(
            val failure: OpenFailure,
        ) : Phase
    }

    private val phase = MutableStateFlow<Phase>(Phase.Restoring)

    val state: StateFlow<AppState> =
        combine(phase, env.session, env.opening) { phase, session, opening ->
            when {
                phase == Phase.Restoring -> AppState.Restoring
                opening -> AppState.Opening
                session != null -> AppState.Open(session, (phase as? Phase.Ready)?.failure)
                phase is Phase.RestoreFailed -> AppState.RestoreFailed(phase.failure)
                else -> AppState.NoLibrary((phase as? Phase.Ready)?.failure)
            }
        }.stateIn(viewModelScope, WhileSubscribedTimeout, AppState.Restoring)

    /** Whether the next folder choice goes through the Storage Access Framework (Android). */
    val useSafStorage: StateFlow<Boolean> =
        env.settings.settings
            .map { it.useSafStorage }
            .distinctUntilChanged()
            .stateIn(viewModelScope, WhileSubscribedTimeout, env.settings.settings.value.useSafStorage)

    /**
     * The app's language override (null follows the platform's), which the whole UI is composed in.
     * Its current value from the start, so a recreated window's first frame is already in it.
     */
    val appLanguage: StateFlow<String?> =
        env.settings.settings
            .map { it.appLanguage }
            .distinctUntilChanged()
            .stateIn(viewModelScope, WhileSubscribedTimeout, env.settings.settings.value.appLanguage)

    /** Whether AI is configured, for the shell's AI entries. */
    val aiConfigured: StateFlow<Boolean> =
        env.settings
            .aiConfigured()
            .stateIn(viewModelScope, SharingStarted.Eagerly, env.settings.settings.value.aiConfigured)

    /**
     * Outstanding background work in the open library, for the shell's Activity indicator: the only
     * standing sign that the app is still busy, or, once a background bulk edit finishes or work
     * stops, that it is waiting on the user.
     */
    val queueStatus: StateFlow<QueueStatus> =
        env.session
            .flatMapLatest { session -> session?.work?.queueStatus() ?: flowOf(QueueStatus.None) }
            .stateIn(viewModelScope, WhileSubscribedTimeout, QueueStatus.None)

    /**
     * Why the open library refuses writes now, for the shell's read-only banner: the session's
     * [LibrarySession.writeBlock], null while writes are taken or no library is open. It clears by
     * itself once a pass reads the journal whole.
     */
    val writeBlock: StateFlow<WriteBlock?> =
        env.session
            .flatMapLatest { session -> session?.writeBlock ?: flowOf(null) }
            .stateIn(
                viewModelScope,
                WhileSubscribedTimeout,
                env.session.value
                    ?.writeBlock
                    ?.value,
            )

    /** The onboarding's setup engine, started on first use and closed once a cloud library opens. */
    private var cloudEngine: CloudSetupEngine? = null

    private fun cloudEngine(): CloudSetup? = (cloudEngine ?: env.newCloudSetup()?.also { cloudEngine = it })?.setup

    private fun closeCloudEngine() {
        cloudEngine?.close()
        cloudEngine = null
    }

    /**
     * The onboarding's cloud setup: the provider, its sign-in and the cloud folder. Choosing the
     * folder opens the library ([openCloudLibrary]).
     */
    private val cloud =
        CloudSetupController(
            scope = viewModelScope,
            setup = ::cloudEngine,
            readRemote = { null },
            persistRemote = ::openCloudLibrary,
            // Nothing is connected before the library opens, so there is nothing to disconnect.
            disconnectRemote = {},
            newRemoteName = newRemoteName,
        )

    /** Whether this platform can set up a cloud library at all. */
    val cloudAvailable: Boolean get() = cloud.available

    /** The onboarding's cloud setup step, null while it is closed. */
    val cloudSetupState: StateFlow<CloudSetupState?> get() = cloud.state

    val cloudSetup: CloudSetupActions get() = cloud

    init {
        restore()
        // Decoded thumbnails belong to the library they were drawn for: whenever it closes or
        // changes, the next one starts with an empty cache.
        viewModelScope.launch { env.session.collect { clearThumbCache() } }
    }

    /**
     * Read the open library's journal again (a rescan), which lifts a block a journal still arriving
     * through sync, or a pass that failed, left; a rescan that fails says so.
     */
    fun retryWrites() {
        val session = env.session.value ?: return
        launchReporting(Res.string.library_refresh_failed) { session.ingest.rescan() }
    }

    /**
     * Build this device's index of the open library again from the folder alone, as a fresh device
     * would: the way out of a block that no reading of the journal lifts (a journal object nobody
     * holds any more). A rebuild that does not finish says so.
     */
    fun rebuildIndex() {
        val session = env.session.value ?: return
        launchReporting(Res.string.library_rebuild_failed) {
            // The banner keeps showing the block a failed rebuild leaves; the message says it did not finish.
            when (session.maintenance.rebuildIndex()) {
                RebuildOutcome.Rebuilt -> Unit
                RebuildOutcome.Failed -> message(uiText(Res.string.library_rebuild_not_completed))
            }
        }
    }

    /** Try the saved library again. */
    fun retry() = restore()

    private fun restore() {
        phase.value = Phase.Restoring
        viewModelScope.launch {
            phase.value =
                runCatchingCancellable { env.openPersistedLibrary() }.fold(
                    onSuccess = { Phase.Ready() },
                    onFailure = { Phase.RestoreFailed(it.toOpenFailure()) },
                )
        }
    }

    /** Open the library at [location]: a first one, another one, or the saved one picked again. */
    fun open(location: String) {
        val from = phase.value
        viewModelScope.launch {
            phase.value =
                runCatchingCancellable { env.openLibrary(location) }.fold(
                    onSuccess = { Phase.Ready() },
                    onFailure = { failure ->
                        // Picking another folder for a saved library that failed keeps the way back
                        // to it: the saved location is only replaced once something else opens.
                        if (from is Phase.RestoreFailed) {
                            Phase.RestoreFailed(failure.toOpenFailure())
                        } else {
                            Phase.Ready(failure.toOpenFailure())
                        }
                    },
                )
        }
    }

    /** Create a library folder named [name] inside [parent], then open it. */
    fun createLibrary(
        parent: String,
        name: String,
    ) {
        viewModelScope.launch {
            val created = runCatchingCancellable { withContext(io) { createFolder(parent, name) } }
            val location = created.getOrNull()
            if (location != null) {
                open(location)
            } else {
                phase.value =
                    Phase.Ready(OpenFailure(OpenFailureReason.FOLDER_NOT_CREATED, created.exceptionOrNull()?.reason()))
            }
        }
    }

    /** Create (or reuse) the platform's recommended library folder at [location], then open it. */
    fun useRecommended(location: String) {
        // A Windows path separates its folders with backslashes.
        val folder = location.trimEnd('/', '\\')
        val split = folder.lastIndexOfAny(charArrayOf('/', '\\'))
        createLibrary(folder.substring(0, split.coerceAtLeast(0)), folder.substring(split + 1))
    }

    /**
     * First run left the user on the first document they added: say how to find it again, a word
     * from its page in the library's search, once its text is read ([textRead]).
     */
    fun firstDocumentLanded(textRead: Boolean) =
        message(
            uiText(if (textRead) Res.string.onboarding_landed_search else Res.string.onboarding_landed_reading),
        )

    /**
     * Open a library kept in the cloud folder [remote] (`name:path`, as the wizard chose it): make its
     * working copy on this device, open that, and connect it to [remote] with automatic sync on,
     * starting the first pass at once, which brings in what the cloud folder already holds. A library
     * that does not open forgets the remote's sign-in, as backing out of the wizard does.
     */
    private fun openCloudLibrary(remote: String) {
        val remoteName = remote.substringBefore(':')
        viewModelScope.launch {
            val created = runCatchingCancellable { withContext(io) { createFolder(cloudLibraries(), remoteName) } }
            val location = created.getOrNull()
            if (location == null) {
                forgetCloudRemote(remoteName)
                phase.value =
                    Phase.Ready(OpenFailure(OpenFailureReason.FOLDER_NOT_CREATED, created.exceptionOrNull()?.reason()))
                return@launch
            }
            val opened = runCatchingCancellable { env.openLibrary(location) }
            opened.exceptionOrNull()?.let { failure ->
                forgetCloudRemote(remoteName)
                phase.value = Phase.Ready(failure.toOpenFailure())
                return@launch
            }
            // The library's own engine takes over from here, over the same configuration.
            closeCloudEngine()
            phase.value = Phase.Ready()
            val session = env.session.value ?: return@launch
            val connected =
                reporting(Res.string.common_onboarding_cloud_connect_failed) {
                    session.sync.configure { it.copy(remote = remote, enabled = true) }
                }
            // How the pass went shows where every pass's does: the status and the failure banner.
            if (connected) runCatchingCancellable { session.sync.syncNow() }
        }
    }

    private suspend fun forgetCloudRemote(name: String) {
        runCatchingCancellable { cloudEngine()?.deleteRemote(name) }
    }

    override fun onCleared() {
        closeCloudEngine()
        super.onCleared()
    }

    fun setUseSafStorage(enabled: Boolean) =
        launchReporting(Res.string.common_save_setting_failed) {
            env.settings.edit { it.copy(useSafStorage = enabled) }
        }

    /** The user has read why a switch to another library failed. */
    fun dismissFailure() = phase.update { if (it is Phase.Ready) Phase.Ready() else it }

    /**
     * Import the files dropped on the window ([request]), whatever screen is showing, and say what
     * became of them, with a Show action handing the new documents to [onShow]; once their text is
     * read, offer the search tip.
     */
    fun importFiles(
        request: ImportRequest,
        onShow: (docIds: List<String>) -> Unit,
    ) {
        val session = env.session.value
        if (session == null) {
            message(uiText(Res.string.library_drop_needs_library))
            return
        }
        launchReporting(Res.string.library_import_failed) {
            val batch = session.ingest.importBatch(request)
            message(
                batch.asUiText(),
                batch.imported.takeIf { it.isNotEmpty() }?.let { ids ->
                    UiMessage.Action(uiText(Res.string.library_show)) { onShow(ids) }
                },
            )
            viewModelScope.launch {
                session.log.runLogged("The search tip") {
                    offerSearchTip(env.settings, session.documents, batch.imported, ::message)
                }
            }
        }
    }
}

private fun Throwable.toOpenFailure(): OpenFailure =
    OpenFailure(
        reason =
            when (this) {
                is LibraryUnavailableException -> reason
                is VerifiedPublicationUnavailableException -> OpenFailureReason.UNSUPPORTED_STORAGE
                is MetadataWriteBlocked if block == WriteBlock.NEWER_FORMAT -> OpenFailureReason.NEEDS_NEWER_APP
                else -> OpenFailureReason.UNREADABLE
            },
        details = reason(),
    )
