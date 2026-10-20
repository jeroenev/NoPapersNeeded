package dev.npn.core.index

import androidx.room3.withWriteTransaction
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.FtsRows
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.OcrQueueDao
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.fieldTypes
import dev.npn.core.db.isLive
import dev.npn.core.db.missingSentinel
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.holds
import dev.npn.core.fs.uniquifyPath
import dev.npn.core.pathrules.StoragePathContext
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pathrules.StoragePathTemplate
import dev.npn.core.pathrules.pathOrNull
import dev.npn.core.pathrules.pathTaken
import dev.npn.core.pathrules.resolveUniqueTarget
import dev.npn.core.util.EpochClock
import dev.npn.core.util.NpnLog
import dev.npn.core.util.SystemEpochClock
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal sealed interface ImportResult {
    /**
     * The bytes are [docId]'s file at [relPath] now. [broughtBack]: they were a document the library
     * had but did not show (trashed, deleted or missing), and the import brought that document back,
     * metadata and all, rather than adding one.
     */
    data class Imported(
        val docId: String,
        val relPath: String,
        val broughtBack: Boolean = false,
    ) : ImportResult

    /** A document the library shows holds these bytes already; nothing was added. */
    data class Duplicate(
        val existingDocId: String,
        val existingPath: String,
    ) : ImportResult
}

/** The file to import has no bytes. */
internal class EmptyImportException : IllegalArgumentException("The file is empty")

/**
 * The file to import holds bytes a relink superseded (a bake's original), and the document they
 * became is not one the library shows; no import brings superseded bytes back.
 */
internal class SupersededImportException :
    IllegalArgumentException("The file is an earlier version of a document in the library")

/**
 * Imports external PDFs or byte payloads into the library, deduplicating by content hash.
 *
 * Because [LibraryFileSystem.sha256] hashes a library-relative path (there is no stream-hash of an
 * [ExternalSource]), imports stage into [NpnPaths.TMP_DIR] first, hash there, dedup-check, then
 * move into place or delete. The staging file is cleaned on both success and failure.
 *
 * Only bytes the library shows are a [ImportResult.Duplicate]: a live document whose file still
 * holds them, which an unchanged size and mtime prove as they do for a rescan, and a hash of the
 * file proves otherwise. Bytes the index knows as a document it does not show (trashed, deleted,
 * missing, or live with its file gone) bring that document back instead, its row and metadata
 * kept: a caller may delete a duplicate's source (the watched folder does), and those bytes may be
 * the only copy.
 * The file lands at the import target, or stays where the document's row says if its bytes are
 * back there already.
 *
 * Bytes a relink superseded (a bake's original) are an earlier version of another document, and
 * no journal event makes a superseded document live again: they are a duplicate of the document
 * they became while the library shows it, and otherwise refused ([SupersededImportException]),
 * with nothing moved either way.
 *
 * The dedup check, the move into place and the row write run as one step through [serialize],
 * which the session points at the journal coordinator's lock, handing the step the journal
 * ([LockedJournal]) as it stands under it. Without it two imports of the same bytes both pass the
 * check and place two files for one hash, and a rescan listing the library between the move and the
 * row write inserts a row of its own that the import then overwrites (or that overwrites the
 * import's).
 *
 * Bytes the journal had deleted or trashed are recorded as back ([LockedJournal.restoreReturned])
 * in that same step, deleted ones only once this build writes `doc-undelete`; without that record,
 * the next fold would hide the document again. One step,
 * so no trash, rescan or other lifecycle change lands between the row turning live and the journal
 * saying so, and a record that fails takes the import back before the lock is let go. Once a
 * document is back, whatever the trash still holds of it is deleted: the trash keeps an entry for
 * as long as its document is live, so it would be a second copy for good.
 */
