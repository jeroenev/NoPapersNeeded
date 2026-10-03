package dev.npn.app.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.app.BuildInfo
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.checksForUpdates
import dev.npn.core.app.SettingsRepository
import dev.npn.core.update.AppRelease
import dev.npn.core.update.AppVersions
import dev.npn.core.update.LatestReleaseSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Looks up the latest release once per app start and offers it while it is newer than this build,
 * the check is on, and the user has not dismissed that version. The lookup waits for the setting,
 * so turning the check on later in the session still runs it once.
 */
class UpdateNoticeViewModel(
    private val settings: SettingsRepository,
    private val source: LatestReleaseSource,
    private val currentVersion: String = BuildInfo.VERSION,
    platformChecks: Boolean = checksForUpdates(),
) : ViewModel() {
    private val newer = MutableStateFlow<AppRelease?>(null)

    val notice: StateFlow<AppRelease?> =
        combine(newer, settings.settings) { release, s ->
            release?.takeIf { s.updateCheckEnabled && it.version != s.dismissedUpdateVersion }
        }.stateIn(viewModelScope, WhileSubscribedTimeout, null)

    init {
        if (platformChecks) {
            viewModelScope.launch {
                settings.settings.first { it.updateCheckEnabled }
                newer.value = source.latest()?.takeIf { AppVersions.isNewer(it.version, currentVersion) }
            }
        }
    }

    fun dismiss(release: AppRelease) {
        viewModelScope.launch { settings.edit { it.copy(dismissedUpdateVersion = release.version) } }
    }
}
