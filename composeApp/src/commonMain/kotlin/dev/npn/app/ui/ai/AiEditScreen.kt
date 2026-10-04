@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.core.app.EditProgress
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SettingsRepository
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_applying
import no_papers_needed.composeapp.generated.resources.ai_edit_cancel_confirm
import no_papers_needed.composeapp.generated.resources.ai_edit_cancel_keep
import no_papers_needed.composeapp.generated.resources.ai_edit_cancel_message
import no_papers_needed.composeapp.generated.resources.ai_edit_cancel_title
import no_papers_needed.composeapp.generated.resources.ai_edit_done
import no_papers_needed.composeapp.generated.resources.ai_edit_last_completed
import no_papers_needed.composeapp.generated.resources.ai_edit_leave_confirm
import no_papers_needed.composeapp.generated.resources.ai_edit_leave_message
import no_papers_needed.composeapp.generated.resources.ai_edit_leave_stay
import no_papers_needed.composeapp.generated.resources.ai_edit_leave_title
import no_papers_needed.composeapp.generated.resources.ai_edit_queued
import no_papers_needed.composeapp.generated.resources.ai_edit_queued_open
import no_papers_needed.composeapp.generated.resources.ai_edit_suggesting_finishing
import no_papers_needed.composeapp.generated.resources.ai_edit_suggesting_progress
import no_papers_needed.composeapp.generated.resources.ai_edit_title
import no_papers_needed.composeapp.generated.resources.common_back
import no_papers_needed.composeapp.generated.resources.common_cancel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The bulk AI-edit page: configure a run, then either watch it suggest and review the result here,
 * or hand it to the background queue and pick the result up from the processing queue later.
 */
@Composable
fun AiEditScreen(
    session: LibrarySession,
    settings: SettingsRepository,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenDoc: (String) -> Unit,
    selection: List<String> = emptyList(),
) {
    val vm: AiEditViewModel = viewModel { AiEditViewModel(session, settings, selection) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(vm.messages, snackbar)

    val actions =
        remember(vm) {
            ReviewActions(
                onToggleChip = vm::toggleChip,
                onToggleField = vm::toggleField,
                onSetDoc = vm::setDocSelection,
                onSetAll = vm::setAllSelection,
                onSelectRecommended = vm::selectRecommended,
                onApply = vm::apply,
                onExit = vm::skipReview,
                onOpenDoc = onOpenDoc,
                exit = ReviewExit.SKIP_BATCH,
            )
        }

    // Leaving mid-flight throws away answers that were already paid for, so Back asks first in
    // exactly the phases where that is true: the top-bar arrow, the system gesture and, on
    // desktop, Esc (which the window routes to the same back dispatcher).
    var confirmingLeave by remember { mutableStateOf(false) }
    val leavingCostsSuggestions = ui.phase.leavingDiscardsSuggestions
    val requestBack = { if (leavingCostsSuggestions) confirmingLeave = true else onBack() }
    LeaveGuard(guarding = leavingCostsSuggestions, onBack = { confirmingLeave = true })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.ai_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            EditPhaseContent(ui, vm, session, actions, onOpenQueue, onBack)
        }
    }
    if (confirmingLeave) {
        ConfirmDialog(
            title = stringResource(Res.string.ai_edit_leave_title),
            message = stringResource(Res.string.ai_edit_leave_message),
            confirmLabel = stringResource(Res.string.ai_edit_leave_confirm),
            dismissLabel = stringResource(Res.string.ai_edit_leave_stay),
            destructive = true,
            onConfirm = {
                confirmingLeave = false
                onBack()
            },
            onDismiss = { confirmingLeave = false },
        )
    }
}

/**
 * Takes Back — the system gesture or Esc on desktop — while [guarding], calling [onBack] instead
 * of leaving; otherwise Back falls through to the navigation host.
 */
@Composable
internal fun LeaveGuard(
    guarding: Boolean,
    onBack: () -> Unit,
) {
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = guarding,
        onBackCompleted = onBack,
    )
}

/** The one step of the flow that is currently on screen; see [EditPhase]. */
@Composable
private fun EditPhaseContent(
    ui: AiEditUiState,
    vm: AiEditViewModel,
    session: LibrarySession,
    actions: ReviewActions,
    onOpenQueue: () -> Unit,
    onBack: () -> Unit,
) {
    when (val phase = ui.phase) {
        is EditPhase.Idle -> {
            AiEditSetup(ui, vm, session.content)
        }

        is EditPhase.Suggesting -> {
            SuggestingPhase(phase.progress, vm::cancel)
        }

        is EditPhase.Review -> {
            ReviewBatch(phase.docs, phase.emptyDocs, session.content, actions)
        }

        is EditPhase.Applying -> {
            CenteredProgress(stringResource(Res.string.ai_edit_applying))
        }

        is EditPhase.Queued -> {
            QueuedPhase(phase.documents, onOpenQueue, onBack)
        }

        is EditPhase.Done -> {
            DonePhase(
                result = phase.result,
                onNextBatch = vm::nextBatch,
                onBack = onBack,
                onReconfigure = vm::reconfigure,
            )
        }
    }
}

/**
 * Suggestion progress.
 *
 * Reports documents *completed*, not the one being worked on: the run is concurrent
 * ([dev.npn.core.app.AiEditRun]), so there is no single current document, and the title the run
 * reports is the one that finished last. The count used to be `done + 1` to read as "working on
 * number N", which meant a finished batch of twelve claimed "13 of 12".
 */
@Composable
private fun SuggestingPhase(
    progress: EditProgress,
    onCancel: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(Res.string.ai_edit_suggesting_progress, progress.done, progress.total),
            style = MaterialTheme.typography.titleMedium,
        )
        val fraction = if (progress.total == 0) 0f else progress.done.toFloat() / progress.total
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        if (progress.done > 0) {
            Text(
                stringResource(Res.string.ai_edit_last_completed, progress.currentDisplayTitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The gap between the last answer and the Review is spent projecting suggestions onto the
        // documents, which on a big batch is long enough to look like a stall.
        if (progress.total > 0 && progress.done == progress.total) {
            Text(
                stringResource(Res.string.ai_edit_suggesting_finishing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = { if (progress.done > 0) confirming = true else onCancel() },
        ) {
            Text(stringResource(Res.string.common_cancel))
        }
    }
    if (confirming) {
        ConfirmDialog(
            title = stringResource(Res.string.ai_edit_cancel_title),
            message = pluralStringResource(Res.plurals.ai_edit_cancel_message, progress.done, progress.done),
            confirmLabel = stringResource(Res.string.ai_edit_cancel_confirm),
            dismissLabel = stringResource(Res.string.ai_edit_cancel_keep),
            destructive = true,
            onConfirm = {
                confirming = false
                onCancel()
            },
            onDismiss = { confirming = false },
        )
    }
}

/**
 * Terminal state of a backgrounded run. Nothing was suggested yet and nothing will be written
 * without review — this only says where the result will turn up.
 */
@Composable
private fun QueuedPhase(
    documents: Int,
    onOpenQueue: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            pluralStringResource(Res.plurals.ai_edit_queued, documents, documents),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onOpenQueue) { Text(stringResource(Res.string.ai_edit_queued_open)) }
        OutlinedButton(onClick = onBack) { Text(stringResource(Res.string.ai_edit_done)) }
    }
}
