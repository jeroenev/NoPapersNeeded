package dev.npn.core.app

/**
 * Per-document (and bulk) metadata edits. Everything here is a journal-backed write to one or
 * more documents' own metadata; vocabulary-level tag operations live on [TagAdmin] and a
 * document's existence/location on [DocLifecycle].
 *
 * A write that returns normally has landed. One that throws was not published, unless projecting it
 * onto the index failed after it was. What a write sets off afterwards (the storage-path rule, the
 * auto-match rules) never fails it: such a failure is in the session log.
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
     *
     * With [fillOnly], each document gets only what it lacks when the edit commits, judged under the
     * journal's lock: a field it has a value for and a tag it carries (in any case) are left out, so
     * a value entered while the caller worked out its edit is never overwritten. A document left with
     * nothing to write gets no follow-ups either.
     */
    suspend fun applyEdits(
        docIds: List<String>,
        addTags: List<String> = emptyList(),
        fields: Map<String, String?> = emptyMap(),
        fillOnly: Boolean = false,
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
     * Move the document's own file to `.npn/trash` and record a doc-trash event. Returns what it did,
     * for a caller's Undo to hand to [undoTrash]: null, with nothing written, for one already trashed
     * or deleted (here or by another device) or unknown, which an Undo must leave alone.
     */
    suspend fun trashDoc(docId: String): TrashReceipt?

    suspend fun restoreDoc(docId: String)

    /**
     * [restoreDoc] each of [docIds], listing the trash once for all of them rather than once each.
     * One that fails does not stop the others; returns how many failed, each in the session log.
     */
    suspend fun restoreDocs(docIds: List<String>): Int

    /**
     * Undo [trashes]: [restoreDocs] each of their documents while that very trash holds it. One
     * trashed again since, here or on another device, stays in the trash with nothing written, as
     * one restored or deleted since does; neither is a failure. Returns how many failed, as
     * [restoreDocs] does.
     */
    suspend fun undoTrash(trashes: List<TrashReceipt>): Int

    /**
     * Delete a document in the trash for good: a doc-delete tombstone, then its bytes. A document
     * not in the trash (restored here or on another device, brought back by an import, or unknown)
     * is left alone, with nothing written. Throws only when the tombstone was not written.
     */
    suspend fun deleteDocPermanently(docId: String): PermanentDeleteOutcome

    /**
     * [deleteDocPermanently] each of [docIds], over one listing of the trash, as [restoreDocs] restores.
     * One that fails does not stop the others, and one no longer in the trash is no failure.
     */
    suspend fun deleteDocsPermanently(docIds: List<String>): PermanentDeleteTally

    /**
     * Give the document's file [newFileName] in its folder: its new location is journaled and the file
     * follows it. On storage that cannot move a document, nothing is written.
     */
    suspend fun renameFile(
        docId: String,
        newFileName: String,
    ): RelocationOutcome

    /** Move the document's file into [newDirRelPath], as [renameFile] renames it. */
    suspend fun moveFile(
        docId: String,
        newDirRelPath: String,
    ): RelocationOutcome
}

/** What [DocLifecycle.renameFile] or [DocLifecycle.moveFile] did. */
enum class RelocationOutcome {
    /** The document's new location is journaled, and its file follows it. */
    MOVED,

    /** Nothing to do: the document is not in the library, or already there. */
    UNCHANGED,

    /**
     * This library's storage cannot move a document's file (a folder opened through Android's folder
     * picker), so nothing was written.
     */
    UNSUPPORTED,
}

/**
 * A trash [DocLifecycle.trashDoc] made: [docId] went to the trash, and its row's trash time
 * ([dev.npn.core.db.DocumentEntity.trashedAt], the winning trash's) was [trashedAt] once it stood. A
 * later trash, here or on another device, gives the row another, which is how an Undo tells them
 * apart.
 */
data class TrashReceipt(
    val docId: String,
    val trashedAt: Long?,
)

/** What [DocLifecycle.deleteDocPermanently] did. */
enum class PermanentDeleteOutcome {
    /** The document is deleted, and its bytes are gone from the library. */
    DELETED,

    /** The document was not in the trash; nothing changed. */
    NOT_IN_TRASH,

    /**
     * The document is deleted, but a file holding its bytes could not be removed and stays in the
     * library folder. The cause is in the session log.
     */
    FILE_LEFT,
}

/**
 * What [DocLifecycle.deleteDocsPermanently] came to: how many documents it [failed] to delete (each
 * in the session log), and how many it deleted whose file stayed ([PermanentDeleteOutcome.FILE_LEFT]).
 */
data class PermanentDeleteTally(
    val failed: Int = 0,
    val filesLeft: Int = 0,
)
