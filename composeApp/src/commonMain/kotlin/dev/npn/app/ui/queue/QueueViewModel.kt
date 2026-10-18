package dev.npn.app.ui.queue

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.QueuedEditRun
import dev.npn.core.app.QueuedWork
import dev.npn.core.app.WorkFacade
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_rerun_ocr_failed
import no_papers_needed.composeapp.generated.resources.queue_edit_run_discard_failed

/**
 * The processing queue: what the OCR and AI-enrichment passes still owe ([items]), and the AI edit
 * runs handed to the background ([editRuns]).
 *
 * Almost read-only — the queues run themselves and nothing here can hurry them. The exceptions are
 * [retry] for a document whose OCR stopped, and [discardEditRun]: an edit run is work the user chose
 * to start, and a run over the wrong scope or against a dead endpoint must be abandonable without
 * waiting for it to finish first. Both report a failure through [messages].
 */
class QueueViewModel(
    session: LibrarySession,
) : MessagesViewModel() {
    private val editQueue = session.aiEditQueue
    private val ocr = session.ocr

    /** Null until the first load, so the screen does not flash "nothing queued" before it. */
    val items: StateFlow<List<QueuedWork>?> =
        session.work.queue.stateIn(viewModelScope, WhileSubscribedTimeout, null)

    /** Queued AI edit runs, finished ones first — see [WorkFacade.editRuns]. Null until the first load. */
    val editRuns: StateFlow<List<QueuedEditRun>?> =
        session.work.editRuns.stateIn(viewModelScope, WhileSubscribedTimeout, null)

    /** Abandon a queued edit run and the suggestions it has bought so far. */
    fun discardEditRun(jobId: String) =
        launchReporting(Res.string.queue_edit_run_discard_failed) { editQueue.discardEditRun(jobId) }

    /** Reset a terminal OCR failure to pending and kick its worker through the normal OCR seam. */
    fun retry(docId: String) = launchReporting(Res.string.common_rerun_ocr_failed) { ocr.reOcr(docId) }
}
