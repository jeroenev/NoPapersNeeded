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
 * A document a library check expects a file of: one the journal shows (neither trashed nor deleted,
 * nor superseded by a relink), placed where the index's row has its file, else where its storage path
 * says. [relPath] is null when neither places it; [size] is the size a live row recorded, null when
 * there is none to compare; [knownMissing] when the library already lists it as missing.
 */
internal data class ExpectedDocument(
    val docId: String,
    val relPath: String?,
    val fileName: String?,
    val size: Long?,
    val knownMissing: Boolean,
    /** The index has a row of it, so the library can open it. */
    val openable: Boolean,
) {
    companion object {
        /**
         * What a check expects of [docId], from its index [row] and what the fold says of it
         * ([folded]); null when the library should hold no file of it. The journal decides that: a
         * row the index hides as trashed or deleted is left for the fold to settle, and a document
         * the journal names that the index lost its row of is still expected.
         */
        fun of(
            docId: String,
            row: DocumentEntity?,
            folded: JournalDocView?,
        ): ExpectedDocument? {
            if (folded?.shown == false) return null
            if (row == null && folded == null) return null
            if (row != null && (row.trashed || row.deleted)) return null
            // A row waiting at its sentinel holds no path; the sentinel is internal and never shown.
            val rowPath = row?.relPath?.takeIf(NpnPaths::isDocumentPath)
            val relPath = rowPath ?: folded?.intendedPath
            return ExpectedDocument(
                docId = docId,
                relPath = relPath,
                fileName = row?.fileName ?: relPath?.let(NpnPaths::fileName),
                size = row?.size?.takeIf { !row.missing && rowPath != null },
                knownMissing = row?.missing == true,
                openable = row != null,
            )
        }
    }
}

/** The library shows the document: the fold has it neither deleted nor trashed, nor superseded. */
private val JournalDocView.shown: Boolean
    get() = !deleted && !trashed && supersededBy == null

/**
 * What a library check reads besides the folder itself, each call fresh: the documents the library
 * should hold, one of them again before a problem with it is reported, and the journal objects the
 * index applied.
 */
internal interface VerificationSource {
    suspend fun expectedDocuments(): List<ExpectedDocument>

    /** [docId] as the library has it now, or null when it should hold no file of it any more. */
    suspend fun expectedDocument(docId: String): ExpectedDocument?

    /** The journal objects (and snapshots) the index applied events from. */
    suspend fun appliedSources(): List<String>

    /**
     * Of [gone], applied sources the store no longer lists, those holding an event no source in
     * [listed] holds: a gone source whose events another holds lost nothing, as reconcile reads it.
     */
    suspend fun unheldSources(
        gone: List<String>,
        listed: Set<String>,
    ): Set<String>
}

/**
 * Reads a library and says whether its own files and its journal object store still hold the bytes
 * their names claim. Read-only by construction: it lists, stats and hashes, and never writes,
 * repairs, deletes or reads a document's content into a report.
 *
 * Which documents should have a file is the journal's to say ([VerificationSource]), the index's
 * rows only placing them. A document is checked against its id, the SHA-256 of its bytes, and against
 * the size its row recorded. A journal object is checked against the hash in its name, which its
 * publication verified. The walk skips symlinks ([LibraryFileSystem.listAllIndexable] does), so
 * nothing outside the library is ever followed.
 *
 * The walk takes minutes on a large library and holds no lock, so a document may be moved, trashed
 * or replaced while it runs. A problem with a document is reported only once it holds against a fresh
 * read of the document and a fresh listing, after the walk. Hashing is cancellable between files, not
 * within one.
 */
