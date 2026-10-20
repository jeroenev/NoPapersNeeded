package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.isLive
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.NpnPaths
import dev.npn.core.ocr.OcrState

// What an import decides about the bytes it staged, as pure functions of what it gathered under the
// writer lock. [ImportService] gathers the facts (the row of the bytes' hash, whether that row's
// file holds them, what the journal made of them) and carries out what these return.

/** Where an import's bytes go, once they are no duplicate of a document the library shows. */
internal sealed interface ImportPlan {
    /**
     * Bytes a relink superseded (a bake's original): an earlier version of [successor], which no
     * journal event makes live again. A duplicate of [successor] while the library shows it, and
     * refused otherwise ([ImportDecision.supersededDuplicate]); nothing is placed either way.
     */
    data class Superseded(
        val successor: String,
    ) : ImportPlan

    /**
     * Place the bytes as a document: [existing], which the library has but does not show (trashed,
     * deleted, missing, or live with its file gone), brought back, metadata and all, or a new one
     * when null. [inPlace] is the document's own path when its bytes are back there already, where
     * the import leaves them; else the import moves them to a target of its own.
     */
    data class Place(
        val existing: DocumentEntity?,
        val inPlace: String?,
    ) : ImportPlan
}

/** The row an import writes for the document it placed, and the follow-ups that row asks for. */
internal class ImportedRow(
    val row: DocumentEntity,
    /** Whether the document goes into the OCR queue: its text is not indexed yet. */
    val queueOcr: Boolean,
    /** Whether search must learn the row's file name, which bringing a document back changed. */
    val reindexName: Boolean,
)

internal object ImportDecision {
    /** The OCR states of a document whose text is indexed. */
    private val INDEXED = setOf(OcrState.DONE, OcrState.ADOPTED)

    /**
     * The duplicate an import is when [existing], the row of the bytes' hash, is a document the
     * library shows and its file holds the bytes ([inPlace] is its path then, null when it does
     * not): nothing is added. Null when the import goes on to [place].
     */
    fun duplicate(
        existing: DocumentEntity?,
        inPlace: String?,
    ): ImportResult.Duplicate? =
        if (existing != null && existing.isLive && inPlace != null) {
            ImportResult.Duplicate(existing.docId, existing.relPath)
        } else {
            null
        }

    /**
     * Where bytes of [hash] that are no [duplicate] go: nowhere when a relink superseded them
     * ([successor] is the document they became, asked of the journal, not the index: a superseded
     * row is deleted like any other, and an index rebuilt since has none), else into [existing]'s
     * return or a new document.
     */
    fun place(
        hash: String,
        existing: DocumentEntity?,
        inPlace: String?,
        successor: String,
    ): ImportPlan = if (successor != hash) ImportPlan.Superseded(successor) else ImportPlan.Place(existing, inPlace)

    /**
     * The duplicate an import of superseded bytes is: of their successor's row [shown] while it is
     * live and [holds] its bytes; null when the import is refused.
     */
    fun supersededDuplicate(
        shown: DocumentEntity?,
        holds: Boolean,
    ): ImportResult.Duplicate? =
        shown?.takeIf { it.isLive && holds }?.let { ImportResult.Duplicate(it.docId, it.relPath) }

    /**
     * The row of the document of [hash] whose file an import put at [target], [stat] being that
     * file's: [existing] brought back as live there, or a new document added [now]. A document whose
     * text is not indexed yet is queued for OCR.
     */
    fun row(
        hash: String,
        target: String,
        existing: DocumentEntity?,
        stat: FileMeta,
        now: Long,
    ): ImportedRow {
        val row = existing?.backAt(target, stat) ?: newDocument(hash, target, stat, now)
        // A returning document whose text is indexed already has nothing for OCR to redo.
        val queueOcr = OcrState.fromColumn(row.ocrState) !in INDEXED
        return ImportedRow(
            row = if (queueOcr) row.copy(ocrState = OcrState.PENDING.column) else row,
            queueOcr = queueOcr,
            reindexName = existing != null && existing.fileName != row.fileName,
        )
    }

    private fun newDocument(
        hash: String,
        target: String,
        stat: FileMeta,
        now: Long,
    ) = DocumentEntity(
        docId = hash,
        relPath = target,
        fileName = NpnPaths.fileName(target),
        size = stat.size,
        mtime = stat.mtimeMillis,
        addedAt = now,
        ocrState = OcrState.PENDING.column,
    )

    /** This row, of a document the library did not show, as the live document whose file is [target]. */
    private fun DocumentEntity.backAt(
        target: String,
        stat: FileMeta,
    ) = copy(
        relPath = target,
        fileName = NpnPaths.fileName(target),
        size = stat.size,
        mtime = stat.mtimeMillis,
        trashed = false,
        trashedOrigPath = null,
        trashedAt = null,
        deleted = false,
        missing = false,
    )
}
