package dev.npn.app.ui.nav

import dev.npn.core.app.QueuedEditRun
import dev.npn.core.app.QueuedWork
import dev.npn.core.app.WorkStage
import dev.npn.core.app.WorkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shell's badge is the only place a background AI edit announces that it has finished, so what
 * counts as "waiting on the user" versus "still working" is pinned here rather than left to the
 * composable that draws it.
 */
class QueueStatusTest {
    @Test
    fun anIdleQueueSaysNothing() {
        assertTrue(QueueStatus.of(emptyList(), emptyList()).isEmpty)
        assertEquals(QueueStatus.None, QueueStatus.of(emptyList(), emptyList()))
    }

    @Test
    fun everyOutstandingWorkEntryCountsAsPending() {
        val status =
            QueueStatus.of(
                listOf(work(WorkStage.OCR), work(WorkStage.AI_ENRICHMENT), work(WorkStage.OCR)),
                emptyList(),
            )

        assertEquals(QueueStatus(pending = 3), status)
        assertFalse(status.isEmpty)
    }

    @Test
    fun anUnfinishedEditRunIsPendingLikeAnyOtherWork() {
        val running =
            listOf(WorkState.WAITING, WorkState.RUNNING, WorkState.RETRYING).map { editRun(it) }

        assertEquals(QueueStatus(pending = 3), QueueStatus.of(emptyList(), running))
    }

    @Test
    fun aFinishedEditRunIsWaitingForReviewRatherThanPending() {
        val status = QueueStatus.of(emptyList(), listOf(editRun(WorkState.READY)))

        assertEquals(QueueStatus(awaitingReview = 1), status)
    }

    /** The two are counted apart so the badge can prefer the one the user has to act on. */
    @Test
    fun reviewAndPendingWorkAreCountedSeparately() {
        val status =
            QueueStatus.of(
                listOf(work(WorkStage.OCR)),
                listOf(editRun(WorkState.READY), editRun(WorkState.RUNNING)),
            )

        assertEquals(QueueStatus(pending = 2, awaitingReview = 1), status)
    }

    private fun work(stage: WorkStage) =
        QueuedWork(
            docId = "doc-$stage",
            displayTitle = "doc",
            stage = stage,
            state = WorkState.WAITING,
            attempts = 0,
            updatedAt = 0L,
        )

    private fun editRun(state: WorkState) =
        QueuedEditRun(
            jobId = "job-$state",
            total = 4,
            done = if (state == WorkState.READY) 4 else 1,
            failures = 0,
            state = state,
            createdAt = 0L,
            updatedAt = 0L,
            attempts = 0,
        )
}
