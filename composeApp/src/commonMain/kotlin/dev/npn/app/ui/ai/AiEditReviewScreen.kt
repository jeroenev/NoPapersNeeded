package dev.npn.app.ui.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.BackTopBar
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.rememberSnackbarMessages
import dev.npn.core.app.LibrarySession
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_applying
import no_papers_needed.composeapp.generated.resources.ai_edit_queued_gone
import no_papers_needed.composeapp.generated.resources.ai_edit_queued_gone_subtitle
import no_papers_needed.composeapp.generated.resources.ai_edit_queued_loading
import no_papers_needed.composeapp.generated.resources.ai_edit_queued_review_title
import org.jetbrains.compose.resources.stringResource

/**
 * Review of one AI edit run that was suggested in the background, reached from the processing
 * queue. Renders the same batch widgets as the interactive run — the only difference is where the
 * suggestions came from.
 */
@Suppress("ViewModelForwarding")
// The batch gets the viewmodel only as its ReviewController interface, the hand-down the conventions prescribe.
@Composable
fun AiEditReviewScreen(
    session: LibrarySession,
    jobId: String,
    onBack: () -> Unit,
    onOpenDoc: (String) -> Unit,
    modifier: Modifier = Modifier,
    vm: AiEditReviewViewModel =
        viewModel(key = jobId) { AiEditReviewViewModel(session, jobId, createSavedStateHandle()) },
) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarMessages(vm.messages)

    Scaffold(
        modifier = modifier,
        topBar = { BackTopBar(title = stringResource(Res.string.ai_edit_queued_review_title), onBack = onBack) },
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
                    ReviewBatch(current.batch, session.content, review = vm, onOpenDoc = onOpenDoc)
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
