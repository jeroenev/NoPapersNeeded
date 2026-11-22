package dev.npn.core.app

/** What part of the library a [LibraryVerificationProgress] is walking. */
enum class VerificationPhase {
    DOCUMENTS,
    OBJECTS,
}

/** How far a [MaintenanceFacade.verifyLibrary] run has got: [done] of [total] items of [phase]. */
data class LibraryVerificationProgress(
    val phase: VerificationPhase,
    val done: Int,
    val total: Int,
)

/** What a verification found wrong, as one item. */
enum class VerificationProblemKind {
    /** A live document's file is not in the library folder. */
    DOCUMENT_MISSING,

    /** The file at a live document's path could not be read. */
    DOCUMENT_UNREADABLE,

    /** The path holds something that is not a regular file, such as a folder or a link. */
    DOCUMENT_NOT_A_FILE,

    /** The file's size is not the document's stored size. */
    DOCUMENT_SIZE_MISMATCH,

    /** The file's bytes hash to something other than the document id. */
    DOCUMENT_HASH_MISMATCH,

    /** A journal object the index applied is no longer in the library folder. */
    OBJECT_MISSING,

    /** A journal object's bytes could not be read. */
    OBJECT_UNREADABLE,

    /** A journal object's bytes do not hash to the name it is published under. */
    OBJECT_HASH_MISMATCH,

    /** A file in the object store the journal cannot read as an object of this build. */
    OBJECT_ORPHANED,

    /** The library folder or its object store could not be listed at all. */
    STORE_UNREADABLE,
    ;

    /** Whether this is a problem with a document's own file, rather than with the journal. */
    val isDocument: Boolean
        get() =
            when (this) {
                DOCUMENT_MISSING,
                DOCUMENT_UNREADABLE,
                DOCUMENT_NOT_A_FILE,
                DOCUMENT_SIZE_MISMATCH,
                DOCUMENT_HASH_MISMATCH,
                -> true

                OBJECT_MISSING,
                OBJECT_UNREADABLE,
                OBJECT_HASH_MISMATCH,
                OBJECT_ORPHANED,
                STORE_UNREADABLE,
                -> false
            }
}

/**
 * One finding of [MaintenanceFacade.verifyLibrary]. A document's name is its file name, never its
 * content; [expectedHash] and [actualHash] are fingerprints, and [expectedSize] and [actualSize] are
 * sizes in bytes. Nothing here carries the bytes of a document or a journal object.
 */
data class LibraryVerificationProblem(
    val kind: VerificationProblemKind,
    /** The document this belongs to, for the document kinds. */
    val docId: String? = null,
    /** The document's file name at the time of the check, for the document kinds. */
    val fileName: String? = null,
    val relPath: String,
    val expectedSize: Long? = null,
    val actualSize: Long? = null,
    val expectedHash: String? = null,
    val actualHash: String? = null,
)

/** A finding that is not a problem but is worth saying, so nobody reads it as damage. */
enum class VerificationNoteKind {
    /** A document a relink superseded still has its old file; the next rescan removes it. */
    SUPERSEDED_DOCUMENT,

    /** A journal object store of a version this build does not read (another build's format). */
    UNSUPPORTED_OBJECT_STORE,
}

data class LibraryVerificationNote(
    val kind: VerificationNoteKind,
    val relPath: String,
    val docId: String? = null,
)

/**
 * What [MaintenanceFacade.verifyLibrary] checked and found. [documentsChecked] and [objectsChecked] are
 * how many items were looked at, not how many passed; [problems] is empty when the library is intact.
 * [notes] are the expected leftovers, such as a superseded document still on disk.
 */
data class LibraryVerificationReport(
    val startedAt: Long,
    val durationMillis: Long,
    val documentsChecked: Int,
    val objectsChecked: Int,
    val problems: List<LibraryVerificationProblem> = emptyList(),
    val notes: List<LibraryVerificationNote> = emptyList(),
) {
    val ok: Boolean get() = problems.isEmpty()

    val documentProblems: List<LibraryVerificationProblem>
        get() = problems.filter { it.kind.isDocument }

    val objectProblems: List<LibraryVerificationProblem>
        get() = problems.filter { !it.kind.isDocument && it.kind != VerificationProblemKind.STORE_UNREADABLE }

    /** A folder or object store that could not be listed; it belongs to neither group below. */
    val storeProblems: List<LibraryVerificationProblem>
        get() = problems.filter { it.kind == VerificationProblemKind.STORE_UNREADABLE }
}
