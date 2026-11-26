package dev.npn.app.ui.queue

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.QueuedEditRun
import dev.npn.core.app.QueuedWork
import dev.npn.core.app.SyncFacade
import dev.npn.core.app.WorkBlock
import dev.npn.core.app.WorkCapabilities
import dev.npn.core.app.WorkFacade
import dev.npn.core.app.blockedBy
import dev.npn.core.index.IndexProgress
import dev.npn.core.mail.MailFailureReason
import dev.npn.core.mail.MailProgress
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.settings.aiConfigured
import dev.npn.core.sync.SyncOutcome
import dev.npn.core.sync.SyncRunSummary
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_rerun_ocr_failed
import no_papers_needed.composeapp.generated.resources.email_check_failed
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
 * Email import as Activity shows it: a check [checking] now (and its [progress]), when the last
 * one ran and how many documents it imported ([lastImported], null when this session has not
 * checked yet), and [problem], why checks are not going through. Shown only for a configured
 * account on a device that can import email and either checks it or has checked it.
 */
data class ActivityMail(
    val checking: Boolean,
    val cancelling: Boolean,
    val progress: MailProgress?,
    val lastCheckedAtMillis: Long?,
    val lastImported: Int?,
    val problem: MailFailureReason?,
    /** Emails checks have left in the mailbox without importing them fully, over every check. */
    val passedOver: Int = 0,
) {
    /** The last check failed, or the password cannot be read: a problem, shown first. */
    val failed: Boolean get() = problem != null
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
 * progress ([scan]), the cloud sync ([sync]) and email import ([mail]).
 *
 * Almost read-only — the queues run themselves and nothing here can hurry them. The exceptions are
 * [retry] for a document whose text recognition stopped, [discardEditRun]: a bulk edit is work the
 * user chose to start, and one over the wrong scope or against a dead endpoint must be abandonable
 * without waiting for it to finish first; [syncNow]; and [checkMailNow] with [cancelMailCheck]. Each
 * reports a failure through [messages].
 */
class QueueViewModel(
    session: LibrarySession,
    /** The device settings, followed for whether an assistant endpoint is configured. */
    settings: SettingsRepository,
) : MessagesViewModel() {
    private val editQueue = session.aiEditQueue
    private val ocr = session.ocr
    private val cloud = session.sync
    private val mailImport = session.mail

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

    /** Email import, or null while no account is configured or this device cannot import email. */
    val mail: StateFlow<ActivityMail?> =
        mailImport.state
            .map { state ->
                // A device that leaves email to another one shows nothing, not "never checked" or a
                // missing password it was never meant to have.
                val involved = state.enabledHere || state.lastCheckedAtMillis != null || state.checking
                if (state.transportAvailable && state.account.isConfigured && involved) {
                    ActivityMail(
                        checking = state.checking,
                        cancelling = state.cancelling,
                        progress = state.progress,
                        lastCheckedAtMillis = state.lastCheckedAtMillis,
                        lastImported = state.lastReport?.imported,
                        problem = state.problem,
                        passedOver = state.passedOver,
                    )
                } else {
                    null
                }
            }.stateIn(viewModelScope, WhileSubscribedTimeout, null)

    /**
     * Check the mailbox now. How it went shows on the email row, and the documents it imported in
     * the app's own message; only a check that broke is said here. The check outlives this page.
     */
    fun checkMailNow() = launchReporting(Res.string.email_check_failed) { mailImport.checkNow() }

    /** Stop the running check after the message in hand; the next one goes on from there. */
    fun cancelMailCheck() = mailImport.cancelCheck()

    /** Abandon a queued bulk edit and the suggestions it has bought so far. */
    fun discardEditRun(jobId: String) =
        launchReporting(Res.string.queue_edit_run_discard_failed) { editQueue.discardEditRun(jobId) }

    /** Reset a terminal text recognition failure to pending and kick its worker through the normal seam. */
    fun retry(docId: String) = launchReporting(Res.string.common_rerun_ocr_failed) { ocr.reOcr(docId) }

    /**
     * Run a cloud sync pass now. How it went shows on the sync row, which points a failure to the
     * cloud sync settings; only a pass that could not start at all is said here.
     */
    fun syncNow() = launchReporting(Res.string.queue_sync_now_failed) { cloud.syncNowNotice()?.let(::message) }
}

/**
 * Run a cloud sync pass now, and what to tell the user of it: only a pass that could not start at
 * all is said, as how a pass went shows on Activity's sync row. Activity's Sync now and the
 * palette's share it.
 */
internal suspend fun SyncFacade.syncNowNotice(): UiText? =
    when (syncNow()) {
        is SyncOutcome.Unavailable -> uiText(Res.string.queue_sync_unavailable)
        SyncOutcome.NotConfigured -> uiText(Res.string.queue_sync_not_configured)
        is SyncOutcome.Completed, is SyncOutcome.Failed, SyncOutcome.AlreadyRunning -> null
    }
