@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.resolve
import dev.npn.core.index.IndexProgress
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_conflict_file_count
import no_papers_needed.composeapp.generated.resources.library_dismiss
import no_papers_needed.composeapp.generated.resources.library_duplicate_count
import no_papers_needed.composeapp.generated.resources.library_ocr_pending
import no_papers_needed.composeapp.generated.resources.library_ocr_pending_running
import no_papers_needed.composeapp.generated.resources.library_review
import no_papers_needed.composeapp.generated.resources.queue_ai_pending
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun Banners(
    banners: LibraryBanners,
    dismissed: Boolean,
    onDismiss: () -> Unit,
    onGoToTrash: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        banners.scanProgress?.let { ScanProgressBanner(it) }
        val lastScan = banners.lastScan
        PathConflictsBanner(lastScan?.pathConflicts.orEmpty())
        val conflicts = lastScan?.conflictFiles.orEmpty()
        val dupes = lastScan?.duplicates.orEmpty()
        if (!dismissed && (conflicts.isNotEmpty() || dupes.isNotEmpty())) {
            ScanIssuesBanner(conflicts.size, dupes.size, onGoToTrash, onDismiss)
        }
        // Both indicators open the same queue view, which is the detail behind either count.
        val ocr = banners.ocr
        if (ocr.pending > 0) {
            QueueStatusIndicator(
                icon = Icons.Filled.Refresh,
                label =
                    pluralStringResource(
                        if (ocr.running) Res.plurals.library_ocr_pending_running else Res.plurals.library_ocr_pending,
                        ocr.pending,
                        ocr.pending,
                    ),
                onClick = onOpenQueue,
            )
        }
        val enrichment = banners.enrichmentPending
        if (enrichment > 0) {
            QueueStatusIndicator(
                icon = Icons.Filled.AutoAwesome,
                label = pluralStringResource(Res.plurals.queue_ai_pending, enrichment, enrichment),
                onClick = onOpenQueue,
            )
        }
    }
}

@Composable
private fun ScanProgressBanner(scanProgress: IndexProgress) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(scanProgress.bannerText().resolve(), style = MaterialTheme.typography.bodyMedium)
            val fraction =
                if (scanProgress.total > 0) scanProgress.hashed.toFloat() / scanProgress.total else 0f
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ScanIssuesBanner(
    conflictCount: Int,
    duplicateCount: Int,
    onGoToTrash: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            // One sentence per kind of issue, each on its own line.
            Column(Modifier.weight(1f)) {
                if (conflictCount > 0) {
                    Text(
                        pluralStringResource(Res.plurals.library_conflict_file_count, conflictCount, conflictCount),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (duplicateCount > 0) {
                    Text(
                        pluralStringResource(Res.plurals.library_duplicate_count, duplicateCount, duplicateCount),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TextButton(onClick = onGoToTrash) { Text(stringResource(Res.string.library_review)) }
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.library_dismiss)) }
        }
    }
}

/** One line of "N documents still waiting", clickable through to the queue view behind the count. */
@Composable
private fun QueueStatusIndicator(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HelpText(label)
    }
}
