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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.core.app.EditProgress
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SettingsRepository
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_applying
import no_papers_needed.composeapp.generated.resources.ai_edit_done
import no_papers_needed.composeapp.generated.resources.ai_edit_queued
import no_papers_needed.composeapp.generated.resources.ai_edit_queued_open
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
                onApply = vm::apply,
                onSkip = vm::skipReview,
            )
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.ai_edit_title)) },
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
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (val phase = ui.phase) {
                is EditPhase.Idle -> AiEditSetup(ui, vm, session.content)
                is EditPhase.Suggesting -> SuggestingPhase(phase.progress, vm::cancel)
                is EditPhase.Review -> ReviewBatch(phase.docs, phase.emptyDocs, session.content, actions)
                is EditPhase.Applying -> CenteredProgress(stringResource(Res.string.ai_edit_applying))
                is EditPhase.Queued -> QueuedPhase(phase.documents, onOpenQueue, onBack)
                is EditPhase.Done -> DonePhase(phase.result, vm::nextBatch, onBack)
            }
        }
    }
}

@Composable
private fun SuggestingPhase(
    progress: EditProgress,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(
                Res.string.ai_edit_suggesting_progress,
                progress.done + 1,
                progress.total,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        val fraction = if (progress.total == 0) 0f else progress.done.toFloat() / progress.total
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text(
            progress.currentDisplayTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onCancel) { Text(stringResource(Res.string.common_cancel)) }
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
