@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.queue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.LoadingIndicator
import dev.npn.app.ui.common.SectionHeading
import dev.npn.app.ui.common.StatusBadge
import dev.npn.app.ui.common.TechnicalDetails
import dev.npn.app.ui.common.formatEpochDateTime
import dev.npn.app.ui.common.rememberSnackbarMessages
import dev.npn.app.ui.common.resolve
import dev.npn.app.ui.library.bannerText
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.QueuedEditRun
import dev.npn.core.app.QueuedWork
import dev.npn.core.app.WorkStage
import dev.npn.core.app.WorkState
import dev.npn.core.index.IndexProgress
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_cancel_confirm
import no_papers_needed.composeapp.generated.resources.ai_edit_cancel_keep
import no_papers_needed.composeapp.generated.resources.ai_edit_cancel_message
import no_papers_needed.composeapp.generated.resources.ai_edit_cancel_title
import no_papers_needed.composeapp.generated.resources.common_retry
import no_papers_needed.composeapp.generated.resources.queue_ai_failed_help
import no_papers_needed.composeapp.generated.resources.queue_ai_temporary_failure
import no_papers_needed.composeapp.generated.resources.queue_attempts
import no_papers_needed.composeapp.generated.resources.queue_edit_run_discard
import no_papers_needed.composeapp.generated.resources.queue_edit_run_documents
import no_papers_needed.composeapp.generated.resources.queue_edit_run_failures
import no_papers_needed.composeapp.generated.resources.queue_edit_run_progress
import no_papers_needed.composeapp.generated.resources.queue_edit_run_review
import no_papers_needed.composeapp.generated.resources.queue_edit_runs
import no_papers_needed.composeapp.generated.resources.queue_empty
import no_papers_needed.composeapp.generated.resources.queue_empty_subtitle
import no_papers_needed.composeapp.generated.resources.queue_help
import no_papers_needed.composeapp.generated.resources.queue_ocr_failed_help
import no_papers_needed.composeapp.generated.resources.queue_ocr_temporary_failure
import no_papers_needed.composeapp.generated.resources.queue_open_document
import no_papers_needed.composeapp.generated.resources.queue_retry_after
import no_papers_needed.composeapp.generated.resources.queue_section_imports
import no_papers_needed.composeapp.generated.resources.queue_section_problems
import no_papers_needed.composeapp.generated.resources.queue_section_sync
import no_papers_needed.composeapp.generated.resources.queue_stage_ai
import no_papers_needed.composeapp.generated.resources.queue_stage_ocr
import no_papers_needed.composeapp.generated.resources.queue_state_failed
import no_papers_needed.composeapp.generated.resources.queue_state_ready
import no_papers_needed.composeapp.generated.resources.queue_state_retrying
import no_papers_needed.composeapp.generated.resources.queue_state_running
import no_papers_needed.composeapp.generated.resources.queue_state_waiting
import no_papers_needed.composeapp.generated.resources.queue_sync_failed
import no_papers_needed.composeapp.generated.resources.queue_sync_last
import no_papers_needed.composeapp.generated.resources.queue_sync_never
import no_papers_needed.composeapp.generated.resources.queue_sync_now
import no_papers_needed.composeapp.generated.resources.queue_sync_running
import no_papers_needed.composeapp.generated.resources.queue_sync_settings
import no_papers_needed.composeapp.generated.resources.queue_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Activity: what the app is doing in the background, problems first. Work that stopped and the
 * cloud sync when its last pass failed lead; then the bulk edits handed to the background (finished
 * ones first, being reviews waiting to be opened), a library scan or import in progress, what text
 * recognition and Auto-organize still owe, and the cloud sync's status with Sync now.
 *
 * The single presentation of that list: a top-level destination with its own item in the rail and
 * the compact bar, also reached from the library's "Processing N documents" line and bulk edit.
 */
