package dev.npn.core.app

import dev.npn.core.db.NpnDatabase
import dev.npn.core.index.ImportResult
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.index.MaintenanceService
import dev.npn.core.index.ScanReport
import dev.npn.core.index.SplitFailure
import dev.npn.core.index.SplitService
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldKeys
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [MaintenanceFacade]: duplicates, relinks and splitting, each run as work the session owns. A
 * relink rescans, so its report lands on [scanReports], the session's last scan report.
 */
internal class MaintenanceFacadeImpl(
    private val lifetime: SessionLifetime,
    private val db: NpnDatabase,
    private val maintenance: MaintenanceService,
    private val coordinator: JournalCoordinator,
    private val splitter: SplitService,
    private val writer: MetadataWriterImpl,
    private val scanReports: MutableStateFlow<ScanReport?>,
    private val log: NpnLog,
) : MaintenanceFacade {
    override suspend fun resolveDuplicate(
        docId: String,
        extraRelPath: String,
    ) = lifetime.owned { maintenance.resolveDuplicate(docId, extraRelPath) }

    override suspend fun applyRelink(
        oldDocId: String,
        newDocId: String,
    ) = lifetime.owned {
        // The relink's fold carries the old document's metadata onto the new id and marks the old
        // one superseded, which only a rescan projects; commitAndRescan runs both under the lock.
        scanReports.value = coordinator.commitAndRescan(listOf(PendingOp.relink(oldDocId, newDocId)))
    }

    override suspend fun splitPdf(
        docId: String,
        segments: List<IntRange>,
    ): SplitOutcome = lifetime.owned { split(docId, segments) }

    private suspend fun split(
        docId: String,
        segments: List<IntRange>,
    ): SplitOutcome {
        val parts =
            when (val result = splitter.split(docId, segments)) {
                is SplitService.Result.Failed -> return SplitOutcome.Failed(result.reason)
                is SplitService.Result.Done -> result.parts
            }
        val partIds =
            parts.map {
                when (it) {
                    is ImportResult.Imported -> it.docId
                    is ImportResult.Duplicate -> it.existingDocId
                }
            }
        // Only the parts the split made get the original's metadata. A part whose bytes were a
        // document already (a duplicate, or one the import brought back) keeps its own.
        val made = parts.filterIsInstance<ImportResult.Imported>().filterNot { it.broughtBack }.map { it.docId }
        var finished = false
        try {
            // Trash last, so a failure anywhere leaves the original untouched; the parts the split
            // made are then taken back, from wherever the storage-path rules moved them. Metadata
            // already copied onto them, and the paths the rules chose, stay in the journal under
            // their hashes, unused: only a file with those bytes makes a row, and a retry makes
            // other bytes (the PDF writer stamps each file's /ID with the time).
            runCatchingCancellable {
                copyMetadataToParts(docId, made)
                // Null for an original trashed or deleted meanwhile: it is out of the library, as
                // the split wants it, so the split is done.
                writer.trashDoc(docId)
            }.getOrElse {
                log.warn("Finishing the split of $docId failed; taking its parts back", it)
                return SplitOutcome.Failed(SplitFailure.NOT_COMPLETED)
            }
            finished = true
        } finally {
            if (!finished) splitter.rollBack(parts)
        }
        return SplitOutcome.Done(partIds)
    }

    /**
     * Carry tags and single-value properties of the split source onto the parts it made, [partIds],
     * as the source holds them: a value that no longer fits its field's type is carried as it is
     * ([MetadataWriterImpl.copyAsStored]), not a reason to fail the split.
     *
     * One edit ([MetadataWriter.applyEdits]) for every part rather than a setter per tag and per property:
     * the setters each re-run the storage path rules, so a source with several tags would rename
     * every part once per tag before it settled. Parts are brand-new files, so those intermediate
     * names are churn no replica needs to see.
     *
     * All-or-nothing across the parts is what this path wants: the parts were created together
     * from one source, so a half-copied set is worse than none, and the caller's retry is a rerun
     * of the same split.
     */
    private suspend fun copyMetadataToParts(
        docId: String,
        partIds: List<String>,
    ) {
        db.documentDao().getByDocId(docId) ?: return
        val tags = db.tagDao().tagsForDocument(docId)
        // Every field carries onto the parts except the ones that describe the whole document
        // (title, notes): a part is not "the" invoice, and its notes would be misleading.
        val fields = db.documentFieldDao().valuesOf(docId).filterKeys { it !in FieldKeys.NOT_INHERITED_ON_SPLIT }
        writer.copyAsStored(partIds, addTags = tags, fields = fields)
    }
}
