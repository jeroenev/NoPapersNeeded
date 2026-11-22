@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.resolve
import dev.npn.core.index.IndexProgress
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_conflict_file_count
import no_papers_needed.composeapp.generated.resources.library_dismiss
import no_papers_needed.composeapp.generated.resources.library_duplicate_count
import no_papers_needed.composeapp.generated.resources.library_path_conflict_count
import no_papers_needed.composeapp.generated.resources.queue_open_trash
import no_papers_needed.composeapp.generated.resources.queue_processing
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The progress and warning banners above the results. The last scan's issues are one dismissable
 * banner ([dismissed] for this scan's report); [onShowDocuments] narrows the library to the documents
 * that could not be moved to their new location, which a scan can report by the thousand (every one
 * a storage-path rule names, on storage that cannot move documents), so the banner only counts them.
 * What text recognition and Auto-organize still owe is one "Processing N documents" line that opens
 * Activity ([onOpenQueue]), where any failure is detailed.
 */
@Composable
internal fun Banners(
    banners: LibraryBanners,
    dismissed: Boolean,
    onDismiss: () -> Unit,
    onGoToTrash: () -> Unit,
    onShowDocuments: (docIds: List<String>) -> Unit,
    onOpenQueue: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        banners.scanProgress?.let { ScanProgressBanner(it) }
        val lastScan = banners.lastScan
        val conflicts = lastScan?.conflictFiles.orEmpty()
        val dupes = lastScan?.duplicates.orEmpty()
        val pathConflicts = lastScan?.pathConflicts.orEmpty()
        if (!dismissed && conflicts.size + dupes.size + pathConflicts.size > 0) {
            ScanIssuesBanner(
                conflictCount = conflicts.size,
                duplicateCount = dupes.size,
                pathConflictCount = pathConflicts.size,
                onGoToTrash = onGoToTrash,
                onShowPathConflicts = { onShowDocuments(pathConflicts.map { it.docId }) },
                onDismiss = onDismiss,
            )
        }
        // One line for both passes: which pass a document waits on, and why one stopped, is
        // Activity's to say. A document is owed one pass at a time, so the counts add up.
        val processing = banners.ocr.pending + banners.enrichmentPending
        if (processing > 0) {
            QueueStatusIndicator(
                icon = Icons.Filled.Sync,
                label = pluralStringResource(Res.plurals.queue_processing, processing, processing),
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

/**
 * The last scan's issues, one sentence per kind. Conflict files and duplicates are reviewed in the
 * trash; the documents that could not be moved are a link that shows them in the library.
 */
@Composable
private fun ScanIssuesBanner(
    conflictCount: Int,
    duplicateCount: Int,
    pathConflictCount: Int,
    onGoToTrash: () -> Unit,
    onShowPathConflicts: () -> Unit,
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
                if (pathConflictCount > 0) {
                    Text(
                        pluralStringResource(
                            Res.plurals.library_path_conflict_count,
                            pathConflictCount,
                            pathConflictCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable(role = Role.Button, onClick = onShowPathConflicts),
                    )
                }
            }
            if (conflictCount > 0 || duplicateCount > 0) {
                TextButton(onClick = onGoToTrash) { Text(stringResource(Res.string.queue_open_trash)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.library_dismiss)) }
        }
    }
}

/** One line of "Processing N documents", clickable through to Activity behind the count. */
@Composable
private fun QueueStatusIndicator(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
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
