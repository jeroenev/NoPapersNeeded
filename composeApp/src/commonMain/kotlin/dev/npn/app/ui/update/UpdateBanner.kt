package dev.npn.app.ui.update

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.BuildInfo
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.TopBanner
import dev.npn.app.ui.common.WithTopBanner
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.update.AppRelease
import dev.npn.core.update.AppVersions
import dev.npn.core.update.GitHubLatestRelease
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_dismiss
import no_papers_needed.composeapp.generated.resources.ux_update_available
import no_papers_needed.composeapp.generated.resources.ux_update_download
import org.jetbrains.compose.resources.stringResource

/**
 * Puts the "new version available" banner above [content] (see [WithTopBanner]). A dismissal that
 * cannot be saved says so on [snackbar].
 */
@Composable
internal fun WithUpdateBanner(
    settings: SettingsRepository,
    snackbar: SnackbarHostState,
    vm: UpdateNoticeViewModel =
        viewModel {
            UpdateNoticeViewModel(
                settings,
                // A beta build is offered betas and stable releases; a stable build only stable ones.
                GitHubLatestRelease(includePrereleases = AppVersions.isPreRelease(BuildInfo.VERSION)),
            )
        },
    content: @Composable () -> Unit,
) {
    SnackbarMessages(vm.messages, snackbar)
    val release by vm.notice.collectAsStateWithLifecycle()
    val shown = release
    WithTopBanner(
        banner = if (shown == null) null else ({ UpdateBanner(shown, onDismiss = { vm.dismiss(shown) }) }),
        content = content,
    )
}

@Composable
private fun UpdateBanner(
    release: AppRelease,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    // Informational, so the neutral secondary container rather than the draft banner's tertiary.
    TopBanner(
        message = stringResource(Res.string.ux_update_available, release.version),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        TextButton(onClick = { runCatching { uriHandler.openUri(release.url) } }) {
            Text(stringResource(Res.string.ux_update_download))
        }
        TextButton(onClick = onDismiss) { Text(stringResource(Res.string.library_dismiss)) }
    }
}
