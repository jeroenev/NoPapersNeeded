package dev.npn

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.npn.core.app.LibrarySession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Periodic background maintenance: rescan the library folder (picks up externally synced files and
 * foreign OCR sidecars) and process the OCR queue. Uses the process-wide [AppEnvironment] singleton
 * from [NpnApplication] so it shares the same session/DB/scope as the UI.
 *
 * [LibrarySession.rescan] already adopts foreign sidecars, queues pending OCR and kicks the OCR
 * worker; the worker then waits (bounded) for the OCR queue and then the automatic AI enrichment
 * queue to drain, so WorkManager keeps the process alive while that background work runs.
 */
class RescanOcrWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val env = NpnApplication.environment(applicationContext)

        // Restore the persisted library if the UI hasn't opened it yet (e.g. worker runs while the
        // app process was cold-started just for this job).
        if (env.session.first() == null) {
            runCatching { env.openPersistedLibrary() }
        }
        val session = env.session.first() ?: return Result.success() // no library configured

        return try {
            // Sweep the watched folder first so freshly scanned PDFs are part of this run's
            // rescan + OCR drain (no-op when no watched folder is configured).
            runCatching { env.scanWatchedFolder() }

            session.ingest.rescan()

            // Hold the wakelock while the OCR queue drains and the documents it produced are
            // enriched, bounded so this job never exceeds WorkManager's window. The bound is on
            // how long we *wait*: on timeout the work carries on under the session until the
            // process dies, and both queues are persistent, so whatever is left is picked up by
            // the next run. That is also what happens to enrichment when a large import uses the
            // whole window on OCR alone.
            withTimeoutOrNull(DRAIN_TIMEOUT_MS) {
                session.ocr.ocrStatus.first { it.pending == 0 && !it.running }
                // Drains rather than watching a count, so it returns as soon as no further
                // progress is possible — feature off, endpoint unconfigured, or a backlog waiting
                // out a backoff — instead of holding the wakelock until the timeout.
                session.aiSuggest.drainAutoEnrich()
            }
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "rescan/OCR maintenance failed; retrying", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "npn"
        private const val DRAIN_TIMEOUT_MS = 8L * 60L * 1000L // 8 min, under WM's 10 min limit
    }
}

object NpnWork {
    private const val PERIODIC_NAME = "npn-rescan-ocr"

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
}
