package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.LabelOff
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.formatEpochDateTime
import dev.npn.app.ui.common.resolve
import dev.npn.core.app.HistoryEntry
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_retry
import no_papers_needed.composeapp.generated.resources.detail_history_load_failed
import no_papers_needed.composeapp.generated.resources.detail_history_newer_changes
import no_papers_needed.composeapp.generated.resources.detail_history_secondary
import no_papers_needed.composeapp.generated.resources.detail_no_history
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Immutable journal timeline for the document: HLC-ordered icon + description rows. */
@Composable
internal fun HistoryTab(
    history: HistoryUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (history) {
            HistoryUiState.NotLoaded, HistoryUiState.Loading -> {
                CircularProgressIndicator()
            }

            HistoryUiState.Empty -> {
                Text(
                    stringResource(Res.string.detail_no_history),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is HistoryUiState.Error -> {
                Text(stringResource(Res.string.detail_history_load_failed, history.message))
                Button(onClick = onRefresh) { Text(stringResource(Res.string.common_retry)) }
            }

            is HistoryUiState.Loaded -> {
                for (item in history.items) {
                    HistoryRow(item)
                }
                // One muted line for what this build cannot read, rather than a row of jargon each.
                if (history.newerChanges > 0) {
                    HelpText(
                        pluralStringResource(
                            Res.plurals.detail_history_newer_changes,
                            history.newerChanges,
                            history.newerChanges,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            imageVector = item.entry.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(item.description.resolve(), style = MaterialTheme.typography.bodyMedium)
            HelpText(
                stringResource(
                    Res.string.detail_history_secondary,
                    formatEpochDateTime(item.timeMillis),
                    item.origin.resolve(),
                ),
            )
        }
    }
}

private fun HistoryEntry.icon(): ImageVector =
    when (this) {
        is HistoryEntry.TagAdded -> Icons.AutoMirrored.Filled.Label
        is HistoryEntry.TagRemoved -> Icons.AutoMirrored.Filled.LabelOff
        is HistoryEntry.TagRenamed -> Icons.Filled.Edit
        is HistoryEntry.TagMerged -> Icons.AutoMirrored.Filled.CallMerge
        is HistoryEntry.TagStyled -> Icons.Filled.Palette
        is HistoryEntry.Reviewed, is HistoryEntry.ReviewUnreadable -> Icons.Filled.CheckCircle
        is HistoryEntry.OcrEnginePinned -> Icons.Filled.Settings
        is HistoryEntry.StoragePathSet -> Icons.AutoMirrored.Filled.DriveFileMove
        is HistoryEntry.FieldSet -> Icons.Filled.TextFields
        is HistoryEntry.Trashed -> Icons.Filled.Delete
        HistoryEntry.Restored -> Icons.Filled.RestoreFromTrash
        HistoryEntry.Deleted -> Icons.Filled.DeleteForever
        is HistoryEntry.Relinked -> Icons.Filled.Link
        is HistoryEntry.SettingChanged -> Icons.Filled.Tune
        is HistoryEntry.Unknown, is HistoryEntry.Undecodable -> Icons.AutoMirrored.Filled.Help
    }
