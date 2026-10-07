package dev.npn.app.ui.update

import androidx.lifecycle.viewModelScope
import dev.npn.app.BuildInfo
import dev.npn.app.platform.checksForUpdates
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.WhileSubscribedTimeout
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
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_save_setting_failed

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
) : MessagesViewModel() {
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

    /** Stop offering [release]; the banner stays, and says why, when that cannot be saved. */
    fun dismiss(release: AppRelease) =
        launchReporting(Res.string.common_save_setting_failed) {
            settings.edit { it.copy(dismissedUpdateVersion = release.version) }
        }
}
