package dev.npn.core.index

import dev.npn.core.db.MetadataProjectionDao
import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.db.SourceKind
import dev.npn.core.db.SourceStatus
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.NpnPaths

/**
 * Decides whether a metadata source set is safe to fold, and owns the grace-window bookkeeping a
 * deletion needs when it arrives before the snapshot that justifies it. Split from
 * [MetadataAccumulator] to keep that class focused on ingestion; the compaction side lives in
 * [SnapshotSupport] for the same reason.
 *
 * Every check reports a failure REASON rather than failing outright. Latching that reason into the
 * projection state (and so blocking writes) stays with [MetadataAccumulator], which owns the DB
 * write — this class only judges.
 */
class SourceSetValidator(
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

    /** A reason to reject the whole entry set on store-version grounds, or null when it is sound. */
    fun unsupportedStorage(entries: List<FileMeta>): String? {
        val supportedStoreVersions = setOf(NpnPaths.STORAGE_V2, NpnPaths.SNAPSHOT_V2)
        val unsupported =
            entries
                .asSequence()
                .mapNotNull { NpnPaths.objectStoreVersion(it.relPath) }
                .firstOrNull { it !in supportedStoreVersions }
        if (unsupported != null) return "Unsupported metadata object store version $unsupported"
        val invalidObject =
            entries.firstOrNull { entry ->
                !entry.isDirectory && NpnPaths.objectStoreVersion(entry.relPath) in supportedStoreVersions &&
                    NpnPaths.journalPath(entry.relPath) is NpnPaths.JournalPath.Unknown
            }
        return invalidObject?.let { "Invalid metadata object path: ${it.relPath}" }
    }

    /**
     * A reason to reject the set because a previously-applied source vanished without a validated
     * replacement, or null when every loss is explained (or still inside its grace window).
     */
    suspend fun unexplainedSourceLoss(
        previous: List<MetadataSourceEntity>,
        files: Map<String, FileMeta>,
    ): String? {
        val current = dao.allSources()
        val supersededHashes = dao.appliedSupersededHashes().toHashSet()
        val stillMissing = HashSet<String>()
        val hardMissing =
            previous.firstOrNull { source ->
                if (source.path in files) return@firstOrNull false
                when (source.kind) {
                    SourceKind.OBJECT, SourceKind.SNAPSHOT -> {
                        isUnexplainedObjectLoss(source, current, files, supersededHashes, stillMissing)
                    }

                    SourceKind.LEGACY_SEGMENT -> {
                        !hasLegacyReplacement(source, current, files)
                    }
                }
            }
        // Drop pending counters for paths that reappeared or got explained this pass.
        pendingSupersession.keys.retainAll(stillMissing)
        return hardMissing?.let { "Metadata source disappeared without a validated replacement: ${it.path}" }
    }

    /**
     * True when a missing content-addressed source is UNEXPLAINED and its grace window is spent.
     *
     * A loss is explained (returns false) when either a hash-identical replacement path is present,
     * or an applied snapshot lists the source's content hash as superseded. When neither holds yet
     * — a deletion that raced ahead of its snapshot — the source becomes "pending supersession":
     * its events are still applied locally, so we tolerate it for [PENDING_SUPERSESSION_LIMIT]
     * reconciles and only then treat it as real damage.
     */
    private fun isUnexplainedObjectLoss(
        source: MetadataSourceEntity,
        current: List<MetadataSourceEntity>,
        files: Map<String, FileMeta>,
        supersededHashes: Set<String>,
        stillMissing: MutableSet<String>,
    ): Boolean {
        val hasReplacement =
            current.any { candidate ->
                candidate.path in files && candidate.status == SourceStatus.APPLIED &&
                    candidate.contentHash == source.contentHash
            }
        if (hasReplacement) return false
        if (source.contentHash != null && source.contentHash in supersededHashes) return false
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
        /**
         * How many consecutive reconciles a deleted-but-still-applied content-addressed source may
         * stay unexplained (no hash replacement, no applied snapshot superseding it) before it is
         * treated as real damage and hard-blocks writes. The 15-minute reconcile cadence makes this
         * roughly a two-hour grace window for a snapshot to arrive after its deletions.
         */
        const val PENDING_SUPERSESSION_LIMIT = 8
    }
}
