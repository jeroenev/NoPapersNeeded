package dev.npn.app.ui.whatsnew

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.app.BuildInfo
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res

/**
 * Decides whether this device should see "What's new" once for the version now running.
 *
 * A fresh install records the current version without showing anything: the onboarding is already
 * enough to read. A later version change shows the notes once and records them when dismissed, but
 * only when the bundled notes are this version's ([notesVersion]): a release that brings none keeps
 * the last one's notes in About, and does not pop them up as news.
 */
internal class WhatsNewGateViewModel(
    private val settings: SettingsRepository,
    private val currentVersion: String = BuildInfo.VERSION,
    private val log: NpnLog = NpnLog.None,
    /** The version the bundled notes are for, null when they cannot be read. */
    private val notesVersion: suspend () -> String? = ::bundledNotesVersion,
) : ViewModel() {
    private val _show = MutableStateFlow(false)
    val show: StateFlow<Boolean> = _show.asStateFlow()

    init {
        viewModelScope.launch {
            val snapshot = settings.settings.first()
            when {
                // A fresh install records the version without a popup. An existing library means this
                // build is the first with notes, so its user belongs to the upgrade they describe.
                snapshot.lastSeenVersion == null && snapshot.libraryLocation == null -> {
                    runCatchingCancellable { settings.edit { it.copy(lastSeenVersion = currentVersion) } }
                        .onFailure { log.warn("Recording the last seen app version", it) }
                }

                snapshot.lastSeenVersion != currentVersion -> {
                    _show.value = notesVersion() == currentVersion
                }
            }
        }
    }

    fun dismiss() {
        _show.value = false
        viewModelScope.launch {
            runCatchingCancellable {
                settings.edit {
                    if (it.lastSeenVersion ==
                        currentVersion
                    ) {
                        it
                    } else {
                        it.copy(lastSeenVersion = currentVersion)
                    }
                }
            }.onFailure { log.warn("Recording the seen release notes", it) }
        }
    }
}

private suspend fun bundledNotesVersion(): String? =
    runCatchingCancellable { WhatsNewMarkdown.version(Res.readBytes(WhatsNewMarkdown.NOTES_PATH).decodeToString()) }
        .getOrNull()
