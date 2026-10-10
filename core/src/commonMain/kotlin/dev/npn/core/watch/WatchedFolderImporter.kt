package dev.npn.core.watch

import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.ImportTally
import dev.npn.core.app.IngestFacade
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Auto-imports PDFs from a [WatchedFolder] through the library's [IngestFacade]. Import goes
 * through the normal ingest path, so parts are hash-deduped and OCR is queued/kicked automatically.
 *
 * Handled files are remembered in memory by (id, size, mtime) so repeated polls do not re-stage
 * and re-hash the same bytes; a fresh importer (e.g. a background worker run) simply relies on
 * the session's hash dedup instead. With delete-after-import on, a file listed with no mtime (a
 * provider may report 0) is not remembered, as its size alone would also cover a file written under
 * its name since: one kept because it no longer held the imported bytes is imported at the next
 * sweep. Files modified less than [minQuietMillis] ago are skipped — a scanner may still be writing
 * them — and picked up by a later sweep. Failures are retried up to [maxAttempts] times; a
 * cancelled import is not a failure and costs no attempt.
 *
 * Sweeps never overlap: every one runs under [sweepLock], which also guards the memory above. A
 * host running several importers over one folder (the continuous one plus an on-demand sweep)
 * passes them all the same lock, so the same file is never imported twice at once.
 */
class WatchedFolderImporter(
    private val ingest: IngestFacade,
    private val folder: WatchedFolder,
    private val deleteAfterImport: suspend () -> Boolean,
    private val log: NpnLog = NpnLog.None,
    private val sweepLock: Mutex = Mutex(),
    private val clock: EpochClock = SystemEpochClock,
    private val minQuietMillis: Long = DEFAULT_MIN_QUIET_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    private val completed = HashSet<String>()
    private val attempts = HashMap<String, Int>()

    /** One sweep: list, import what is new and stable, optionally delete consumed sources. */
    suspend fun scanOnce(): ImportTally = sweepLock.withLock { sweep() }

    private suspend fun sweep(): ImportTally {
        var tally = ImportTally()
        val now = clock.nowMillis()
        for (file in folder.listPdfs()) {
            val key = "${file.id}|${file.size}|${file.mtimeMillis}"
            if (shouldSkip(key, file, now)) continue

            // Only a closed session throws; that counts as a failed attempt like any other.
            val outcome =
                runCatchingCancellable { ingest.importPdf(file.source, file.name) }
                    .onFailure { log.warn("Watched folder import of ${file.name} failed", it) }
                    .getOrNull()
            tally += outcome
            when (outcome) {
                is ImportOutcome.Imported -> {
                    consume(file, key, outcome.docId)
                }

                is ImportOutcome.Duplicate -> {
                    consume(file, key, outcome.existingDocId)
                }

                is ImportOutcome.Failed, null -> {
                    attempts[key] = (attempts[key] ?: 0) + 1
                    if (outcome != null) log.warn("Watched folder import of ${file.name} failed: ${outcome.reason}")
                }
            }
        }
        return tally
    }

    /** Already handled, retried out, or possibly still being written (empty / freshly modified). */
    private fun shouldSkip(
        key: String,
        file: WatchedFolderFile,
        now: Long,
    ): Boolean =
        key in completed ||
            (attempts[key] ?: 0) >= maxAttempts ||
            file.size <= 0L ||
            now - file.mtimeMillis < minQuietMillis

    /**
     * Remember [file] as handled, and delete it when asked to while it holds [docId]'s bytes. A file
     * listed with no mtime that was to be deleted is not remembered: its key would also cover a file
     * of the same size written under its name since, which the delete kept for the next sweep.
     */
    private suspend fun consume(
        file: WatchedFolderFile,
        key: String,
        docId: String,
    ) {
        val delete = deleteAfterImport()
        if (file.mtimeMillis != 0L || !delete) completed.add(key)
        if (delete) {
            log.runLogged("Deleting ${file.name} from the watched folder after import") { folder.delete(file, docId) }
        }
    }

    /**
     * Continuous mode: one loop that sweeps on a periodic poll (the first at once) and on the
     * platform's change events, for platforms and syncs that have them. A change waits out the
     * quiet window first, since the file is likely still being written. Requests that arrive while
     * a sweep (or its wait) runs are merged into one more sweep, due when the latest-due of them
     * asked: a burst of events costs at most one extra pass rather than one each, and a poll never
     * cuts a change's wait short, which would sweep before its file had gone quiet and skip it.
     *
     * [timeSource] measures the waits; a test passes its scheduler's to run them in virtual time.
     */
    fun start(
        scope: CoroutineScope,
        pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MS,
        timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    ): Job =
        scope.launch {
            // When the next sweep is due, the latest any request since the last one asked for; null
            // while none is asked for.
            val due = MutableStateFlow<ComparableTimeMark?>(null)

            fun request(waitMillis: Long) {
                val at = timeSource.markNow() + waitMillis.milliseconds
                due.update { pending -> if (pending != null && pending > at) pending else at }
            }
            launch { folder.changes().collect { request(minQuietMillis + CHANGE_SETTLE_MS) } }
            launch {
                while (true) {
                    request(0L)
                    delay(pollIntervalMillis)
                }
            }
            while (true) {
                val at = due.filterNotNull().first()
                // Taken whole: a request from here on is for the sweep after this one.
                if (!due.compareAndSet(at, null)) continue
                delay(-at.elapsedNow())
                log.runLogged("Watched folder sweep") { scanOnce() }
            }
        }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 60_000L
        const val DEFAULT_MIN_QUIET_MS = 2_000L
        const val DEFAULT_MAX_ATTEMPTS = 3
        private const val CHANGE_SETTLE_MS = 1_000L
    }
}