@OptIn(ExperimentalUuidApi::class)
internal class ImportService(
    private val fs: LibraryFileSystem,
    private val db: NpnDatabase,
    private val ocrQueueDao: OcrQueueDao,
    private val clock: EpochClock = SystemEpochClock,
    private val ruleProvider: suspend () -> StoragePathRule? = { null },
    private val serialize: suspend (suspend (LockedJournal) -> Unit) -> Unit,
    /** Where clearing a returned document's trashed copy, best effort, reports a failure. */
    private val log: NpnLog = NpnLog.None,
) {
    private val documentDao get() = db.documentDao()
    private val trash = TrashOps(fs)

    suspend fun importPdf(
        source: ExternalSource,
        suggestedName: String,
        toDir: String = NpnPaths.DEFAULT_INBOX,
    ): ImportResult = staged(suggestedName, toDir) { tmpPath -> fs.importExternal(source, tmpPath) }

    suspend fun importBytes(
        bytes: ByteArray,
        suggestedName: String,
        toDir: String = NpnPaths.DEFAULT_INBOX,
    ): ImportResult = staged(suggestedName, toDir) { tmpPath -> fs.writeBytesOnce(tmpPath, bytes) }

    /**
     * Import what [stage] writes to a fresh staging path. The staging file is deleted after, best
     * effort: one left behind is never indexed (it lives under `.npn/`), and a rescan sweeps it away
     * once it is old (TempFileSweep). That delete is neither cancellable nor failing, so a caller
     * cancelled while the import placed its file still gets the result, and records it (a split, to
     * take the part back) before it sees the cancellation.
     */
    private suspend inline fun staged(
        suggestedName: String,
        toDir: String,
        stage: (tmpPath: String) -> Unit,
    ): ImportResult {
        val tmpPath = NpnPaths.Temp.importPath(Uuid.random().toString())
        try {
            stage(tmpPath)
            return finishImport(tmpPath, suggestedName, toDir)
        } finally {
            withContext(NonCancellable) {
                runCatchingCancellable { if (fs.exists(tmpPath)) fs.delete(tmpPath) }
            }
        }
    }

    private suspend fun finishImport(
        tmpPath: String,
        suggestedName: String,
        toDir: String,
    ): ImportResult {
        // The stat of the staged bytes, which only this import writes, and which the move into place
        // keeps: the row records it, not a stat of the target once moved, which by then may be of a
        // file another process put there.
        val staged = fs.stat(tmpPath) ?: error("Staged import vanished: $tmpPath")
        // An empty file is never a document; its hash would claim the empty-content identity.
        if (staged.size == 0L) throw EmptyImportException()
        val hash = fs.sha256(tmpPath)
        // From the lock on, the import runs to its end even when its caller goes away: cut short
        // between placing a returning document and recording its return (or taking it back), the
        // document would be live with nothing in the journal to say so, and the next fold would hide
        // it again with the import's copy left in the library.
        return withContext(NonCancellable) {
            var outcome: ImportResult? = null
            serialize { journal ->
                outcome = recordReturn(place(tmpPath, hash, staged, suggestedName, toDir, journal), journal, tmpPath)
            }
            val result = checkNotNull(outcome)
            if (result is ImportResult.Imported && result.broughtBack) discardTrashCopies(result.docId)
            result
        }
    }

    /**
     * Record in the journal that [placed]'s document is back, when the journal still hides it, in the
     * step that placed it; when that fails, [takeBack] the placement there too and throw.
     */
    private suspend fun recordReturn(
        placed: Placement,
        journal: LockedJournal,
        tmpPath: String,
    ): ImportResult {
        val imported = placed.result as? ImportResult.Imported ?: return placed.result
        val restored =
            runCatchingCancellable { journal.restoreReturned(imported.docId, imported.relPath) }.getOrElse { failure ->
                log.runLogged("Taking back the import of ${imported.relPath}") { takeBack(placed, tmpPath) }
                throw failure
            }
        return if (restored) imported.copy(broughtBack = true) else imported
    }

    /**
     * Take back an import this service just made, for a caller whose larger operation failed after
     * it: a split whose later part could not be made, or whose original could not be trashed. An
     * import writes no journal event beyond the restore of bytes the journal had hidden, so taking
     * it back is removing the file and its rows, not a trash or a tombstone; a tombstone would hide
     * the same bytes should they be imported again.
     *
     * Follows the document, not the path the import chose: metadata the caller wrote meanwhile can
     * have moved it (a storage-path rule), and another file can have taken the name it left. Only
     * the document's own bytes are deleted, and a document since trashed or deleted is left alone.
     * So is a document the import brought back ([ImportResult.Imported.broughtBack]): it was the
     * user's before the import, and with its trashed copy gone its file may be the only one.
     */
    suspend fun undoImport(imported: ImportResult.Imported) {
        if (imported.broughtBack) return
        serialize { _ ->
            val row = documentDao.getByDocId(imported.docId) ?: return@serialize
            if (row.deleted || row.trashed) return@serialize
            if (fs.holds(row.relPath, imported.docId)) fs.delete(row.relPath)
            forget(imported.docId)
            ocrQueueDao.delete(imported.docId)
        }
    }

    /** Drop [docId] from the index: its row, and the tag, field and search rows the fold projected. */
    private suspend fun forget(docId: String) =
        db.withWriteTransaction {
            documentDao.delete(docId)
            db.tagDao().clearDocumentTagsFor(listOf(docId))
            db.documentFieldDao().clearFieldsFor(listOf(docId))
            db.ftsDao().delete(docId)
        }

    /**
     * What [place] did: its [result], whether it [moved] the imported file into the library (or found
     * the bytes back at the document's own path), and, for a document it brought back, the row and
     * OCR queue entries before and after its enqueue, for [takeBack].
     */
    private class Placement(
        val result: ImportResult,
        val moved: Boolean = false,
        val previous: DocumentEntity? = null,
        val previousQueue: OcrQueueEntity? = null,
        val queued: OcrQueueEntity? = null,
    )

    private suspend fun place(
        tmpPath: String,
        hash: String,
        staged: FileMeta,
        suggestedName: String,
        toDir: String,
        journal: LockedJournal,
    ): Placement {
        val existing = documentDao.getByDocId(hash)
        // The document's own path, when its bytes are there. This runs under the writer lock, so
        // re-importing a known, unchanged PDF must read no library file.
        val inPlace = existing?.takeIf { fs.holdsDocument(it, staged.size) }?.relPath
        ImportDecision.duplicate(existing, inPlace)?.let { return Placement(it) }
        return when (val plan = ImportDecision.place(hash, existing, inPlace, journal.successorOf(hash))) {
            is ImportPlan.Superseded -> Placement(supersededBy(plan.successor))
            is ImportPlan.Place -> placeAt(plan, tmpPath, hash, staged, suggestedName, toDir)
        }
    }

    /** Carry out [plan]: move the staged bytes into place (unless they are there), and [record] them. */
    private suspend fun placeAt(
        plan: ImportPlan.Place,
        tmpPath: String,
        hash: String,
        staged: FileMeta,
        suggestedName: String,
        toDir: String,
    ): Placement {
        val existing = plan.existing
        val inPlace = plan.inPlace
        val target = inPlace ?: chooseImportTarget(ensurePdfName(suggestedName), toDir).also { fs.move(tmpPath, it) }
        var placement: Placement? = null
        try {
            placement = record(hash, target, existing, placed = staged.takeIf { inPlace == null })
        } finally {
            // A cancelled import is taken back as a failed one is.
            if (placement == null) {
                withContext(NonCancellable) { unplace(hash, existing, target.takeIf { inPlace == null }, tmpPath) }
            }
        }
        return placement
    }

    /**
     * What an import of bytes a relink superseded comes to: a duplicate of their [successor] while
     * the library shows it and its file holds its bytes, else a refusal. Bringing the superseded
     * document back would last until the next fold, which hides it again whatever its events say.
     */
    private suspend fun supersededBy(successor: String): ImportResult {
        val shown = documentDao.getByDocId(successor)
        val holds = shown != null && shown.isLive && fs.holdsDocument(shown)
        return ImportDecision.supersededDuplicate(shown, holds) ?: throw SupersededImportException()
    }

    /**
     * Write the row of the document whose file [place] put at [target], [existing] brought back or a
     * new one, and queue it for OCR. [placed] is the stat of the staged file [place] moved there, the
     * row's from then on; null when the bytes were back at [target] already, whose stat is then read.
     */
    private suspend fun record(
        hash: String,
        target: String,
        existing: DocumentEntity?,
        placed: FileMeta?,
    ): Placement {
        val meta = placed ?: fs.stat(target) ?: error("Imported file vanished: $target")
        val now = clock.nowMillis()
        val imported = ImportDecision.row(hash, target, existing, meta, now)
        // No live row names the target ([uniqueTargetPath]); a hidden row naming it gives it up.
        documentDao.upsert(imported.row)
        val enqueued = if (imported.queueOcr) ocrQueueDao.enqueueForImport(hash, now) else null
        if (imported.reindexName) indexFileName(imported.row)
        return Placement(
            ImportResult.Imported(hash, target, broughtBack = existing != null),
            moved = placed != null,
            previous = existing,
            previousQueue = enqueued?.first,
            queued = enqueued?.second,
        )
    }

    /**
     * Undo a [place] whose rows could not be written (or, for a new document, whose return could not
     * be recorded), still under the lock: [hash]'s row goes back to [existing] (away, for a new
     * document), and the file [place] moved to [movedTo] goes back to
     * [tmpPath], so a failed import leaves nothing in the library. Best effort and logged; the caller
     * still throws the failure. A queue entry left behind is dropped by the OCR drain, which finds no
     * live document for it.
     */
    private suspend fun unplace(
        hash: String,
        existing: DocumentEntity?,
        movedTo: String?,
        tmpPath: String,
    ) {
        log.runLogged("Putting back the row of $hash after a failed import") {
            if (existing != null) documentDao.upsert(existing.onFreePath()) else forget(hash)
        }
        if (movedTo != null) {
            log.runLogged("Taking the failed import back out of $movedTo") { fs.move(movedTo, tmpPath) }
        }
    }

    /**
     * Undo [placed] after recording its document's return failed, still in the step under the lock
     * that placed it. A new document goes as [unplace] takes one back, its file to [tmpPath]. A
     * returning one gets the row and OCR queue entry it had; its file goes only while the trash still
     * holds the bytes, and otherwise stays, the row pointing at it with the lifecycle it had: a failed
     * import never deletes the only copy of a document's bytes, and a retry or a rescan finds them
     * back where the row says.
     */
    private suspend fun takeBack(
        placed: Placement,
        tmpPath: String,
    ) {
        val imported = placed.result as ImportResult.Imported
        val docId = imported.docId
        val placedAt = imported.relPath
        val previous = placed.previous ?: return unplace(docId, existing = null, movedTo = placedAt, tmpPath)
        val row =
            when {
                // Brought back in place, the file there before; or no copy of ours left where the
                // import moved one. That can be the hidden row's own path too, which a trashed
                // row keeps: a copy moved there is the import's, not the document's file.
                !placed.moved || !fs.holds(placedAt, docId) -> {
                    previous
                }

                trash.copiesOf(docId).isNotEmpty() -> {
                    fs.delete(placedAt)
                    previous
                }

                else -> {
                    val meta = fs.stat(placedAt) ?: error("Imported file vanished: $placedAt")
                    previous.copy(
                        relPath = placedAt,
                        fileName = NpnPaths.fileName(placedAt),
                        size = meta.size,
                        mtime = meta.mtimeMillis,
                        missing = false,
                    )
                }
            }
        documentDao.upsert(row.onFreePath())
        placed.queued?.let { ocrQueueDao.undoEnqueue(it, placed.previousQueue) }
        if (row.fileName != NpnPaths.fileName(placedAt)) indexFileName(row)
    }

    /**
     * Make search find [row] under its file name, which bringing a document back can change: a fold
     * rewrites a document's searchable columns only when it changes the row, and the row written
     * here already holds what a fold would project.
     */
    private suspend fun indexFileName(row: DocumentEntity) {
        db.ftsDao().upsertMetadata(listOf(FtsRows.indexed(db, row.docId, row.fileName, content = null)))
    }

    /** This row, or this row at its missing sentinel when another row holds its path now. */
    private suspend fun DocumentEntity.onFreePath(): DocumentEntity {
        val holder = documentDao.getByRelPath(relPath)?.docId
        return if (holder == null || holder == docId) this else copy(relPath = missingSentinel(docId))
    }

    /**
     * Delete, best effort, what the trash holds of [docId], now that the document is back. Decided
     * under the lock, and only while the document is live with its file holding its bytes: a trash
     * of it since moved that file into the trash, as one more entry with its bytes. The entries are
     * hashed before the lock; under it, only one that changed since, or appeared, is hashed.
     */
    private suspend fun discardTrashCopies(docId: String) {
        log.runLogged("Removing the trashed copy of $docId") {
            val hashed = trash.hashCandidates(docId)
            serialize { _ ->
                val row = documentDao.getByDocId(docId)
                if (row == null || !row.isLive || !fs.holdsDocument(row)) return@serialize
                for (path in trash.copiesOf(docId, hashed)) fs.delete(path)
            }
        }
    }

    private suspend fun chooseImportTarget(
        cleanName: String,
        toDir: String,
    ): String {
        val rule = ruleProvider()
        // A blank template expands to no path, as does any template that cannot expand.
        if (rule?.enabled != true) return uniqueTargetPath(toDir, cleanName)
        val context =
            StoragePathContext(
                fields = emptyMap(),
                tags = emptyList(),
                originalFileName = cleanName,
                // The registry's types decide where a template accepts a date pattern.
                fieldTypes = db.fieldDefDao().fieldTypes(),
            )
        val expanded = StoragePathTemplate.expand(rule.template, context).pathOrNull
        return if (expanded == null) {
            uniqueTargetPath(toDir, cleanName)
        } else {
            resolveUniqueTarget(fs, documentDao, currentDocId = null, target = expanded)
        }
    }

    /**
     * Build `dir/name.pdf`, adding " (2)", " (3)", ... before the extension until free: no file is
     * there and no live row names it. A live row whose file went outside the app names its path until
     * a rescan finds it missing; a hidden row gives its path up to a live document.
     */
    private suspend fun uniqueTargetPath(
        dir: String,
        name: String,
    ): String {
        val normDir = NpnPaths.normalize(dir)
        val target = if (normDir.isEmpty()) name else "$normDir/$name"
        return uniquifyPath(target, startIndex = 2, suffix = { " ($it)" }) { candidate ->
            pathTaken(fs, documentDao, currentDocId = null, candidate)
        }
    }
}

/** [name] as the file name of an imported PDF: sanitized, never empty, ending in `.pdf`. */
private fun ensurePdfName(name: String): String {
    val sanitized = NpnPaths.sanitizeFileName(name.trim()).ifEmpty { "document" }
    return if (NpnPaths.isPdf(sanitized)) sanitized else "$sanitized.pdf"
}
