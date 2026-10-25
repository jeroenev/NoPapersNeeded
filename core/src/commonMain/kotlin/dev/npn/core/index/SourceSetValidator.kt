package dev.npn.core.index

import dev.npn.core.db.MetadataProjectionDao
import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.fs.FileMeta
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths

/**
 * Decides whether a metadata source set is safe to fold. [MetadataAccumulator] does the ingestion.
 *
 * Every check reports a failure REASON rather than failing outright. Latching that reason into the
 * projection state (and so blocking writes) stays with [MetadataAccumulator], which owns the DB
 * write — this class only judges.
 *
 * A journal source this device applied that is gone from the folder, and whose events no listed file
 * holds, is a loss at the first pass that sees it. Builds that compacted the journal tolerated such a
 * loss for a few passes, because another device's deletions could arrive before the snapshot that
 * justified them. No build deletes a journal object now, and nothing else legitimately makes an
 * applied source vanish, even briefly: objects are write-once and named by their hash, so a sync tool
 * never has a newer version of one to swap in, and a file still in transfer is listed under the
 * tool's temporary name ([JournalPath.SyncTemp]) while the object itself is not there yet, which is
 * no gap where an applied object was. What is left is real damage (a file deleted by hand, or by a
 * sync tool undoing local changes), a folder put back from a backup older than this index, or an
 * earlier build on another device still compacting, whose snapshot lifts the block once it arrives.
 * Each blocks writes ([WriteBlock.JOURNAL_DAMAGED]) until a pass reads a journal that holds every
 * applied event again. Waiting would help none of them, and would only let writes go on over a
 * journal that lost events.
 *
 * Whether a listed file holds a gone source's events is read off the event tables, by event identity
 * (device and sequence); no snapshot manifest is taken at its word. That covers every way a source
 * can go without a loss alike: the objects an earlier build compacted, which its snapshot holds, or a
 * later snapshot that compacted that one in turn; a snapshot gone with the objects it replaced back
 * on disk (a folder put back from before a compaction). A snapshot that names an object without
 * holding its events explains nothing.
 */
internal class SourceSetValidator(
    private val dao: MetadataProjectionDao,
) {
    /**
     * A reason to reject the whole entry set because it holds an object store only a newer build
     * writes, or null when every store is one this build reads.
     */
    fun newerStore(entries: List<FileMeta>): String? {
        val unsupported =
            entries
                .asSequence()
                .mapNotNull { JournalPaths.objectStoreVersion(it.relPath) }
                .firstOrNull { it !in SUPPORTED_STORE_VERSIONS }
        return unsupported?.let { "Unsupported metadata object store version $it" }
    }

    /**
     * A reason to reject the whole entry set because a store this build reads holds a file that is
     * no object of it, or null when there is none.
     */
    fun invalidObjectPath(entries: List<FileMeta>): String? {
        val invalidObject =
            entries.firstOrNull { entry ->
                !entry.isDirectory && JournalPaths.objectStoreVersion(entry.relPath) in SUPPORTED_STORE_VERSIONS &&
                    JournalPaths.classify(entry.relPath) is JournalPath.Unknown
            }
        return invalidObject?.let { "Invalid metadata object path: ${it.relPath}" }
    }

    /**
     * A reason to reject the set because a source applied before the pass is gone from the folder and
     * an event it held is held by no applied source the pass listed, or null when every event of each
     * of [gone] is. [gone] are the applied sources the pass did not list, and [listed] the paths of
     * the applied sources it did.
     */
    suspend fun unheldEvents(
        gone: List<MetadataSourceEntity>,
        listed: Set<String>,
    ): String? {
        if (gone.isEmpty()) return null
        val unheld =
            dao
                .eventHolders(gone.map { it.path })
                .groupBy { Triple(it.sourcePath, it.eventDeviceId, it.eventSequence) }
                .entries
                .firstOrNull { (_, holders) -> holders.none { it.holderPath in listed } }
        return unheld?.let { "Metadata source disappeared without a validated replacement: ${it.key.first}" }
    }

    private companion object {
        val SUPPORTED_STORE_VERSIONS = setOf(JournalPaths.STORAGE_V2, JournalPaths.SNAPSHOT_V2)
    }
}