internal class LibraryVerifier(
    private val fs: LibraryFileSystem,
    private val clock: EpochClock,
    /** Where a failed listing goes; the run itself reports it as [VerificationProblemKind.STORE_UNREADABLE]. */
    private val log: NpnLog = NpnLog.None,
) {
    suspend fun verify(
        source: VerificationSource,
        onProgress: (LibraryVerificationProgress) -> Unit,
    ): LibraryVerificationReport {
        val startedAt = clock.nowMillis()
        val problems = ArrayList<LibraryVerificationProblem>()
        val expected = source.expectedDocuments().sortedWith(compareBy({ it.relPath ?: it.fileName }, { it.docId }))

        // One walk of the library's own files, so every check below reads a regular file the walk
        // found rather than following whatever a path may have become.
        val documents = listLibrary(expected.size, problems)
        val journalFiles = listJournal(problems)

        var documentsChecked = 0
        if (documents != null) {
            val suspects = ArrayList<String>()
            onProgress(LibraryVerificationProgress(VerificationPhase.DOCUMENTS, 0, expected.size))
            for (doc in expected) {
                currentCoroutineContext().ensureActive()
                if (check(doc, documents) != null) suspects += doc.docId
                documentsChecked++
                onProgress(LibraryVerificationProgress(VerificationPhase.DOCUMENTS, documentsChecked, expected.size))
            }
            problems += recheck(suspects, source)
        }

        // Without the listing, every source looks missing; the one STORE_UNREADABLE already says why.
        val missingSources = if (journalFiles == null) emptyList() else missingSources(source, journalFiles)
        var objectsChecked = 0
        val objectTotal = journalFiles.orEmpty().size + missingSources.size
        var done = 0
        onProgress(LibraryVerificationProgress(VerificationPhase.OBJECTS, done, objectTotal))
        for (meta in journalFiles.orEmpty()) {
            currentCoroutineContext().ensureActive()
            verifyObject(meta, problems)
            objectsChecked++
            onProgress(LibraryVerificationProgress(VerificationPhase.OBJECTS, ++done, objectTotal))
        }
        for (path in missingSources) {
            problems += LibraryVerificationProblem(kind = VerificationProblemKind.OBJECT_MISSING, relPath = path)
            onProgress(LibraryVerificationProgress(VerificationPhase.OBJECTS, ++done, objectTotal))
        }

        return LibraryVerificationReport(
            startedAt = startedAt,
            durationMillis = clock.nowMillis() - startedAt,
            documentsChecked = documentsChecked,
            objectsChecked = objectsChecked,
            problems = problems,
        )
    }

    /**
     * The problems among [suspects] that still hold against a fresh read of each document and a
     * fresh listing: one moved, trashed or replaced during the walk is checked where it is now, or
     * not at all. A listing that fails now reports nothing it cannot confirm.
     */
    private suspend fun recheck(
        suspects: List<String>,
        source: VerificationSource,
    ): List<LibraryVerificationProblem> {
        if (suspects.isEmpty()) return emptyList()
        val listing =
            runCatchingCancellable { fs.listAllIndexable() }
                .onFailure { log.warn("Listing the library again to confirm ${suspects.size} problems failed", it) }
                .getOrNull()
                ?.associateBy { it.relPath } ?: return emptyList()
        return suspects.mapNotNull { docId ->
            currentCoroutineContext().ensureActive()
            source.expectedDocument(docId)?.let { check(it, listing) }
        }
    }

    /** The library's files by path, or null when the walk failed (reported once, not per document). */
    private suspend fun listLibrary(
        expected: Int,
        problems: MutableList<LibraryVerificationProblem>,
    ): Map<String, FileMeta>? =
        runCatchingCancellable { fs.listAllIndexable() }
            .onFailure {
                log.warn("Listing $expected documents for verification failed", it)
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

    /** What is wrong with [doc]'s file as [listing] has it, or null when it holds the document's bytes. */
    private suspend fun check(
        doc: ExpectedDocument,
        listing: Map<String, FileMeta>,
    ): LibraryVerificationProblem? {
        val relPath = doc.relPath ?: return doc.problem(VerificationProblemKind.DOCUMENT_MISSING)
        val listed = listing[relPath]
        return when {
            listed == null || listed.isDirectory -> {
                absent(doc, relPath, listed)
            }

            doc.size != null && listed.size != doc.size -> {
                doc.problem(
                    VerificationProblemKind.DOCUMENT_SIZE_MISMATCH,
                    expectedSize = doc.size,
                    actualSize = listed.size,
                )
            }

            else -> {
                checkBytes(doc, relPath)
            }
        }
    }

    /**
     * [doc]'s path holds no regular file the walk listed. A path that exists but the walk did not
     * include is a link or something else the library cannot hold; only "nothing is there" is
     * missing. A failure to ask is not evidence either way.
     */
    private suspend fun absent(
        doc: ExpectedDocument,
        relPath: String,
        listed: FileMeta?,
    ): LibraryVerificationProblem {
        val exists =
            listed != null ||
                runCatchingCancellable { fs.exists(relPath) }.getOrNull()
                    ?: return doc.problem(VerificationProblemKind.DOCUMENT_UNREADABLE)
        return doc.problem(
            if (exists) VerificationProblemKind.DOCUMENT_NOT_A_FILE else VerificationProblemKind.DOCUMENT_MISSING,
        )
    }

    /** Whether the file at [relPath] hashes to [doc]'s id; null when it does. */
    private suspend fun checkBytes(
        doc: ExpectedDocument,
        relPath: String,
    ): LibraryVerificationProblem? {
        val hash =
            runCatchingCancellable { fs.sha256(relPath) }.getOrNull()
                ?: return doc.problem(VerificationProblemKind.DOCUMENT_UNREADABLE)
        return if (hash == doc.docId) {
            null
        } else {
            doc.problem(VerificationProblemKind.DOCUMENT_HASH_MISMATCH, expectedHash = doc.docId, actualHash = hash)
        }
    }

    /**
     * A problem of [kind] with this document. For one the library already lists as missing, whatever
     * is at its path, it is the one known loss ([VerificationProblemKind.DOCUMENT_KNOWN_MISSING]); for
     * one the index has no row of, its storage path only says where it was meant to be, so whatever
     * holds that path is another file, and the document is missing.
     */
    private fun ExpectedDocument.problem(
        kind: VerificationProblemKind,
        expectedSize: Long? = null,
        actualSize: Long? = null,
        expectedHash: String? = null,
        actualHash: String? = null,
    ): LibraryVerificationProblem =
        if (knownMissing || !openable) {
            LibraryVerificationProblem(
                kind =
                    if (knownMissing) {
                        VerificationProblemKind.DOCUMENT_KNOWN_MISSING
                    } else {
                        VerificationProblemKind.DOCUMENT_MISSING
                    },
                docId = docId,
                fileName = fileName,
                relPath = relPath,
                openable = openable,
            )
        } else {
            LibraryVerificationProblem(
                kind = kind,
                docId = docId,
                fileName = fileName,
                relPath = relPath,
                openable = openable,
                expectedSize = expectedSize,
                actualSize = actualSize,
                expectedHash = expectedHash,
                actualHash = actualHash,
            )
        }

    /** Check one listed journal file. */
    private suspend fun verifyObject(
        meta: FileMeta,
        problems: MutableList<LibraryVerificationProblem>,
    ) {
        val expected =
            when (val path = JournalPaths.classify(meta.relPath)) {
                is JournalPath.ObjectV2 -> {
                    path.expectedHash
                }

                is JournalPath.SnapshotV2 -> {
                    path.expectedHash
                }

                // Neither the app's own staging file (the temp sweep owns those), another tool's
                // in-flight transfer, nor a newer build's object store is damage: reconcile reads past
                // the first two and refuses the library over the third (a newer format).
                JournalPath.SyncTemp, JournalPath.Staging, is JournalPath.UnsupportedObjectStore -> {
                    return
                }

                // Only a misnamed file inside one of this build's buckets is damage, as reconcile
                // finds it; anything else beside the buckets is no object at all.
                JournalPath.Unknown -> {
                    if (JournalPaths.objectStoreVersion(meta.relPath) in SUPPORTED_STORES) {
                        problems +=
                            LibraryVerificationProblem(
                                kind = VerificationProblemKind.OBJECT_ORPHANED,
                                relPath = meta.relPath,
                            )
                    }
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
     * Journal objects the index applied that are no longer in the store and held an event no listed
     * source holds, in a stable order. A source never applied (one still arriving, or damaged) recorded
     * nothing, and a gone one whose every event another holds (a conflict copy the sync tool removed)
     * lost nothing: reconcile reads past both, and so does the check.
     */
    private suspend fun missingSources(
        source: VerificationSource,
        journalFiles: List<FileMeta>,
    ): List<String> {
        val present = journalFiles.mapTo(HashSet()) { it.relPath }
        val applied = source.appliedSources()
        val gone =
            applied.filter { it !in present }.filter {
                when (JournalPaths.classify(it)) {
                    is JournalPath.ObjectV2, is JournalPath.SnapshotV2 -> true

                    is JournalPath.UnsupportedObjectStore,
                    JournalPath.Staging,
                    JournalPath.SyncTemp,
                    JournalPath.Unknown,
                    -> false
                }
            }
        if (gone.isEmpty()) return emptyList()
        return source
            .unheldSources(gone, applied.filterTo(HashSet()) { it in present })
            .sorted()
    }

    private fun storeUnreadable(relPath: String): LibraryVerificationProblem =
        LibraryVerificationProblem(kind = VerificationProblemKind.STORE_UNREADABLE, relPath = relPath)

    private companion object {
        val SUPPORTED_STORES = setOf(JournalPaths.STORAGE_V2, JournalPaths.SNAPSHOT_V2)
    }
}
