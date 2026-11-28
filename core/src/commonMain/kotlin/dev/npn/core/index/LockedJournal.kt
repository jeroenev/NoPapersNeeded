package dev.npn.core.index

import dev.npn.core.fs.DocumentFacts

/**
 * The journal as a step under the writer lock reads and writes it
 * ([JournalCoordinator.serialized]): an import's ([ImportService]), a delete for
 * good's removal of the bytes and the trash purge's deletes. Its calls take no lock of their own, so
 * they are for that step alone. What it says of a document is what the [dev.npn.core.fs.DeleteGate]
 * deletes its bytes by.
 */
internal interface LockedJournal : DocumentFacts {
    /** The document [docId]'s bytes became, its relink chain followed to the end; [docId] when none superseded it. */
    override suspend fun successorOf(docId: String): String

    /**
     * Whether the journal has [docId] deleted: its latest delete is newer than any undelete. An
     * import that brings it back writes one ([restoreReturned]) once this build writes
     * `doc-undelete` ([dev.npn.core.journal.JournalOp.DocUndelete.WRITTEN]), and until then none,
     * so false too for a document an import brought back in this session unrecorded, until its next
     * lifecycle change; a rescan showing its file again soon after the delete writes none, and
     * leaves it deleted.
     */
    override suspend fun isDeleted(docId: String): Boolean

    /** Whether the journal has [docId] in the trash (and not deleted). */
    override suspend fun isTrashed(docId: String): Boolean

    /** Whether the library shows [docId]: neither trashed, deleted, nor superseded by a relink. */
    suspend fun isCurrent(docId: String): Boolean

    /**
     * Whether an event names [docId] (its own, or a relink onto it): a document the library knows,
     * where [isCurrent] says true of any id it never saw as well.
     */
    suspend fun isNamed(docId: String): Boolean = false

    /**
     * Record that [docId]'s bytes are in the library again, at [relPath], when the journal still has
     * it deleted or trashed. Writes nothing, and needs no write access, for a document nothing hides,
     * nor for a deleted one while this build writes no `doc-undelete`: the import then stands as
     * builds before that op left it, shown until a fold of the journal hides it again. Returns
     * whether it wrote the return; throws only when it did not.
     */
    suspend fun restoreReturned(
        docId: String,
        relPath: String,
    ): Boolean

    /**
     * Record [docId] deleted for good (`doc-delete`), for an import taken back
     * ([ImportService.undoImport]) before its file is deleted: a sync tool may have carried the file
     * to another device meanwhile, where it is a document, and the deletion it carries there next
     * must be one the journal says. Throws only when nothing was published.
     */
    suspend fun recordTakenBack(docId: String)

    /**
     * For the trash purge: every document the journal knows that is deleted, or else not superseded,
     * mapped to when it was deleted for good or trashed, or to null while it is neither
     * ([dev.npn.core.journal.FoldedState.trashTimes]).
     */
    suspend fun trashTimes(): Map<String, Long?>

    /**
     * For the keep store's purge: each of [docs] mapped to when its copy stops being needed
     * ([dev.npn.core.journal.FoldedState.keepTime]), or null to keep it. A journal that knows no such
     * times keeps every copy.
     */
    suspend fun keepTimes(docs: Set<String>): Map<String, Long?> = docs.associateWith { null }
}
