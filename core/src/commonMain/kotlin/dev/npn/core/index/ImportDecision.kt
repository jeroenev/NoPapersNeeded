package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.NpnPaths
import dev.npn.core.ocr.OcrState

/** The row an import writes for the document it placed, and the follow-ups that row asks for. */
internal class ImportedRow(
    val row: DocumentEntity,
    /** Whether the document goes into the OCR queue: its text is not indexed yet. */
    val queueOcr: Boolean,
    /** Whether search must learn the row's file name, which bringing a document back changed. */
    val reindexName: Boolean,
)

/**
 * The row an import writes, as a pure function of what [ImportService] gathered under the writer
 * lock; the service decides the rest inline (a duplicate, superseded bytes, where the file goes).
 */
internal object ImportDecision {
    /** The OCR states of a document whose text is indexed. */
    private val INDEXED = setOf(OcrState.DONE, OcrState.ADOPTED)

    /**
     * The row of the document of [hash] whose file an import put at [target], [stat] being that
     * file's: [existing] brought back as live there, or a new document. A document whose
     * text is not indexed yet is queued for OCR.
     */
    fun row(
        hash: String,
        target: String,
        existing: DocumentEntity?,
        stat: FileMeta,
    ): ImportedRow {
        val row = existing?.backAt(target, stat) ?: newDocument(hash, target, stat)
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
    ) = DocumentEntity(
        docId = hash,
        relPath = target,
        fileName = NpnPaths.fileName(target),
        size = stat.size,
        mtime = stat.mtimeMillis,
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
