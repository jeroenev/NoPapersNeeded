package dev.npn.core.app

// ---------- write side (journal-backed) ----------

/**
 * Per-document (and bulk) metadata edits. Everything here is a journal-backed write to one or
 * more documents' own metadata; vocabulary-level tag operations live on [TagAdmin] and a
 * document's existence/location on [DocLifecycle].
 */
interface MetadataWriter {
    suspend fun addTag(
        docIds: List<String>,
        tag: String,
    )

    suspend fun removeTag(
        docIds: List<String>,
        tag: String,
    )

    suspend fun setReviewed(
        docIds: List<String>,
        reviewed: Boolean,
    )

    suspend fun setCorrespondent(
        docIds: List<String>,
        value: String?,
    )

    suspend fun setDocType(
        docIds: List<String>,
        value: String?,
    )

    suspend fun setDocDate(
        docId: String,
        isoDate: String?,
    )

    suspend fun setTitle(
        docId: String,
        title: String?,
    )

    /** Set a free-text note; null or blank clears it. */
    suspend fun setNotes(
        docId: String,
        notes: String?,
    )

    /** Set (or clear, when [value] is null/blank) a custom metadata field [key] on [docId]. */
    suspend fun setField(
        docId: String,
        key: String,
        value: String?,
    )
}

/** Vocabulary-level tag administration, affecting every document holding a tag. */
interface TagAdmin {
    suspend fun renameTag(
        from: String,
        to: String,
    )

    suspend fun mergeTag(
        from: String,
        to: String,
    )

    suspend fun deleteTag(name: String) // removes tag from all docs

    suspend fun setTagStyle(
        tag: String,
        color: String?,
        icon: String?,
    )
}

/** A document's existence and location: trash lifecycle plus file rename/move. */
interface DocLifecycle {
    suspend fun trashDoc(docId: String) // moves file to .npn/trash + doc-trash event

    suspend fun restoreDoc(docId: String)

    suspend fun deleteDocPermanently(docId: String) // deletes file + doc-delete tombstone

    suspend fun renameFile(
        docId: String,
        newFileName: String,
    )

    suspend fun moveFile(
        docId: String,
        newDirRelPath: String,
    )
}
