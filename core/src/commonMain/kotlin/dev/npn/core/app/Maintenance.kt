package dev.npn.core.app

import dev.npn.core.index.SplitFailure
import kotlinx.coroutines.flow.StateFlow

/** Repair and surgery on existing documents: duplicates, relinks, and splitting; and on the index. */
interface MaintenanceFacade {
    /**
     * Rebuild the index's metadata from the library folder alone, as a fresh index's open does:
     * forget every journal object this device applied and read the folder's again. The way out of a
     * library whose writes stay refused ([LibrarySession.writeBlock], `JOURNAL_DAMAGED`) because a
     * journal object this device applied is gone for good from the folder: what only that object
     * held is forgotten, as a device that never saw it shows the library. Nothing of the folder is
     * touched. Throws only when the session is closed.
     */
    suspend fun rebuildIndex(): RebuildOutcome

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
     * Check the library folder against itself: every document the journal shows has its file there,
     * readable, and hashing to the document's id (one the library already lists as missing is
     * reported as such, never passed over), and every journal object in the store holds the bytes its
     * name claims. Reports what is missing, unreadable or changed, and misnamed files in the object
     * store. Read-only: nothing is repaired, moved or deleted, and no document's content reaches the
     * report. Main-safe and cancellable, reporting [onProgress] as it walks; throws only when the
     * session is closed.
     */
    suspend fun verifyLibrary(onProgress: (LibraryVerificationProgress) -> Unit = {}): LibraryVerificationReport

    /** The check [startVerification] runs, as it goes, then its result until [clearVerification]. */
    val verification: StateFlow<LibraryVerificationRun>

    /**
     * Run [verifyLibrary] as the session's own work, so it goes on when the screen that asked for it
     * closes; [verification] follows it. False, starting nothing, while one runs or once the session
     * is closed.
     */
    fun startVerification(): Boolean

    /** Stop the running check, leaving no result. */
    fun cancelVerification()

    /** Forget the last check's result once it was shown. */
    fun clearVerification()

    /**
     * Counts for a diagnostics bundle: the index's rows and journal objects, nothing that names a
     * document. On demand only, never on a hot path.
     */
    suspend fun diagnostics(): LibraryDiagnostics
}

/**
 * What a diagnostics bundle says about the open library, as counts. No titles, paths, hashes or
 * content: those must never reach a public issue.
 */
data class LibraryDiagnostics(
    val liveDocuments: Int = 0,
    val trashedDocuments: Int = 0,
    val missingDocuments: Int = 0,
    val tags: Int = 0,
    val fields: Int = 0,
    val journalObjects: Int = 0,
    val ocrQueue: Int = 0,
    val enrichmentQueue: Int = 0,
    val editRuns: Int = 0,
)

sealed interface RebuildOutcome {
    /** The index is the fold of the folder's journal again, and writes are taken. */
    data object Rebuilt : RebuildOutcome

    /**
     * The rebuild failed (logged): writes stay refused ([LibrarySession.writeBlock] says why), and the
     * index shows what it showed.
     */
    data object Failed : RebuildOutcome
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
