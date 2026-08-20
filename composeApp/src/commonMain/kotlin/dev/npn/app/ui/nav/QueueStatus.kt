package dev.npn.app.ui.nav

import dev.npn.core.app.QueuedEditRun
import dev.npn.core.app.QueuedWork
import dev.npn.core.app.WorkFacade
import dev.npn.core.app.WorkState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * What the processing queue is carrying, condensed to what a navigation badge can say.
 *
 * Two numbers rather than one, because they ask different things of the user. [pending] is the app
 * being busy — documents owed OCR or enrichment, edit runs still buying suggestions — and clears
 * itself given time and a network. [awaitingReview] is a background AI edit that has finished and
 * will sit in the queue until someone opens or discards it: work that is *owed the user*, and the
 * only reason to interrupt them. The shell shows it in preference to [pending] for that reason.
 */
data class QueueStatus(
    val pending: Int = 0,
    val awaitingReview: Int = 0,
) {
    val isEmpty: Boolean get() = pending == 0 && awaitingReview == 0

    companion object {
        val None = QueueStatus()

        fun of(
            work: List<QueuedWork>,
            editRuns: List<QueuedEditRun>,
        ) = QueueStatus(
            // Entries rather than per-stage counts, so a stage added later still shows up.
            pending = work.size + editRuns.count { it.state != WorkState.READY },
            awaitingReview = editRuns.count { it.state == WorkState.READY },
        )
    }
}

/** The [QueueStatus] as the queues change, for the shell's badge. */
fun WorkFacade.queueStatus(): Flow<QueueStatus> =
    combine(queue, editRuns) { work, editRuns -> QueueStatus.of(work, editRuns) }
