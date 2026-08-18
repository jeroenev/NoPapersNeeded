@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.StatusBadge
import dev.npn.app.ui.common.formatEpochDateTime
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.QueuedWork
import dev.npn.core.app.WorkStage
import dev.npn.core.app.WorkState
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_back
import no_papers_needed.composeapp.generated.resources.queue_attempts
import no_papers_needed.composeapp.generated.resources.queue_empty
import no_papers_needed.composeapp.generated.resources.queue_empty_subtitle
import no_papers_needed.composeapp.generated.resources.queue_help
import no_papers_needed.composeapp.generated.resources.queue_retry_after
import no_papers_needed.composeapp.generated.resources.queue_stage_ai
import no_papers_needed.composeapp.generated.resources.queue_stage_ocr
import no_papers_needed.composeapp.generated.resources.queue_state_failed
import no_papers_needed.composeapp.generated.resources.queue_state_retrying
import no_papers_needed.composeapp.generated.resources.queue_state_running
import no_papers_needed.composeapp.generated.resources.queue_state_waiting
import no_papers_needed.composeapp.generated.resources.queue_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The processing queue: everything the OCR and AI-enrichment passes still owe. The single
 * presentation of that list — reached from the navigation rail on wide layouts, from settings on
 * compact ones, and from the library's progress banners on both.
 */
@Composable
fun QueueScreen(
    session: LibrarySession,
    onBack: () -> Unit,
) {
    val vm: QueueViewModel = viewModel { QueueViewModel(session) }
    val items by vm.items.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.queue_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        QueueList(items, Modifier.fillMaxSize().padding(padding))
    }
}

@Composable
private fun QueueList(
    items: List<QueuedWork>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Box(modifier) {
            EmptyState(
                icon = Icons.Filled.DoneAll,
                title = stringResource(Res.string.queue_empty),
                subtitle = stringResource(Res.string.queue_empty_subtitle),
            )
        }
        return
    }
    LazyColumn(modifier) {
        item {
            Text(
                stringResource(Res.string.queue_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        // One document can be owed both passes, so the stage is part of the identity.
        items(items, key = { "${it.stage}:${it.docId}" }) { entry ->
            QueueRow(entry)
            HorizontalDivider()
        }
    }
}

@Composable
private fun QueueRow(entry: QueuedWork) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            entry.stage.icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                entry.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(entry.stage.label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StateBadge(entry.state)
            }
            QueueRowTrouble(entry)
        }
    }
}

/** The "why is this not done yet" line: attempts, next retry, and the last error. */
@Composable
private fun QueueRowTrouble(entry: QueuedWork) {
    if (entry.attempts == 0) return
    Text(
        pluralStringResource(Res.plurals.queue_attempts, entry.attempts, entry.attempts),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    entry.nextAttemptAt?.let {
        Text(
            stringResource(Res.string.queue_retry_after, formatEpochDateTime(it)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    entry.lastError?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StateBadge(state: WorkState) {
    val scheme = MaterialTheme.colorScheme
    val (background: Color, content: Color) =
        when (state) {
            WorkState.RUNNING -> scheme.primaryContainer to scheme.onPrimaryContainer
            WorkState.WAITING -> scheme.surfaceVariant to scheme.onSurfaceVariant
            WorkState.RETRYING -> scheme.tertiaryContainer to scheme.onTertiaryContainer
            WorkState.FAILED -> scheme.errorContainer to scheme.onErrorContainer
        }
    StatusBadge(stringResource(state.label), background, content)
}

private val WorkStage.icon: ImageVector
    get() =
        when (this) {
            WorkStage.OCR -> Icons.Filled.DocumentScanner
            WorkStage.AI_ENRICHMENT -> Icons.Filled.AutoAwesome
        }

private val WorkStage.label: StringResource
    get() =
        when (this) {
            WorkStage.OCR -> Res.string.queue_stage_ocr
            WorkStage.AI_ENRICHMENT -> Res.string.queue_stage_ai
        }

private val WorkState.label: StringResource
    get() =
        when (this) {
            WorkState.WAITING -> Res.string.queue_state_waiting
            WorkState.RUNNING -> Res.string.queue_state_running
            WorkState.RETRYING -> Res.string.queue_state_retrying
            WorkState.FAILED -> Res.string.queue_state_failed
        }
