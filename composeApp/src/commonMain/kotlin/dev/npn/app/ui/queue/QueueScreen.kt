@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.queue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.StatusBadge
import dev.npn.app.ui.common.TechnicalDetails
import dev.npn.app.ui.common.formatEpochDateTime
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.QueuedEditRun
import dev.npn.core.app.QueuedWork
import dev.npn.core.app.WorkStage
import dev.npn.core.app.WorkState
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_retry
import no_papers_needed.composeapp.generated.resources.queue_attempts
import no_papers_needed.composeapp.generated.resources.queue_edit_run_discard
import no_papers_needed.composeapp.generated.resources.queue_edit_run_documents
import no_papers_needed.composeapp.generated.resources.queue_edit_run_failures
import no_papers_needed.composeapp.generated.resources.queue_edit_run_progress
import no_papers_needed.composeapp.generated.resources.queue_edit_run_review
import no_papers_needed.composeapp.generated.resources.queue_edit_runs
import no_papers_needed.composeapp.generated.resources.queue_empty
import no_papers_needed.composeapp.generated.resources.queue_empty_subtitle
import no_papers_needed.composeapp.generated.resources.queue_failed_help
import no_papers_needed.composeapp.generated.resources.queue_help
import no_papers_needed.composeapp.generated.resources.queue_open_document
import no_papers_needed.composeapp.generated.resources.queue_retry_after
import no_papers_needed.composeapp.generated.resources.queue_stage_ai
import no_papers_needed.composeapp.generated.resources.queue_stage_ocr
import no_papers_needed.composeapp.generated.resources.queue_state_failed
import no_papers_needed.composeapp.generated.resources.queue_state_ready
import no_papers_needed.composeapp.generated.resources.queue_state_retrying
import no_papers_needed.composeapp.generated.resources.queue_state_running
import no_papers_needed.composeapp.generated.resources.queue_state_waiting
import no_papers_needed.composeapp.generated.resources.queue_temporary_failure
import no_papers_needed.composeapp.generated.resources.queue_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The processing queue: everything the OCR and AI-enrichment passes still owe, plus the AI edit
 * runs handed to the background. The single presentation of that list: a top-level destination
 * (the rail on wide layouts, the bottom bar's "More" sheet on compact ones), also reached from the
 * library's progress indicators and the AI edit page.
 *
 * Edit runs sit at the top and finished ones first, because they are the only entries that need a
 * person: a finished run is a review waiting to be opened, while everything below it clears itself.
 */
@Composable
fun QueueScreen(
    session: LibrarySession,
    onOpenDoc: (String) -> Unit,
    onOpenEditRun: (String) -> Unit,
) {
    val vm: QueueViewModel = viewModel { QueueViewModel(session) }
    val items by vm.items.collectAsStateWithLifecycle()
    val editRuns by vm.editRuns.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(vm.messages, snackbar)

    Scaffold(
        topBar = {
            // No back arrow: the queue is a top-level destination (rail / settings / banners), not a
            // pushed screen.
            TopAppBar(title = { Text(stringResource(Res.string.queue_title)) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        QueueList(
            items = items,
            editRuns = editRuns,
            onOpenDoc = onOpenDoc,
            onOpenEditRun = onOpenEditRun,
            onDiscardEditRun = vm::discardEditRun,
            onRetry = vm::retry,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@Composable
private fun QueueList(
    items: List<QueuedWork>,
    editRuns: List<QueuedEditRun>,
    onOpenDoc: (String) -> Unit,
    onOpenEditRun: (String) -> Unit,
    onDiscardEditRun: (String) -> Unit,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty() && editRuns.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.DoneAll,
            title = stringResource(Res.string.queue_empty),
            modifier = modifier,
            subtitle = stringResource(Res.string.queue_empty_subtitle),
        )
        return
    }
    LazyColumn(modifier) {
        if (editRuns.isNotEmpty()) {
            item { SectionHeader(stringResource(Res.string.queue_edit_runs)) }
            items(editRuns, key = { "run:${it.jobId}" }) { run ->
                EditRunRow(run, onOpenEditRun, onDiscardEditRun)
                HorizontalDivider()
            }
        }
        item {
            HelpText(
                stringResource(Res.string.queue_help),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        // One document can be owed both passes, so the stage is part of the identity.
        items(items, key = { "${it.stage}:${it.docId}" }) { entry ->
            QueueRow(entry, onOpenDoc, onRetry)
            HorizontalDivider()
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * One background edit run. A finished run is the whole point of the section, so it is the one that
 * is clickable and leads with its call to action; a run still being suggested shows how far along
 * it is and offers only the way out.
 */
@Composable
private fun EditRunRow(
    run: QueuedEditRun,
    onOpen: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    val ready = run.state == WorkState.READY
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (ready) Modifier.clickable { onOpen(run.jobId) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                pluralStringResource(Res.plurals.queue_edit_run_documents, run.total, run.total),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    formatEpochDateTime(run.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StateBadge(run.state)
            }
            if (!ready) {
                LinearProgressIndicator(
                    progress = { if (run.total == 0) 0f else run.done.toFloat() / run.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(Res.string.queue_edit_run_progress, run.done, run.total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EditRunTrouble(run)
        }
        if (ready) {
            TextButton(onClick = { onOpen(run.jobId) }) {
                Text(stringResource(Res.string.queue_edit_run_review))
            }
        } else {
            TextButton(onClick = { onDiscard(run.jobId) }) {
                Text(stringResource(Res.string.queue_edit_run_discard))
            }
        }
    }
}

/** The "why is this taking so long" line of an edit run: retry timing, errors, given-up documents. */
@Composable
private fun EditRunTrouble(run: QueuedEditRun) {
    run.nextAttemptAt?.let {
        Text(
            stringResource(Res.string.queue_retry_after, formatEpochDateTime(it)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (run.failures > 0) {
        Text(
            pluralStringResource(Res.plurals.queue_edit_run_failures, run.failures, run.failures),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    run.lastError?.let {
        Text(
            stringResource(Res.string.queue_temporary_failure),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TechnicalDetails(it)
    }
}

@Composable
private fun QueueRow(
    entry: QueuedWork,
    onOpenDoc: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    val openLabel = stringResource(Res.string.queue_open_document)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = openLabel,
                onClick = { onOpenDoc(entry.docId) },
            ).padding(horizontal = 16.dp, vertical = 10.dp),
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
        if (entry.state == WorkState.FAILED) {
            TextButton(onClick = { onRetry(entry.docId) }) {
                Text(stringResource(Res.string.common_retry))
            }
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
            stringResource(
                if (entry.state == WorkState.FAILED) {
                    Res.string.queue_failed_help
                } else {
                    Res.string.queue_temporary_failure
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TechnicalDetails(it)
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
            WorkState.READY -> scheme.secondaryContainer to scheme.onSecondaryContainer
        }
    StatusBadge(
        stringResource(state.label),
        background,
        content,
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
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
            WorkState.READY -> Res.string.queue_state_ready
        }
