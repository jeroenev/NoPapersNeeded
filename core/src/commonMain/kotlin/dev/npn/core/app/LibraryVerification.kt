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

/**
 * The library check the session runs ([MaintenanceFacade.startVerification]): its [progress] while
 * [running], then its [report], or the [failure] that ended it.
 * Cleared by [MaintenanceFacade.clearVerification] once the result was shown.
 */
data class LibraryVerificationRun(
    val running: Boolean = false,
    val progress: LibraryVerificationProgress? = null,
    val report: LibraryVerificationReport? = null,
    val failure: Throwable? = null,
)

/** What a verification found wrong, as one item. */
enum class VerificationProblemKind {
    /** A document the library shows has no file in the library folder, and the library did not know. */
    DOCUMENT_MISSING,

    /**
     * A document whose file the library already lists as missing (a rescan found it gone): still a
     * loss, so the library is not intact, but no news to the library.
     */
    DOCUMENT_KNOWN_MISSING,

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

    /** A file in one of this build's object buckets that is named as no journal object. */
    OBJECT_ORPHANED,

    /** The library folder or its object store could not be listed at all. */
    STORE_UNREADABLE,
    ;

    /** Whether this is a problem with a document's own file, rather than with the journal. */
    val isDocument: Boolean
        get() =
            when (this) {
                DOCUMENT_MISSING,
                DOCUMENT_KNOWN_MISSING,
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
 * One finding of [MaintenanceFacade.verifyLibrary]. A document is named by its library path, or by
 * its id when nothing records a path, never by its content; [expectedHash] and
 * [actualHash] are fingerprints, and [expectedSize] and [actualSize] are sizes in bytes. Nothing here
 * carries the bytes of a document or a journal object.
 */
data class LibraryVerificationProblem(
    val kind: VerificationProblemKind,
    /** The document this belongs to, for the document kinds. */
    val docId: String? = null,
    /** The document's file name at the time of the check, for the document kinds that have one. */
    val fileName: String? = null,
    /**
     * Where in the library folder the file is (or should be): never an internal name under `.npn/`
     * but for the journal kinds. Null for a document no row and no storage path place anywhere.
     */
    val relPath: String?,
    /** Whether the library has the document to open, so the report can offer to. */
    val openable: Boolean = false,
    val expectedSize: Long? = null,
    val actualSize: Long? = null,
    val expectedHash: String? = null,
    val actualHash: String? = null,
)

/**
 * What [MaintenanceFacade.verifyLibrary] checked and found. [documentsChecked] and [objectsChecked] are
 * how many items were looked at, not how many passed; [problems] is empty when the library is intact,
 * and only then: a document already known to be missing is a problem too.
 */
data class LibraryVerificationReport(
    val startedAt: Long,
    val durationMillis: Long,
    val documentsChecked: Int,
    val objectsChecked: Int,
    val problems: List<LibraryVerificationProblem> = emptyList(),
) {
    val ok: Boolean get() = problems.isEmpty()

    /** Documents the library already lists as missing: their own group, apart from damage found now. */
    val knownMissing: List<LibraryVerificationProblem>
        get() = problems.filter { it.kind == VerificationProblemKind.DOCUMENT_KNOWN_MISSING }

    /** What the check found wrong with documents' files, beyond those already known missing. */
    val documentProblems: List<LibraryVerificationProblem>
        get() = problems.filter { it.kind.isDocument && it.kind != VerificationProblemKind.DOCUMENT_KNOWN_MISSING }

    val objectProblems: List<LibraryVerificationProblem>
        get() = problems.filter { !it.kind.isDocument && it.kind != VerificationProblemKind.STORE_UNREADABLE }

    /** A folder or object store that could not be listed; it belongs to neither group below. */
    val storeProblems: List<LibraryVerificationProblem>
        get() = problems.filter { it.kind == VerificationProblemKind.STORE_UNREADABLE }
}
