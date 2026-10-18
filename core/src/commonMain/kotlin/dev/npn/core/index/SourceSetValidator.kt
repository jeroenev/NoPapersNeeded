package dev.npn.core.index

import dev.npn.core.db.MetadataProjectionDao
import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.db.SourceKind
import dev.npn.core.db.SourceStatus
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths

/**
 * Decides whether a metadata source set is safe to fold. [MetadataAccumulator] does the ingestion,
 * and [SnapshotSupport] says what the snapshots earlier builds wrote account for.
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
     * A reason to reject the set because a previously-applied source vanished without a validated
     * replacement, or null when every loss is explained. [files] is what the pass listed and read,
     * [previous] the source table before it and [current] after it, and [carried] the content hashes
     * whose events [files] hold ([SnapshotSupport.carriedByListed]).
     *
     * A content-addressed source's loss is explained when [carried] holds its hash: a hash-identical
     * replacement is listed, an applied snapshot on disk carries it, or, for a snapshot, the sources
     * it superseded are back. A snapshot the index remembers but the folder no longer holds explains
     * nothing.
     */
    suspend fun unexplainedSourceLoss(
        previous: List<MetadataSourceEntity>,
        current: List<MetadataSourceEntity>,
        files: Map<String, FileMeta>,
        carried: Set<String>,
    ): String? {
        val lost =
            previous.firstOrNull { source ->
                // A source that never read as valid recorded nothing, so its loss loses nothing.
                source.status == SourceStatus.APPLIED && source.path !in files &&
                    when (source.kind) {
                        SourceKind.OBJECT, SourceKind.SNAPSHOT -> source.contentHash !in carried
                        SourceKind.LEGACY_SEGMENT -> !hasLegacyReplacement(source, current, files)
                    }
            }
        return lost?.let { "Metadata source disappeared without a validated replacement: ${it.path}" }
    }

    private suspend fun hasLegacyReplacement(
        missing: MetadataSourceEntity,
        current: List<MetadataSourceEntity>,
        files: Map<String, FileMeta>,
    ): Boolean {
        val deviceDir = NpnPaths.parentDir(missing.path)
        val required = dao.sourceEvents(missing.path).map { it.eventDeviceId to it.eventSequence }.toSet()
        val replacements =
            current.filter { source ->
                source.path in files && NpnPaths.parentDir(source.path) == deviceDir &&
                    source.status == SourceStatus.APPLIED
            }
        val covered =
            replacements
                .flatMap { dao.sourceEvents(it.path) }
                .mapTo(HashSet()) { it.eventDeviceId to it.eventSequence }
        return required.isNotEmpty() && covered.containsAll(required)
    }

    private companion object {
        val SUPPORTED_STORE_VERSIONS = setOf(JournalPaths.STORAGE_V2, JournalPaths.SNAPSHOT_V2)
    }
}
