package dev.npn

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.npn.app.di.AppEnvironment
import dev.npn.core.app.LibrarySession
import dev.npn.core.util.AndroidLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** 8 min: how long a worker waits on the queues, under WorkManager's 10 min limit. */
private const val DRAIN_TIMEOUT_MS = 8L * 60L * 1000L

/**
 * Periodic background maintenance: rescan the library folder (picks up externally synced files and
 * foreign OCR sidecars) and process the OCR queue. Uses the process-wide [AppEnvironment] singleton
 * from [NpnApplication] so it shares the same session/DB/scope as the UI. A session the worker had
 * to open (a cold start) is not rescanned again: opening it already started its initial rescan.
 *
 * [LibrarySession.rescan] already adopts foreign sidecars and queues pending OCR; the worker then
 * drains (bounded) the OCR queue, the automatic AI enrichment queue and the queued AI edit runs
 * through [dev.npn.core.app.WorkFacade], so WorkManager keeps the process alive while that
 * background work runs.
 */
class RescanOcrWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val env = NpnApplication.environment(applicationContext)
        // A session this opened starts with a rescan of its own, which the drains below wait for.
        val wasOpen = env.session.value != null
        val session = env.openedLibrary() ?: return Result.success() // no library to maintain
        return runCatchingCancellable {
            // Sweep the watched folder first so freshly scanned PDFs are part of this run's
            // rescan + OCR drain (no-op when no watched folder is configured).
            AndroidLog.runLogged("Watched-folder sweep") { env.scanWatchedFolder() }

            if (wasOpen) session.ingest.rescan()

            // Drain OCR, then enrichment of the documents it produced, then queued edit runs.
            // The timeout bounds only how long we *wait*: on timeout the work carries on under
            // the session until the process dies, and all queues are persistent, so whatever is
            // left is picked up by the next run. Each drain returns as soon as no further progress
            // is possible (queue empty, feature off, endpoint unconfigured, or a backoff pending)
            // rather than holding the wakelock to the timeout. Edit runs drain last: the user
            // reviews them, so nothing downstream waits and a finished run is just as useful later.
            withTimeoutOrNull(DRAIN_TIMEOUT_MS) {
                session.work.drainOcr()
                session.work.drainEnrichment()
                session.work.drainEditRuns()
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                AndroidLog.warn("Background rescan and OCR failed; retrying", it)
                Result.retry()
            },
        )
    }
}

/**
 * Finishes the OCR and automatic AI enrichment a foreground import (a share, an open with) queued,
 * once that import has already told the user how it went. The work runs on the session either way;
 * this keeps the process alive for it after the user leaves the app.
 */
class QueueDrainWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val session = NpnApplication.environment(applicationContext).openedLibrary() ?: return Result.success()
        // Whatever is left when this gives up stays queued for the next drain or maintenance run.
        AndroidLog.runLogged("Draining the queues after an import") {
            withTimeoutOrNull(DRAIN_TIMEOUT_MS) {
                session.work.drainOcr()
                session.work.drainEnrichment()
            }
        }
        return Result.success()
    }
}

/**
 * The open library, restoring the saved one when the process was started just for this work; null
 * when none is set up or it does not open, which is logged.
 */
private suspend fun AppEnvironment.openedLibrary(): LibrarySession? {
    session.value?.let { return it }
    AndroidLog.runLogged("Opening the library for background work") { openPersistedLibrary() }
    return session.value
}

object NpnWork {
    private const val PERIODIC_NAME = "npn-rescan-ocr"
    private const val DRAIN_NAME = "npn-drain-queues"

    /**
     * Schedule the unique periodic rescan+OCR job (idempotent). UPDATE (not KEEP) so an interval
     * change in an app update replaces the previously enqueued spec instead of keeping the old
     * period forever; WorkManager preserves the existing enqueued state and next-run timing.
     */
    fun schedulePeriodic(context: Context) {
        val constraints =
            Constraints
                .Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()

        val request =
            PeriodicWorkRequestBuilder<RescanOcrWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * Drain the OCR and enrichment queues once, now, under WorkManager ([QueueDrainWorker]). A newer
     * request replaces a pending or running one: the drains themselves run on the session, so only
     * the wait restarts, and it then covers what the newer import queued too.
     */
    fun drainQueues(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            DRAIN_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<QueueDrainWorker>().build(),
        )
    }
}
