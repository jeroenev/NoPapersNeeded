@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.core.app.LibrarySession
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_applying
import no_papers_needed.composeapp.generated.resources.ai_edit_queued_gone
import no_papers_needed.composeapp.generated.resources.ai_edit_queued_gone_subtitle
import no_papers_needed.composeapp.generated.resources.ai_edit_queued_loading
import no_papers_needed.composeapp.generated.resources.ai_edit_queued_review_title
import no_papers_needed.composeapp.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource

/**
 * Review of one AI edit run that was suggested in the background, reached from the processing
 * queue. Renders the same batch widgets as the interactive run — the only difference is where the
 * suggestions came from.
 */
@Composable
fun AiEditReviewScreen(
    session: LibrarySession,
    jobId: String,
    onBack: () -> Unit,
) {
    val vm: AiEditReviewViewModel = viewModel(key = jobId) { AiEditReviewViewModel(session, jobId) }
    val phase by vm.phase.collectAsStateWithLifecycle()
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
                onSkip = vm::skip,
            )
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.ai_edit_queued_review_title)) },
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
            when (val current = phase) {
                QueuedReviewPhase.Loading -> {
                    CenteredProgress(stringResource(Res.string.ai_edit_queued_loading))
                }

                QueuedReviewPhase.Unavailable -> {
                    EmptyState(
                        icon = Icons.Filled.HourglassEmpty,
                        title = stringResource(Res.string.ai_edit_queued_gone),
                        subtitle = stringResource(Res.string.ai_edit_queued_gone_subtitle),
                    )
                }

                is QueuedReviewPhase.Review -> {
                    ReviewBatch(current.docs, current.emptyDocs, session.content, actions)
                }

                QueuedReviewPhase.Applying -> {
                    CenteredProgress(stringResource(Res.string.ai_edit_applying))
                }

                is QueuedReviewPhase.Done -> {
                    DonePhase(current.result, onNextBatch = null, onBack = onBack)
                }
            }
        }
    }
}
