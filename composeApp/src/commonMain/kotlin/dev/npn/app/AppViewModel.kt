package dev.npn.app

import androidx.lifecycle.viewModelScope
import dev.npn.app.di.AppEnvironment
import dev.npn.app.di.LibraryUnavailableException
import dev.npn.app.di.OpenFailureReason
import dev.npn.app.platform.createLibraryFolder
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.asUiText
import dev.npn.app.ui.common.clearThumbCache
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.importAll
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.VerifiedPublicationUnavailableException
import dev.npn.core.settings.aiConfigured
import dev.npn.core.util.reason
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_save_setting_failed
import no_papers_needed.composeapp.generated.resources.library_drop_needs_library
import no_papers_needed.composeapp.generated.resources.library_import_failed

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
 */
class AppViewModel(
    private val env: AppEnvironment,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val createFolder: suspend (parent: String, name: String) -> String? = ::createLibraryFolder,
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
            .stateIn(viewModelScope, WhileSubscribedTimeout, false)

    /**
     * Whether AI is configured, for the shell's AI entries. Kept from the start rather than while
     * subscribed, so the answer is in before the library opens and the bar never shifts under it.
     */
    val aiConfigured: StateFlow<Boolean> =
        env.settings.aiConfigured().stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        restore()
        // Decoded thumbnails belong to the library they were drawn for: whenever it closes or
        // changes, the next one starts with an empty cache.
        viewModelScope.launch { env.session.collect { clearThumbCache() } }
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
    fun useRecommended(location: String) =
        createLibrary(location.substringBeforeLast('/'), location.substringAfterLast('/'))

    fun setUseSafStorage(enabled: Boolean) =
        launchReporting(Res.string.common_save_setting_failed) {
            env.settings.edit { it.copy(useSafStorage = enabled) }
        }

    /** The user has read why a switch to another library failed. */
    fun dismissFailure() = phase.update { if (it is Phase.Ready) Phase.Ready() else it }

    /**
     * Import [files] (a file and the name to give it) dropped on the window, whatever screen is
     * showing, and say what became of them.
     */
    fun importFiles(files: List<Pair<ExternalSource, String>>) {
        val session = env.session.value
        if (session == null) {
            message(uiText(Res.string.library_drop_needs_library))
            return
        }
        launchReporting(Res.string.library_import_failed) {
            message(session.ingest.importAll(files).asUiText())
        }
    }
}

private fun Throwable.toOpenFailure(): OpenFailure =
    OpenFailure(
        reason =
            when (this) {
                is LibraryUnavailableException -> reason
                is VerifiedPublicationUnavailableException -> OpenFailureReason.UNSUPPORTED_STORAGE
                else -> OpenFailureReason.UNREADABLE
            },
        details = reason(),
    )
