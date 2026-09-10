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
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.LabelOff
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.formatEpochDateTime
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_retry
import no_papers_needed.composeapp.generated.resources.common_unknown_error
import no_papers_needed.composeapp.generated.resources.detail_history_load_failed
import no_papers_needed.composeapp.generated.resources.detail_history_secondary
import no_papers_needed.composeapp.generated.resources.detail_no_history
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
                Text(
                    stringResource(
                        Res.string.detail_history_load_failed,
                        history.message.ifBlank { stringResource(Res.string.common_unknown_error) },
                    ),
                )
                Button(onClick = onRefresh) { Text(stringResource(Res.string.common_retry)) }
            }

            is HistoryUiState.Loaded -> {
                for (item in history.items) {
                    HistoryRow(item)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            imageVector = item.kind.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(item.description.resolve(), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    Res.string.detail_history_secondary,
                    formatEpochDateTime(item.hlc.physicalMillis),
                    item.deviceId,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun HistoryEventKind.icon() =
    when (this) {
        HistoryEventKind.TAG_ADD -> Icons.AutoMirrored.Filled.Label
        HistoryEventKind.TAG_REMOVE -> Icons.AutoMirrored.Filled.LabelOff
        HistoryEventKind.PROP_REVIEWED -> Icons.Filled.CheckCircle
        HistoryEventKind.PROP_OCR_ENGINE -> Icons.Filled.Settings
        HistoryEventKind.FIELD_SET -> Icons.Filled.TextFields
        HistoryEventKind.FIELD_REMOVE -> Icons.Filled.TextFields
        HistoryEventKind.TRASH -> Icons.Filled.Delete
        HistoryEventKind.RESTORE -> Icons.Filled.RestoreFromTrash
        HistoryEventKind.DELETE -> Icons.Filled.DeleteForever
        HistoryEventKind.RELINK -> Icons.Filled.Link
        HistoryEventKind.UNKNOWN -> Icons.AutoMirrored.Filled.Help
    }
