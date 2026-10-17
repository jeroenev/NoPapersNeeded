package dev.npn.app.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.update.AppRelease
import dev.npn.core.update.GitHubLatestRelease
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_dismiss
import no_papers_needed.composeapp.generated.resources.ux_update_available
import no_papers_needed.composeapp.generated.resources.ux_update_download
import org.jetbrains.compose.resources.stringResource

/**
 * Puts the "new version available" banner above [content]. Like the draft banner, it takes the
 * status-bar inset while visible and consumes it for [content]. A dismissal that cannot be saved
 * says so on [snackbar].
 */
@Composable
internal fun WithUpdateBanner(
    settings: SettingsRepository,
    snackbar: SnackbarHostState,
    vm: UpdateNoticeViewModel = viewModel { UpdateNoticeViewModel(settings, GitHubLatestRelease()) },
    content: @Composable () -> Unit,
) {
    SnackbarMessages(vm.messages, snackbar)
    val release by vm.notice.collectAsStateWithLifecycle()
    val shown = release
    Column(Modifier.fillMaxSize()) {
        if (shown != null) UpdateBanner(shown, onDismiss = { vm.dismiss(shown) })
        Box(
            Modifier
                .weight(1f)
                .then(if (shown == null) Modifier else Modifier.consumeWindowInsets(WindowInsets.statusBars)),
        ) { content() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateBanner(
    release: AppRelease,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    // Informational, so the neutral secondary container rather than the draft banner's tertiary.
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        FlowRow(
            Modifier.statusBarsPadding().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(Res.string.ux_update_available, release.version),
                modifier = Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { runCatching { uriHandler.openUri(release.url) } }) {
                Text(stringResource(Res.string.ux_update_download))
            }
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.library_dismiss)) }
        }
    }
}
