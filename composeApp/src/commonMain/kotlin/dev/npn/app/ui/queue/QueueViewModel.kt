package dev.npn.app.ui.queue

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.QueuedEditRun
import dev.npn.core.app.QueuedWork
import dev.npn.core.app.WorkFacade
import dev.npn.core.app.WorkStage
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_rerun_ocr_failed

/**
 * The processing queue: what the OCR and AI-enrichment passes still owe ([items]), and the AI edit
 * runs handed to the background ([editRuns]).
 *
 * Almost read-only — the queues run themselves and nothing here can hurry them. The one exception
 * is [discardEditRun]: an edit run is work the user chose to start, and a run over the wrong scope
 * or against a dead endpoint must be abandonable without waiting for it to finish first. It is a
 * plain [ViewModel] all the same: dropping a run is a local delete with nothing to report.
 */
class QueueViewModel(
    session: LibrarySession,
) : MessagesViewModel() {
    private val editQueue = session.aiEditQueue
    private val ocr = session.ocr

    val items: StateFlow<List<QueuedWork>> =
        session.work.queue.stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    /** Queued AI edit runs, finished ones first — see [WorkFacade.editRuns]. */
    val editRuns: StateFlow<List<QueuedEditRun>> =
        session.work.editRuns.stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    /** Per-stage counts, for the callers that only want a badge rather than the whole list. */
    val counts: StateFlow<QueueCounts> =
        session.work.queue
            .map { entries -> QueueCounts.of(entries) }
            .stateIn(viewModelScope, WhileSubscribedTimeout, QueueCounts())

    /** Abandon a queued edit run and the suggestions it has bought so far. */
    fun discardEditRun(jobId: String) {
        viewModelScope.launch { runCatchingCancellable { editQueue.discardEditRun(jobId) } }
    }

    /** Reset a terminal OCR failure to pending and kick its worker through the normal OCR seam. */
    fun retry(docId: String) = launchReporting(Res.string.common_rerun_ocr_failed) { ocr.reOcr(docId) }
}

/** How much outstanding work each pass is carrying. */
data class QueueCounts(
    val ocr: Int = 0,
    val enrichment: Int = 0,
) {
    val total: Int get() = ocr + enrichment

    companion object {
        fun of(entries: List<QueuedWork>) =
            QueueCounts(
                ocr = entries.count { it.stage == WorkStage.OCR },
                enrichment = entries.count { it.stage == WorkStage.AI_ENRICHMENT },
            )
    }
}
