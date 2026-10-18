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
 * Decides whether a metadata source set is safe to fold, and owns the grace-window bookkeeping a
 * deletion needs when it arrives before the snapshot that justifies it. [MetadataAccumulator] does
 * the ingestion, and [SnapshotSupport] the compaction side.
 *
 * Every check reports a failure REASON rather than failing outright. Latching that reason into the
 * projection state (and so blocking writes) stays with [MetadataAccumulator], which owns the DB
 * write — this class only judges.
 */
internal class SourceSetValidator(
    private val dao: MetadataProjectionDao,
) {
    /**
     * Pending-supersession bookkeeping: a previously-applied source whose file disappeared and
     * whose content hash is NOT yet covered by an applied snapshot. Its events are still in the DB
     * (they were applied), so a deletion arriving before its justifying snapshot must NOT hard-block
     * writes. We count how many consecutive reconciles each such path stays unexplained and only
     * escalate to the hard block past [PENDING_SUPERSESSION_LIMIT] (a deletion that never gets a
     * snapshot is real damage). The counter is device-local and rebuildable; a fresh session
     * legitimately restarts it.
     */
    private val pendingSupersession = HashMap<String, Int>()

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
     * replacement, or null when every loss is explained (or still inside its grace window). [files]
     * is what the pass listed and read, [previous] the source table before it and [current] after
     * it, and [carried] the content hashes whose events [files] hold ([SnapshotSupport.carriedByListed]).
     */
    suspend fun unexplainedSourceLoss(
        previous: List<MetadataSourceEntity>,
        current: List<MetadataSourceEntity>,
        files: Map<String, FileMeta>,
        carried: Set<String>,
    ): String? {
        val stillMissing = HashSet<String>()
        // Every missing source is judged, not only those before the first hard one: judging one
        // counts its grace window, and a source left unjudged would lose its count below.
        val hardLosses =
            previous
                .filter { source ->
                    if (source.path in files) return@filter false
                    when (source.kind) {
                        SourceKind.OBJECT, SourceKind.SNAPSHOT -> {
                            isUnexplainedObjectLoss(source, carried, stillMissing)
                        }

                        SourceKind.LEGACY_SEGMENT -> {
                            !hasLegacyReplacement(source, current, files)
                        }
                    }
                }
        // Drop pending counters for paths that reappeared or got explained this pass.
        pendingSupersession.keys.retainAll(stillMissing)
        return hardLosses.firstOrNull()?.let {
            "Metadata source disappeared without a validated replacement: ${it.path}"
        }
    }

    /**
     * True when a missing content-addressed source is UNEXPLAINED and its grace window is spent.
     *
     * A loss is explained (returns false) when the listed files hold the source's events
     * ([carried]): a hash-identical replacement is listed, an applied snapshot on disk carries them,
     * or, for a snapshot, the sources it superseded are back. A snapshot the index remembers but the
     * folder no longer holds explains nothing. When none holds yet — a deletion that raced ahead of
     * its snapshot — the source becomes "pending supersession": its events are still applied
     * locally, so we tolerate it for [PENDING_SUPERSESSION_LIMIT] reconciles and only then treat it
     * as real damage.
     */
    private fun isUnexplainedObjectLoss(
        source: MetadataSourceEntity,
        carried: Set<String>,
        stillMissing: MutableSet<String>,
    ): Boolean {
        if (source.contentHash in carried) return false
        // Pending supersession: not yet explained. Withhold the hard block for a grace window.
        stillMissing.add(source.path)
        val count = (pendingSupersession[source.path] ?: 0) + 1
        pendingSupersession[source.path] = count
        return count > PENDING_SUPERSESSION_LIMIT
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

    companion object {
        private val SUPPORTED_STORE_VERSIONS = setOf(JournalPaths.STORAGE_V2, JournalPaths.SNAPSHOT_V2)

        /**
         * How many consecutive reconciles a deleted-but-still-applied content-addressed source may
         * stay unexplained (no hash replacement, no applied snapshot superseding it) before it is
         * treated as real damage and hard-blocks writes. The 15-minute reconcile cadence makes this
         * roughly a two-hour grace window for a snapshot to arrive after its deletions.
         */
        const val PENDING_SUPERSESSION_LIMIT = 8
    }
}
