package dev.npn.core.db

/**
 * A document row for a test to insert: what the test names, and filler a fold would have written
 * for the rest. The file name follows [relPath], which follows [docId].
 */
fun documentEntity(
    docId: String,
    relPath: String = "$docId.pdf",
    fileName: String = relPath.substringAfterLast('/'),
    size: Long = 100,
    mtime: Long = 1000,
    addedAt: Long = 1000,
    pageCount: Int? = null,
    reviewed: Boolean = false,
    title: String? = null,
    correspondent: String? = null,
    docType: String? = null,
    docDate: String? = null,
    notes: String? = null,
    trashed: Boolean = false,
    deleted: Boolean = false,
    missing: Boolean = false,
    ocrEngine: String? = null,
    ocrEngineOverride: String? = null,
): DocumentEntity =
    DocumentEntity(
        docId = docId,
        relPath = relPath,
        fileName = fileName,
        size = size,
        mtime = mtime,
        pageCount = pageCount,
        addedAt = addedAt,
        reviewed = reviewed,
        correspondent = correspondent,
        docType = docType,
        docDate = docDate,
        title = title,
        notes = notes,
        trashed = trashed,
        deleted = deleted,
        missing = missing,
        ocrEngine = ocrEngine,
        ocrEngineOverride = ocrEngineOverride,
    )