@Composable
fun QueueScreen(
    session: LibrarySession,
    onOpenDoc: (String) -> Unit,
    onOpenEditRun: (String) -> Unit,
    onOpenCloudSettings: () -> Unit,
    modifier: Modifier = Modifier,
    vm: QueueViewModel = viewModel { QueueViewModel(session) },
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val editRuns by vm.editRuns.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarMessages(vm.messages)

    Scaffold(
        modifier = modifier,
        topBar = {
            // No back arrow: Activity is a top-level destination (rail / bar / banners), not a pushed
            // screen.
            TopAppBar(title = { Text(stringResource(Res.string.queue_title)) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val loadedItems = items
        val loadedRuns = editRuns
        if (loadedItems == null || loadedRuns == null) {
            LoadingIndicator(Modifier.padding(padding))
        } else {
            ActivityList(
                activity = Activity(loadedItems, loadedRuns, scan, sync),
                actions =
                    ActivityActions(
                        onOpenDoc = onOpenDoc,
                        onOpenEditRun = onOpenEditRun,
                        onDiscardEditRun = vm::discardEditRun,
                        onRetry = vm::retry,
                        onSyncNow = vm::syncNow,
                        onOpenCloudSettings = onOpenCloudSettings,
                    ),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

/** Everything Activity shows, split the way the page orders it. */
private class Activity(
    items: List<QueuedWork>,
    val editRuns: List<QueuedEditRun>,
    val scan: IndexProgress?,
    val sync: ActivitySync?,
) {
    /** Work that stopped and waits for a retry. */
    val stopped = items.filter { it.state == WorkState.FAILED }
    val ocr = items.filter { it.stage == WorkStage.OCR && it.state != WorkState.FAILED }
    val autoOrganize = items.filter { it.stage == WorkStage.AI_ENRICHMENT && it.state != WorkState.FAILED }

    val hasProblems: Boolean get() = stopped.isNotEmpty() || sync?.failed == true
    val idle: Boolean
        get() = stopped.isEmpty() && editRuns.isEmpty() && scan == null && ocr.isEmpty() && autoOrganize.isEmpty()
}

/** What the rows of Activity can do. */
private class ActivityActions(
    val onOpenDoc: (String) -> Unit,
    val onOpenEditRun: (String) -> Unit,
    val onDiscardEditRun: (String) -> Unit,
    val onRetry: (String) -> Unit,
    val onSyncNow: () -> Unit,
    val onOpenCloudSettings: () -> Unit,
)

@Composable
private fun ActivityList(
    activity: Activity,
    actions: ActivityActions,
    modifier: Modifier = Modifier,
) {
    if (activity.idle && activity.sync == null) {
        EmptyState(
            icon = Icons.Filled.DoneAll,
            title = stringResource(Res.string.queue_empty),
            modifier = modifier,
            subtitle = stringResource(Res.string.queue_empty_subtitle),
        )
        return
    }
    val sync = activity.sync
    LazyColumn(modifier) {
        if (activity.hasProblems) {
            heading(Res.string.queue_section_problems)
            if (sync != null && sync.failed) {
                item(key = "sync") { SyncRow(sync, actions) }
            }
            workRows(activity.stopped, actions)
        }
        if (activity.editRuns.isNotEmpty()) {
            heading(Res.string.queue_edit_runs)
            items(activity.editRuns, key = { "run:${it.jobId}" }) { run ->
                EditRunRow(run, actions.onOpenEditRun, actions.onDiscardEditRun)
                HorizontalDivider()
            }
        }
        activity.scan?.let { scan ->
            heading(Res.string.queue_section_imports)
            item(key = "scan") { ScanRow(scan) }
        }
        if (activity.ocr.isNotEmpty() || activity.autoOrganize.isNotEmpty()) {
            item(key = "help") {
                HelpText(
                    stringResource(Res.string.queue_help),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        if (activity.ocr.isNotEmpty()) {
            heading(Res.string.queue_stage_ocr)
            workRows(activity.ocr, actions)
        }
        if (activity.autoOrganize.isNotEmpty()) {
            heading(Res.string.queue_stage_ai)
            workRows(activity.autoOrganize, actions)
        }
        if (activity.idle) {
            item(key = "idle") {
                EmptyState(
                    icon = Icons.Filled.DoneAll,
                    title = stringResource(Res.string.queue_empty),
                    subtitle = stringResource(Res.string.queue_empty_subtitle),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            }
        }
        if (sync != null && !sync.failed) {
            heading(Res.string.queue_section_sync)
            item(key = "sync") { SyncRow(sync, actions) }
        }
    }
}

private fun LazyListScope.heading(title: StringResource) {
    item(key = "heading:${title.key}") {
        SectionHeading(
            stringResource(title),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

private fun LazyListScope.workRows(
    entries: List<QueuedWork>,
    actions: ActivityActions,
) {
    // One document can be owed both passes, so the stage is part of the identity.
    items(entries, key = { "${it.stage}:${it.docId}" }) { entry ->
        QueueRow(entry, actions.onOpenDoc, actions.onRetry)
        HorizontalDivider()
    }
}

/** A library scan or import in progress: what it is doing, and how far along. */
@Composable
private fun ScanRow(scan: IndexProgress) {
    ActivityRow(Icons.Filled.FolderOpen) {
        Text(scan.bannerText().resolve(), style = MaterialTheme.typography.titleSmall)
        val fraction = if (scan.total > 0) scan.hashed.toFloat() / scan.total else 0f
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The cloud sync as one row: whether it is syncing, when it last did, or that its last pass did not
 * finish; Sync now, and the way to the cloud sync settings, which say why a pass failed.
 */
@Composable
private fun SyncRow(
    sync: ActivitySync,
    actions: ActivityActions,
) {
    ActivityRow(Icons.Filled.Cloud) {
        val last = sync.lastRun
        when {
            sync.running -> {
                Text(stringResource(Res.string.queue_sync_running), style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            sync.failed -> {
                Text(
                    stringResource(Res.string.queue_sync_failed),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            last == null -> {
                Text(stringResource(Res.string.queue_sync_never), style = MaterialTheme.typography.titleSmall)
            }

            else -> {
                Text(
                    stringResource(Res.string.queue_sync_last, formatEpochDateTime(last.finishedAt)),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.onSyncNow, enabled = !sync.running) {
                Text(stringResource(Res.string.queue_sync_now))
            }
            TextButton(onClick = actions.onOpenCloudSettings) {
                Text(stringResource(Res.string.queue_sync_settings))
            }
        }
    }
}

/** An icon beside a column of content, the layout every Activity row shares. */
@Composable
private fun ActivityRow(
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

/**
 * One background bulk edit. A finished run is the whole point of the section, so it is the one that
 * is clickable and leads with its call to action; a run still being suggested shows how far along
 * it is and offers only the way out, which asks first once answers were already paid for, as the
 * interactive run's Cancel does.
 */
@Composable
private fun EditRunRow(
    run: QueuedEditRun,
    onOpen: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    val ready = run.state == WorkState.READY
    var confirmingDiscard by rememberSaveable(run.jobId) { mutableStateOf(false) }
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
                HelpText(formatEpochDateTime(run.createdAt))
                StateBadge(run.state)
            }
            if (!ready) {
                LinearProgressIndicator(
                    progress = { if (run.total == 0) 0f else run.done.toFloat() / run.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                HelpText(stringResource(Res.string.queue_edit_run_progress, run.done, run.total))
            }
            EditRunTrouble(run)
        }
        if (ready) {
            TextButton(onClick = { onOpen(run.jobId) }) {
                Text(stringResource(Res.string.queue_edit_run_review))
            }
        } else {
            TextButton(onClick = { if (run.done > 0) confirmingDiscard = true else onDiscard(run.jobId) }) {
                Text(stringResource(Res.string.queue_edit_run_discard))
            }
        }
    }
    // A run that finished meanwhile has nothing left to stop: the row offers its review instead.
    if (confirmingDiscard && !ready) {
        ConfirmDialog(
            title = stringResource(Res.string.ai_edit_cancel_title),
            message = pluralStringResource(Res.plurals.ai_edit_cancel_message, run.done, run.done),
            confirmLabel = stringResource(Res.string.ai_edit_cancel_confirm),
            dismissLabel = stringResource(Res.string.ai_edit_cancel_keep),
            destructive = true,
            onConfirm = {
                confirmingDiscard = false
                onDiscard(run.jobId)
            },
            onDismiss = { confirmingDiscard = false },
        )
    }
}

/** The "why is this taking so long" line of a bulk edit: retry timing, errors, given-up documents. */
@Composable
private fun EditRunTrouble(run: QueuedEditRun) {
    run.nextAttemptAt?.let {
        HelpText(stringResource(Res.string.queue_retry_after, formatEpochDateTime(it)))
    }
    if (run.failures > 0) {
        HelpText(pluralStringResource(Res.plurals.queue_edit_run_failures, run.failures, run.failures))
    }
    run.lastError?.let {
        Text(
            stringResource(Res.string.queue_ai_temporary_failure),
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
                HelpText(stringResource(entry.stage.label))
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

/** The "why is this not done yet" line: attempts, next retry, and the last error in the stage's words. */
@Composable
private fun QueueRowTrouble(entry: QueuedWork) {
    if (entry.attempts == 0) return
    HelpText(pluralStringResource(Res.plurals.queue_attempts, entry.attempts, entry.attempts))
    entry.nextAttemptAt?.let {
        HelpText(stringResource(Res.string.queue_retry_after, formatEpochDateTime(it)))
    }
    entry.lastError?.let {
        Text(
            stringResource(entry.failureText()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TechnicalDetails(it)
    }
}

/**
 * What went wrong, said for the pass it went wrong in: text recognition fails on this device (its
 * setup), Auto-organize on the assistant's provider (its reach), so each points somewhere else.
 */
private fun QueuedWork.failureText(): StringResource {
    val stopped = state == WorkState.FAILED
    return when (stage) {
        WorkStage.OCR -> {
            if (stopped) Res.string.queue_ocr_failed_help else Res.string.queue_ocr_temporary_failure
        }

        WorkStage.AI_ENRICHMENT -> {
            if (stopped) Res.string.queue_ai_failed_help else Res.string.queue_ai_temporary_failure
        }
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
