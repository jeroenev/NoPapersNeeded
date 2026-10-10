package dev.npn.core.app

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

    /**
     * Set (or clear, when [value] is null/blank) field [key] on [docId] — title, correspondent and
     * every registered or ad-hoc field alike. The value is canonicalized for the field's registry
     * type ([dev.npn.core.model.FieldValues]); an input that does not fit the type throws
     * [IllegalArgumentException] and writes nothing. Unregistered keys are treated as TEXT.
     */
    suspend fun setField(
        docId: String,
        key: String,
        value: String?,
    )

    /** [setField] for several documents at once, one journal transaction. */
    suspend fun setField(
        docIds: List<String>,
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
     * Only keys present in [fields] are touched; a present key with a null or blank value clears
     * that field, as in [setField]. Values are canonicalized per the registry type and an off-type
     * value fails the whole edit. Blank and duplicate [addTags] are ignored. Unlike the setters,
     * the commit is all-or-nothing: nothing is written for any document if it fails.
     *
     * Every document gets the same values, so this fits carrying one set of metadata onto many
     * documents (a split's parts, a bulk edit). Per-document *values* still need a call each —
     * batching those into one transaction would make one document's rejection lose the rest.
     */
    suspend fun applyEdits(
        docIds: List<String>,
        addTags: List<String> = emptyList(),
        fields: Map<String, String?> = emptyMap(),
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
    /**
     * Move the document's own file to `.npn/trash` and record a doc-trash event. Returns whether it
     * trashed the document: false, with nothing written, for one already trashed or deleted (here or
     * by another device) or unknown, which a caller's Undo must leave alone.
     */
    suspend fun trashDoc(docId: String): Boolean

    suspend fun restoreDoc(docId: String)

    /**
     * [restoreDoc] each of [docIds], listing the trash once for all of them rather than once each.
     * One that fails does not stop the others; returns how many failed, each in the session log.
     */
    suspend fun restoreDocs(docIds: List<String>): Int

    /**
     * Delete a document in the trash for good: a doc-delete tombstone, then its bytes. A document
     * not in the trash (restored here or on another device, brought back by an import, or unknown)
     * is left alone, with nothing written.
     */
    suspend fun deleteDocPermanently(docId: String)

    /**
     * [deleteDocPermanently] each of [docIds], over one listing of the trash, as [restoreDocs] restores.
     * One no longer in the trash is left alone and is not a failure.
     */
    suspend fun deleteDocsPermanently(docIds: List<String>): Int

    suspend fun renameFile(
        docId: String,
        newFileName: String,
    )

    suspend fun moveFile(
        docId: String,
        newDirRelPath: String,
    )
}
