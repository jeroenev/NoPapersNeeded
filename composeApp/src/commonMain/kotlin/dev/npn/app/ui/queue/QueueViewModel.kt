package dev.npn.app.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.QueuedWork
import dev.npn.core.app.WorkFacade
import dev.npn.core.app.WorkStage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The read-only processing queue: what the OCR and AI-enrichment passes still owe. The queues run
 * themselves, so this view has nothing to command — hence a plain [ViewModel] rather than a
 * `MessagesViewModel`, with no action can fail and no snackbar to report to.
 */
class QueueViewModel(
    work: WorkFacade,
) : ViewModel() {
    constructor(session: LibrarySession) : this(session.work)

    val items: StateFlow<List<QueuedWork>> =
        work.queue.stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    /** Per-stage counts, for the callers that only want a badge rather than the whole list. */
    val counts: StateFlow<QueueCounts> =
        work.queue
            .map { entries -> QueueCounts.of(entries) }
            .stateIn(viewModelScope, WhileSubscribedTimeout, QueueCounts())
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
