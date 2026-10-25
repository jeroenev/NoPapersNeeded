package dev.npn.core.app

import dev.npn.core.index.SplitFailure

/** Repair and surgery on existing documents: duplicates, relinks, and splitting. */
interface MaintenanceFacade {
    /**
     * Forget the extra copy of [docId] recorded at [extraRelPath], deleting the file only while it
     * is still a copy of the document's bytes beside the document's own file.
     */
    suspend fun resolveDuplicate(
        docId: String,
        extraRelPath: String,
    )

    suspend fun applyRelink(
        oldDocId: String,
        newDocId: String,
    )

    /**
     * Split a document into one new document per [segments] entry (0-based page ranges; must be
     * contiguous and cover all pages). Parts are imported as regular new documents (deduped,
     * OCR queued), tags and single-value properties are copied onto the ones the split made (a
     * part that is a document already keeps its own), and the original is moved to trash. The
     * original file itself is never modified.
     *
     * A split that cannot finish returns [SplitOutcome.Failed] and leaves the library as it was;
     * it throws only when the session is closed.
     */
    suspend fun splitPdf(
        docId: String,
        segments: List<IntRange>,
    ): SplitOutcome

    /**
     * Drop this device's index of the library and build it again from the folder alone, as a fresh
     * device would: the way out of a write block a journal object nobody holds any more left
     * ([dev.npn.core.index.WriteBlock.JOURNAL_DAMAGED]), which no rescan can lift. A rebuild that
     * cannot finish returns [RebuildOutcome.Failed] and leaves the index as it was; it throws only
     * when the session is closed. Not available in this build yet.
     */
    suspend fun rebuildIndex(): RebuildOutcome =
        throw UnsupportedOperationException("Rebuilding the index is not available yet")
}

sealed interface SplitOutcome {
    /**
     * One docId per requested segment: the document that holds those pages now. A part whose bytes
     * were a document already is that document, with its own metadata.
     */
    data class Done(
        val partDocIds: List<String>,
    ) : SplitOutcome

    data class Failed(
        val reason: SplitFailure,
    ) : SplitOutcome
}

/** What [MaintenanceFacade.rebuildIndex] came to. */
sealed interface RebuildOutcome {
    /** The index is built again from the folder, and writes are taken. */
    data object Done : RebuildOutcome

    data class Failed(
        val reason: RebuildFailure,
    ) : RebuildOutcome
}

/** Why a rebuild of the index did not finish; the index is as it was. */
enum class RebuildFailure {
    /** The folder could not be read whole, or the index could not be written. */
    NOT_COMPLETED,
}
