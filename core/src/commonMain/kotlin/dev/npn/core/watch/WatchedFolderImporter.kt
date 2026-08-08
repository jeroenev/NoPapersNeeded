package dev.npn.core.watch

import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.IngestFacade
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.SystemEpochClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What one watched-folder sweep did. */
data class WatchedFolderScan(
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
)

/**
 * Auto-imports PDFs from a [WatchedFolder] through the library's [IngestFacade]. Import goes
 * through the normal ingest path, so parts are hash-deduped and OCR is queued/kicked automatically.
 *
 * Handled files are remembered in memory by (id, size, mtime) so repeated polls do not re-stage
 * and re-hash the same bytes; a fresh importer (e.g. a background worker run) simply relies on
 * the session's hash dedup instead. Files modified less than [minQuietMillis] ago are skipped —
 * a scanner may still be writing them — and picked up by a later sweep. Failures are retried up
 * to [maxAttempts] times.
 */
class WatchedFolderImporter(
    private val ingest: IngestFacade,
    private val folder: WatchedFolder,
    private val deleteAfterImport: suspend () -> Boolean,
    private val clock: EpochClock = SystemEpochClock,
    private val minQuietMillis: Long = DEFAULT_MIN_QUIET_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    private val completed = HashSet<String>()
    private val attempts = HashMap<String, Int>()

    /** One sweep: list, import what is new and stable, optionally delete consumed sources. */
    suspend fun scanOnce(): WatchedFolderScan {
        var imported = 0
        var duplicates = 0
        var failed = 0
        val now = clock.nowMillis()
        for (file in folder.listPdfs()) {
            val key = "${file.id}|${file.size}|${file.mtimeMillis}"
            if (shouldSkip(key, file, now)) continue

            when (
                runCatching { ingest.importPdf(file.source, file.name) }
                    .getOrElse { ImportOutcome.Failed(it.message ?: "import failed") }
            ) {
                is ImportOutcome.Imported -> {
                    imported++
                    consume(file, key)
                }

                is ImportOutcome.Duplicate -> {
                    duplicates++
                    consume(file, key)
                }

                is ImportOutcome.Failed -> {
                    failed++
                    attempts[key] = (attempts[key] ?: 0) + 1
                }
            }
        }
        return WatchedFolderScan(imported, duplicates, failed)
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

    private suspend fun consume(
        file: WatchedFolderFile,
        key: String,
    ) {
        completed.add(key)
        if (deleteAfterImport()) {
            runCatching { folder.delete(file) }
        }
    }

    /**
     * Continuous mode: an initial sweep, change-triggered sweeps (where the platform emits
     * events) and a periodic poll fallback for platforms/syncs without events.
     */
    fun start(
        scope: CoroutineScope,
        pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MS,
    ): Job =
        scope.launch {
            launch {
                folder.changes().collect {
                    // Wait out the quiet window (the file is likely still being written) and
                    // collapse event bursts into one trailing sweep.
                    delay(minQuietMillis + CHANGE_COALESCE_MS)
                    runCatching { scanOnce() }
                }
            }
            while (isActive) {
                runCatching { scanOnce() }
                delay(pollIntervalMillis)
            }
        }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 60_000L
        const val DEFAULT_MIN_QUIET_MS = 2_000L
        const val DEFAULT_MAX_ATTEMPTS = 3
        private const val CHANGE_COALESCE_MS = 1_000L
    }
}
