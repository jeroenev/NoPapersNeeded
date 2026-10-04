package dev.npn.core.app

import dev.npn.core.db.NpnDatabase
import dev.npn.core.index.ImportResult
import dev.npn.core.index.MaintenanceService
import dev.npn.core.index.RelinkService
import dev.npn.core.index.ScanReport
import dev.npn.core.index.SplitService
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
    private val relink: RelinkService,
    private val splitter: SplitService,
    private val writer: MetadataWriterImpl,
    private val ocr: OcrFacadeImpl,
    private val scanReports: MutableStateFlow<ScanReport?>,
    private val log: NpnLog,
) : MaintenanceFacade {
    override suspend fun resolveDuplicate(
        docId: String,
        extraRelPath: String,
        keepCanonical: Boolean,
    ) = lifetime.owned { maintenance.resolveDuplicate(docId, extraRelPath, keepCanonical) }

    override suspend fun applyRelink(
        oldDocId: String,
        newDocId: String,
    ) = lifetime.owned {
        // commitAndRescan already refreshes the coordinator's folded state under the lock.
        scanReports.value = relink.applyRelink(oldDocId, newDocId)
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
        var finished = false
        try {
            // Trash last, so a failure anywhere leaves the original untouched; the parts are then
            // taken back. Metadata already copied onto them stays in the journal under their
            // hashes, harmless: a retry makes the same parts and copies the same metadata.
            runCatchingCancellable {
                copyMetadataToParts(docId, partIds)
                writer.trashDoc(docId)
            }.getOrElse {
                log.warn("Finishing the split of $docId failed; taking its parts back", it)
                return SplitOutcome.Failed(SplitFailure.NOT_COMPLETED)
            }
            finished = true
        } finally {
            if (!finished) splitter.rollBack(parts)
        }
        ocr.kick()
        return SplitOutcome.Done(partIds)
    }

    /**
     * Carry tags and single-value properties of the split source onto its parts.
     *
     * One [MetadataWriter.applyEdits] for every part rather than a setter per tag and per property:
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
        val tags =
            db
                .documentDao()
                .tagsForDocs(listOf(docId))
                .map { it.tag }
                .distinct()
        // Every field carries onto the parts except the ones that describe the whole document
        // (title, notes): a part is not "the" invoice, and its notes would be misleading.
        val fields =
            db
                .documentFieldDao()
                .fieldsForDocs(listOf(docId))
                .filter { it.fieldKey !in FieldKeys.NOT_INHERITED_ON_SPLIT }
                .associate<_, String, String?> { it.fieldKey to it.fieldValue }
        writer.applyEdits(partIds, addTags = tags, fields = fields)
    }
}
