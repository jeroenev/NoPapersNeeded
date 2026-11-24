package dev.npn.app.ui.queue

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.QueuedEditRun
import dev.npn.core.app.QueuedWork
import dev.npn.core.app.WorkBlock
import dev.npn.core.app.WorkCapabilities
import dev.npn.core.app.WorkFacade
import dev.npn.core.app.blockedBy
import dev.npn.core.index.IndexProgress
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.settings.aiConfigured
import dev.npn.core.sync.SyncOutcome
import dev.npn.core.sync.SyncRunSummary
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_rerun_ocr_failed
import no_papers_needed.composeapp.generated.resources.queue_edit_run_discard_failed
import no_papers_needed.composeapp.generated.resources.queue_sync_not_configured
import no_papers_needed.composeapp.generated.resources.queue_sync_now_failed
import no_papers_needed.composeapp.generated.resources.queue_sync_unavailable

/**
 * The cloud sync as Activity shows it: a pass [running] now, and how the [lastRun] ended (null
 * before the first). Why a pass failed, and the engine behind it, stay in the cloud sync settings.
 */
data class ActivitySync(
    val running: Boolean,
    val lastRun: SyncRunSummary?,
) {
    /** The last pass ended early, and none is running to set it right: a problem, shown first. */
    val failed: Boolean get() = !running && lastRun?.ok == false
}

/**
 * One entry of Activity's queue with [block], what keeps it from making progress on this device.
 * A blocked entry is not waiting for its turn: it needs a fix, and Activity shows it as a problem
 * with the way there rather than as work in motion.
 */
data class ActivityWork(
    val entry: QueuedWork,
    val block: WorkBlock?,
)

/**
 * Activity, the processing queue's page: what the text recognition and Auto-organize passes still
 * owe ([items]), the bulk edits handed to the background ([editRuns]), a library scan or import in
 * progress ([scan]) and the cloud sync ([sync]).
 *
 * Almost read-only — the queues run themselves and nothing here can hurry them. The exceptions are
 * [retry] for a document whose text recognition stopped, [discardEditRun]: a bulk edit is work the
 * user chose to start, and one over the wrong scope or against a dead endpoint must be abandonable
 * without waiting for it to finish first; and [syncNow]. Each reports a failure through [messages].
 */
class QueueViewModel(
    session: LibrarySession,
    /** The device settings, followed for whether an assistant endpoint is configured. */
    settings: SettingsRepository,
) : MessagesViewModel() {
    private val editQueue = session.aiEditQueue
    private val ocr = session.ocr
    private val cloud = session.sync

    /** Null until the first load, so the screen does not flash "nothing queued" before it. */
    val items: StateFlow<List<ActivityWork>?> =
        combine(
            session.work.queue,
            settings.aiConfigured(),
        ) { queue, aiConfigured ->
            val capabilities = WorkCapabilities(aiEndpointConfigured = aiConfigured)
            queue.map { ActivityWork(it, it.blockedBy(capabilities)) }
        }.stateIn(viewModelScope, WhileSubscribedTimeout, null)

    /** Queued bulk edits, finished ones first — see [WorkFacade.editRuns]. Null until the first load. */
    val editRuns: StateFlow<List<QueuedEditRun>?> =
        session.work.editRuns.stateIn(viewModelScope, WhileSubscribedTimeout, null)

    /** The library scan (an import, a rescan) in progress, or null while none is. */
    val scan: StateFlow<IndexProgress?> =
        session.ingest.scanProgress.stateIn(viewModelScope, WhileSubscribedTimeout, null)

    /** The cloud sync, or null while this library has no cloud folder on this device. */
    val sync: StateFlow<ActivitySync?> =
        combine(cloud.config, cloud.status) { config, status ->
            if (config.configured) ActivitySync(status.running, status.lastRun) else null
        }.stateIn(viewModelScope, WhileSubscribedTimeout, null)

    /** Abandon a queued bulk edit and the suggestions it has bought so far. */
    fun discardEditRun(jobId: String) =
        launchReporting(Res.string.queue_edit_run_discard_failed) { editQueue.discardEditRun(jobId) }

    /** Reset a terminal text recognition failure to pending and kick its worker through the normal seam. */
    fun retry(docId: String) = launchReporting(Res.string.common_rerun_ocr_failed) { ocr.reOcr(docId) }

    /**
     * Run a cloud sync pass now. How it went shows on the sync row, which points a failure to the
     * cloud sync settings; only a pass that could not start at all is said here.
     */
    fun syncNow() =
        launchReporting(Res.string.queue_sync_now_failed) {
            when (cloud.syncNow()) {
                is SyncOutcome.Unavailable -> message(uiText(Res.string.queue_sync_unavailable))
                SyncOutcome.NotConfigured -> message(uiText(Res.string.queue_sync_not_configured))
                is SyncOutcome.Completed, is SyncOutcome.Failed, SyncOutcome.AlreadyRunning -> Unit
            }
        }
}
