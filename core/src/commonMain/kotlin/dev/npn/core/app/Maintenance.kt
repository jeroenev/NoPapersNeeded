package dev.npn.core.app

/** Repair and surgery on existing documents: duplicates, relinks, and splitting. */
interface MaintenanceFacade {
    suspend fun resolveDuplicate(
        docId: String,
        extraRelPath: String,
        keepCanonical: Boolean,
    )

    suspend fun applyRelink(
        oldDocId: String,
        newDocId: String,
    )

    /**
     * Split a document into one new document per [segments] entry (0-based page ranges; must be
     * contiguous and cover all pages). Parts are imported as regular new documents (deduped,
     * OCR queued), tags and single-value properties are copied onto them, and the original is
     * moved to trash. The original file itself is never modified.
     *
     * A split that cannot finish returns [SplitOutcome.Failed] and leaves the library as it was;
     * it throws only when the session is closed.
     */
    suspend fun splitPdf(
        docId: String,
        segments: List<IntRange>,
    ): SplitOutcome
}

sealed interface SplitOutcome {
    /** One docId per requested segment (an already-existing identical part reuses its docId). */
    data class Done(
        val partDocIds: List<String>,
    ) : SplitOutcome

    data class Failed(
        val reason: SplitFailure,
    ) : SplitOutcome
}

/** Why a split did not happen. The underlying cause, if any, is in the session log. */
enum class SplitFailure {
    /** The document is gone, trashed or missing. */
    DOCUMENT_UNAVAILABLE,

    /** The page ranges are not at least two parts covering every page once, in order. */
    INVALID_PARTS,

    /** The PDF could not be read. */
    UNREADABLE_PDF,

    /** A part, its metadata or the trashing of the original failed; the parts were taken back. */
    NOT_COMPLETED,
}
