package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.sync.JournalDocView
import dev.npn.core.util.EpochClock
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Reads a library and says whether its own files and its journal object store still hold the bytes
 * their names claim. Read-only by construction: it lists, stats and hashes, and never writes,
 * repairs, deletes or reads a document's content into a report.
 *
 * A document is checked against its id, the SHA-256 of its bytes, and against the size the row
 * recorded. A journal object is checked against the hash in its name, which its publication
 * verified. The walk skips symlinks ([LibraryFileSystem.listAllIndexable] does), so nothing outside
 * the library is ever followed.
 */
internal class LibraryVerifier(
    private val fs: LibraryFileSystem,
    private val clock: EpochClock,
    /** Where a failed listing goes; the run itself reports it as [VerificationProblemKind.STORE_UNREADABLE]. */
    private val log: NpnLog = NpnLog.None,
) {
    suspend fun verify(
        rows: List<DocumentEntity>,
        metadataSources: List<String>,
        journalView: suspend (Collection<String>) -> Map<String, JournalDocView>,
        onProgress: (LibraryVerificationProgress) -> Unit,
    ): LibraryVerificationReport {
        val startedAt = clock.nowMillis()
        val problems = ArrayList<LibraryVerificationProblem>()
        val notes = ArrayList<LibraryVerificationNote>()
        val views = journalView(rows.map { it.docId })

        // One walk of the library's own files, so every check below reads a regular file the walk
        // found rather than following whatever a path may have become.
        val documents = listLibrary(rows, problems)
        val journalFiles = listJournal(problems)

        // Without the listing, every source looks missing; the one STORE_UNREADABLE already says why.
        val journalPaths = journalFiles?.mapTo(HashSet()) { it.relPath }.orEmpty()
        val missingSources =
            if (journalFiles == null) emptyList() else missingSources(metadataSources, journalPaths)

        val live = documents?.let { rows.filter { row -> row.shows(views[row.docId]) } }.orEmpty()
        var documentsChecked = 0

        var done = 0
        val documentTotal = live.size
        onProgress(LibraryVerificationProgress(VerificationPhase.DOCUMENTS, done, documentTotal))
        for (row in live.sortedBy { it.relPath }) {
            currentCoroutineContext().ensureActive()
            verifyDocument(row, documents?.get(row.relPath), problems)
            documentsChecked++
            done++
            onProgress(LibraryVerificationProgress(VerificationPhase.DOCUMENTS, done, documentTotal))
        }

        noteSuperseded(rows, views, documents, notes)

        var objectsChecked = 0
        val objectTotal = journalFiles.orEmpty().size + missingSources.size
        done = 0
        onProgress(LibraryVerificationProgress(VerificationPhase.OBJECTS, done, objectTotal))
        for (meta in journalFiles.orEmpty()) {
            currentCoroutineContext().ensureActive()
            verifyObject(meta, problems)
            objectsChecked++
            done++
            onProgress(LibraryVerificationProgress(VerificationPhase.OBJECTS, done, objectTotal))
        }
        for (source in missingSources) {
            currentCoroutineContext().ensureActive()
            problems +=
                LibraryVerificationProblem(
                    kind = VerificationProblemKind.OBJECT_MISSING,
                    relPath = source,
                )
            done++
            onProgress(LibraryVerificationProgress(VerificationPhase.OBJECTS, done, objectTotal))
        }

        return LibraryVerificationReport(
            startedAt = startedAt,
            durationMillis = clock.nowMillis() - startedAt,
            documentsChecked = documentsChecked,
            objectsChecked = objectsChecked,
            problems = problems,
            notes = notes,
        )
    }

    /** The library's files by path, or null when the walk failed (reported once, not per document). */
    private suspend fun listLibrary(
        rows: List<DocumentEntity>,
        problems: MutableList<LibraryVerificationProblem>,
    ): Map<String, FileMeta>? =
        runCatchingCancellable { fs.listAllIndexable() }
            .onFailure {
                log.warn("Listing ${rows.size} documents for verification failed", it)
                problems += storeUnreadable("")
            }.getOrNull()
            ?.associateBy { it.relPath }

    /** The object store's files, or null when it could not be listed (reported once). */
    private suspend fun listJournal(problems: MutableList<LibraryVerificationProblem>): List<FileMeta>? =
        runCatchingCancellable { fs.listAllUnder(NpnPaths.JOURNAL_DIR) }
            .onFailure {
                log.warn("Listing the journal object store for verification failed", it)
                problems += storeUnreadable(NpnPaths.JOURNAL_DIR)
            }.getOrNull()
            ?.filter { !it.isDirectory }
            ?.sortedBy { it.relPath }

    /** Whether the row still names a document the library shows, by both the row and the journal. */
    private fun DocumentEntity.shows(view: JournalDocView?): Boolean =
        !trashed &&
            !deleted &&
            view?.deleted != true &&
            view?.trashed != true &&
            view?.supersededBy == null

    private suspend fun verifyDocument(
        row: DocumentEntity,
        listed: FileMeta?,
        problems: MutableList<LibraryVerificationProblem>,
    ) {
        if (listed == null) {
            // A path that exists but the walk did not include is a link or something else the library
            // cannot hold; only "nothing is there" is missing. A failure to ask is not evidence.
            val exists =
                runCatchingCancellable { fs.exists(row.relPath) }
                    .onFailure {
                        problems +=
                            LibraryVerificationProblem(
                                kind = VerificationProblemKind.DOCUMENT_UNREADABLE,
                                docId = row.docId,
                                fileName = row.fileName,
                                relPath = row.relPath,
                            )
                    }.getOrNull() ?: return
            problems +=
                LibraryVerificationProblem(
                    kind =
                        if (exists) {
                            VerificationProblemKind.DOCUMENT_NOT_A_FILE
                        } else {
                            VerificationProblemKind.DOCUMENT_MISSING
                        },
                    docId = row.docId,
                    fileName = row.fileName,
                    relPath = row.relPath,
                )
            return
        }
        if (listed.isDirectory) {
            problems +=
                LibraryVerificationProblem(
                    kind = VerificationProblemKind.DOCUMENT_NOT_A_FILE,
                    docId = row.docId,
                    fileName = row.fileName,
                    relPath = row.relPath,
                )
            return
        }
        if (listed.size != row.size) {
            problems +=
                LibraryVerificationProblem(
                    kind = VerificationProblemKind.DOCUMENT_SIZE_MISMATCH,
                    docId = row.docId,
                    fileName = row.fileName,
                    relPath = row.relPath,
                    expectedSize = row.size,
                    actualSize = listed.size,
                )
            return
        }
        val hash =
            runCatchingCancellable { fs.sha256(row.relPath) }
                .onFailure {
                    problems +=
                        LibraryVerificationProblem(
                            kind = VerificationProblemKind.DOCUMENT_UNREADABLE,
                            docId = row.docId,
                            fileName = row.fileName,
                            relPath = row.relPath,
                        )
                }.getOrNull() ?: return
        if (hash != row.docId) {
            problems +=
                LibraryVerificationProblem(
                    kind = VerificationProblemKind.DOCUMENT_HASH_MISMATCH,
                    docId = row.docId,
                    fileName = row.fileName,
                    relPath = row.relPath,
                    expectedHash = row.docId,
                    actualHash = hash,
                )
        }
    }

    /** Check one listed journal file. */
    private suspend fun verifyObject(
        meta: FileMeta,
        problems: MutableList<LibraryVerificationProblem>,
    ) {
        val path = JournalPaths.classify(meta.relPath)
        val expected =
            when (path) {
                is JournalPath.ObjectV2 -> {
                    path.expectedHash
                }

                is JournalPath.SnapshotV2 -> {
                    path.expectedHash
                }

                // Neither the app's own staging file (the temp sweep owns those) nor another
                // tool's in-flight transfer is damage.
                JournalPath.SyncTemp, JournalPath.Staging -> {
                    return
                }

                is JournalPath.UnsupportedObjectStore -> {
                    problems += orphan(meta.relPath)
                    return
                }

                JournalPath.Unknown -> {
                    problems += orphan(meta.relPath)
                    return
                }
            }
        val actual =
            runCatchingCancellable { fs.sha256(meta.relPath) }
                .onFailure {
                    problems +=
                        LibraryVerificationProblem(
                            kind = VerificationProblemKind.OBJECT_UNREADABLE,
                            relPath = meta.relPath,
                            expectedHash = expected,
                        )
                }.getOrNull() ?: return
        if (actual != expected) {
            problems +=
                LibraryVerificationProblem(
                    kind = VerificationProblemKind.OBJECT_HASH_MISMATCH,
                    relPath = meta.relPath,
                    expectedHash = expected,
                    actualHash = actual,
                )
        }
    }

    /**
     * A superseded document's file is expected to linger until a rescan removes it; report it as a
     * note, never as damage, and never hash it.
     */
    private fun noteSuperseded(
        rows: List<DocumentEntity>,
        views: Map<String, JournalDocView>,
        documents: Map<String, FileMeta>?,
        notes: MutableList<LibraryVerificationNote>,
    ) {
        if (documents == null) return
        for (row in rows) {
            val superseded = views[row.docId]?.supersededBy != null
            if (!superseded || row.relPath !in documents) continue
            notes +=
                LibraryVerificationNote(
                    kind = VerificationNoteKind.SUPERSEDED_DOCUMENT,
                    relPath = row.relPath,
                    docId = row.docId,
                )
        }
    }

    /** Journal objects the index applied that are no longer in the store, in a stable order. */
    private fun missingSources(
        sources: List<String>,
        present: Set<String>,
    ): List<String> =
        sources
            .filter { it !in present }
            .filter {
                when (JournalPaths.classify(it)) {
                    is JournalPath.ObjectV2, is JournalPath.SnapshotV2 -> true

                    is JournalPath.UnsupportedObjectStore,
                    JournalPath.Staging,
                    JournalPath.SyncTemp,
                    JournalPath.Unknown,
                    -> false
                }
            }.distinct()
            .sorted()

    private fun orphan(relPath: String): LibraryVerificationProblem =
        LibraryVerificationProblem(kind = VerificationProblemKind.OBJECT_ORPHANED, relPath = relPath)

    private fun storeUnreadable(relPath: String): LibraryVerificationProblem =
        LibraryVerificationProblem(kind = VerificationProblemKind.STORE_UNREADABLE, relPath = relPath)
}
