package dev.npn.app.ui.nav

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.npn.app.ui.common.TopBanner
import dev.npn.app.ui.common.WithTopBanner
import dev.npn.core.sync.SyncStatus
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_cloud_banner_failed
import no_papers_needed.composeapp.generated.resources.common_cloud_banner_open
import no_papers_needed.composeapp.generated.resources.library_dismiss
import org.jetbrains.compose.resources.stringResource

/**
 * Puts a "cloud sync failed" banner above [content] (see [WithTopBanner]) while the library's last
 * pass failed, so a sync that stopped working is not discovered weeks later in Settings. It goes
 * once a pass succeeds, or when dismissed (until the next failure); [onOpenSettings] opens the cloud
 * sync settings, which say why and what to do.
 */
@Composable
internal fun WithCloudSyncBanner(
    status: SyncStatus,
    onOpenSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    val failed = status.lastRun?.takeIf { !it.ok && !status.running }
    var dismissed by remember { mutableStateOf<Long?>(null) }
    val shown = failed?.takeIf { it.finishedAt != dismissed }
    WithTopBanner(
        banner =
            shown?.let { run ->
                {
                    CloudSyncBanner(
                        onOpenSettings = onOpenSettings,
                        onDismiss = { dismissed = run.finishedAt },
                    )
                }
            },
        content = content,
    )
}

@Composable
private fun CloudSyncBanner(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    TopBanner(
        message = stringResource(Res.string.common_cloud_banner_failed),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        TextButton(onClick = onOpenSettings) { Text(stringResource(Res.string.common_cloud_banner_open)) }
        TextButton(onClick = onDismiss) { Text(stringResource(Res.string.library_dismiss)) }
    }
}
