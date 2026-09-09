package dev.npn.core.app

import dev.npn.core.model.DocProperty

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

    /**
     * Write several edits to [docIds] as a single change: one journal transaction for all of them,
     * then one auto-match pass and one storage-path evaluation per document.
     *
     * Prefer this to a sequence of setters whenever the values are known together. Each setter
     * re-runs the storage path rules on its own, so a path template keyed on correspondent and
     * date renames the file once per setter — the intermediate names are meaningless, and on a
     * synced library folder every device sees each of them.
     *
     * Only keys present in [properties] are touched; a present key with a null or blank value
     * clears that property, as in the individual setters. Blank and duplicate [addTags] are
     * ignored. Unlike the setters, the commit is all-or-nothing: nothing is written for any
     * document if it fails.
     *
     * Every document gets the same values, so this fits carrying one set of metadata onto many
     * documents (a split's parts, a bulk edit). Per-document *values* still need a call each —
     * batching those into one transaction would make one document's rejection lose the rest.
     */
    suspend fun applyEdits(
        docIds: List<String>,
        addTags: List<String> = emptyList(),
        properties: Map<DocProperty, String?> = emptyMap(),
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
