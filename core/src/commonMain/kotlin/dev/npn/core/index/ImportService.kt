package dev.npn.core.index

import androidx.room3.withWriteTransaction
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.OcrQueueDao
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.QueueState
import dev.npn.core.db.isLive
import dev.npn.core.db.missingSentinel
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.holds
import dev.npn.core.fs.uniquifyPath
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.model.FieldType
import dev.npn.core.ocr.OcrState
import dev.npn.core.pathrules.StoragePathContext
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pathrules.StoragePathTemplate
import dev.npn.core.pathrules.pathOrNull
import dev.npn.core.pathrules.resolveUniqueTarget
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

sealed interface ImportResult {
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
class EmptyImportException : IllegalArgumentException("The file is empty")

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
 * The dedup check, the move into place and the row write run as one step through [serialize],
 * which the session points at the journal coordinator's lock. Without it two imports of the same
 * bytes both pass the check and place two files for one hash, and a rescan listing the library
 * between the move and the row write inserts a row of its own that the import then overwrites
 * (or that overwrites the import's).
 *
 * Bytes the journal had deleted or trashed come back through [restore], which records that they
 * are back (`JournalCoordinator.restoreReturned`); without that record, the next fold would hide
 * the document again. Once a document is back, whatever the trash still holds of it is deleted:
 * the trash keeps an entry for as long as its document is live, so it would be a second copy for
 * good.
 */
@OptIn(ExperimentalUuidApi::class)
class ImportService(
    private val fs: LibraryFileSystem,
    private val db: NpnDatabase,
    private val ocrQueueDao: OcrQueueDao,
    private val clock: EpochClock = SystemEpochClock,
    private val ruleProvider: suspend () -> StoragePathRule? = { null },
    /** The registry's field types by key, which decide where a template accepts a date pattern. */
    private val fieldTypes: suspend () -> Map<String, FieldType>,
    private val serialize: suspend (suspend () -> Unit) -> Unit = { it() },
    /**
     * Record that the document is back at the path, when the journal still hides it; returns
     * whether it wrote that, and throws only when it did not.
     */
    private val restore: suspend (docId: String, relPath: String) -> Boolean = { _, _ -> false },
    /** Where clearing a returned document's trashed copy, best effort, reports a failure. */
    private val log: NpnLog = NpnLog.None,
) {
    private val documentDao get() = db.documentDao()
    private val trash = TrashOps(fs)

    suspend fun importPdf(
        source: ExternalSource,
        suggestedName: String,
        toDir: String = NpnPaths.DEFAULT_INBOX,
    ): ImportResult {
        val tmpPath = freshTmpPath()
        try {
            fs.importExternal(source, tmpPath)
            return finishImport(tmpPath, suggestedName, toDir)
        } finally {
            cleanup(tmpPath)
        }
    }

    suspend fun importBytes(
        bytes: ByteArray,
        suggestedName: String,
        toDir: String = NpnPaths.DEFAULT_INBOX,
    ): ImportResult {
        val tmpPath = freshTmpPath()
        try {
            fs.writeBytesOnce(tmpPath, bytes)
            return finishImport(tmpPath, suggestedName, toDir)
        } finally {
            cleanup(tmpPath)
        }
    }

    private suspend fun finishImport(
        tmpPath: String,
        suggestedName: String,
        toDir: String,
    ): ImportResult {
        val size = fs.stat(tmpPath)?.size ?: error("Staged import vanished: $tmpPath")
        // An empty file is never a document; its hash would claim the empty-content identity.
        if (size == 0L) throw EmptyImportException()
        val hash = fs.sha256(tmpPath)
        var placement: Placement? = null
        serialize { placement = place(tmpPath, hash, size, suggestedName, toDir) }
        val placed = checkNotNull(placement)
        val imported = placed.result as? ImportResult.Imported ?: return placed.result
        // After [serialize]: the restore is a journal write, which takes the same lock.
        val restored =
            runCatchingCancellable { restore(imported.docId, imported.relPath) }.getOrElse { failure ->
                log.runLogged("Taking back the import of ${imported.relPath}") { takeBack(placed) }
                throw failure
            }
        if (!restored && !imported.broughtBack) return imported
        discardTrashCopies(imported.docId)
        return imported.copy(broughtBack = true)
    }

    /**
     * Take back an import this service just made, for a caller whose larger operation failed after
     * it: a split whose later part could not be made, or whose original could not be trashed. An
     * import writes no journal event beyond the restore of bytes the journal had hidden, so taking
     * it back is removing the file and its rows, not a trash or a tombstone; a tombstone would hide
     * the same bytes when the caller retries and makes the same part again.
     *
     * Follows the document, not the path the import chose: metadata the caller wrote meanwhile can
     * have moved it (a storage-path rule), and another file can have taken the name it left. Only
     * the document's own bytes are deleted, and a document since trashed or deleted is left alone.
     * So is a document the import brought back ([ImportResult.Imported.broughtBack]): it was the
     * user's before the import, and with its trashed copy gone its file may be the only one.
     */
    suspend fun undoImport(imported: ImportResult.Imported) {
        if (imported.broughtBack) return
        serialize {
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
     * What [place] did: its [result] and, for a document it brought back, the row and OCR queue
     * entry it had before ([queuedOcr]: whether [place] queued it), for [takeBack].
     */
    private class Placement(
        val result: ImportResult,
        val previous: DocumentEntity? = null,
        val previousQueue: OcrQueueEntity? = null,
        val queuedOcr: Boolean = false,
    )

    private suspend fun place(
        tmpPath: String,
        hash: String,
        size: Long,
        suggestedName: String,
        toDir: String,
    ): Placement {
        val existing = documentDao.getByDocId(hash)
        // The document's own path, when its bytes are there. This runs under the writer lock, so
        // re-importing a known, unchanged PDF must read no library file.
        val inPlace = existing?.takeIf { fs.holdsDocument(it, size) }?.relPath
        if (existing != null && existing.isLive && inPlace != null) {
            return Placement(ImportResult.Duplicate(existing.docId, existing.relPath))
        }

        val target = inPlace ?: chooseImportTarget(ensurePdfName(suggestedName), toDir).also { fs.move(tmpPath, it) }
        val meta = fs.stat(target) ?: error("Imported file vanished: $target")
        val now = clock.nowMillis()
        val row = existing?.backAt(target, meta) ?: newDocument(hash, target, meta, now)
        // A returning document whose text is indexed already has nothing for OCR to redo.
        val queueOcr = OcrState.fromColumn(row.ocrState) !in INDEXED
        val previousQueue = existing?.let { ocrQueueDao.getByDocId(hash) }
        // The target was free on disk; a hidden row still naming it gives it up.
        documentDao.upsert(if (queueOcr) row.copy(ocrState = OcrState.PENDING.column) else row)
        if (queueOcr) ocrQueueDao.upsert(OcrQueueEntity(hash, state = QueueState.PENDING.column, updatedAt = now))
        if (existing != null && existing.fileName != row.fileName) indexFileName(row)
        return Placement(
            ImportResult.Imported(hash, target, broughtBack = existing != null),
            previous = existing,
            previousQueue = previousQueue,
            queuedOcr = queueOcr,
        )
    }

    private fun newDocument(
        hash: String,
        target: String,
        meta: FileMeta,
        now: Long,
    ) = DocumentEntity(
        docId = hash,
        relPath = target,
        fileName = NpnPaths.fileName(target),
        size = meta.size,
        mtime = meta.mtimeMillis,
        addedAt = now,
        ocrState = OcrState.PENDING.column,
    )

    /** This row, of a document the library did not show, as the live document whose file is [target]. */
    private fun DocumentEntity.backAt(
        target: String,
        meta: FileMeta,
    ) = copy(
        relPath = target,
        fileName = NpnPaths.fileName(target),
        size = meta.size,
        mtime = meta.mtimeMillis,
        trashed = false,
        trashedOrigPath = null,
        trashedAt = null,
        deleted = false,
        missing = false,
    )

    /**
     * Undo [placed] after recording its document's return failed. A new document goes as
     * [undoImport] takes one back. A returning one gets the row and OCR queue entry it had; its file
     * goes only while the trash still holds the bytes, and otherwise stays, the row pointing at it
     * with the lifecycle it had: a failed import never deletes the only copy of a document's bytes,
     * and a retry or a rescan finds them back where the row says.
     */
    private suspend fun takeBack(placed: Placement) {
        val imported = placed.result as ImportResult.Imported
        val previous = placed.previous ?: return undoImport(imported)
        serialize {
            val docId = imported.docId
            val placedAt = imported.relPath
            val row =
                when {
                    // Brought back in place, the file there before; or no copy of ours left there.
                    placedAt == previous.relPath || !fs.holds(placedAt, docId) -> {
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
            if (placed.queuedOcr) placed.previousQueue?.let { ocrQueueDao.upsert(it) } ?: ocrQueueDao.deleteRow(docId)
            if (row.fileName != NpnPaths.fileName(placedAt)) indexFileName(row)
        }
    }

    /**
     * Make search find [row] under its file name, which bringing a document back can change: a fold
     * rewrites a document's searchable columns only when it changes the row, and the row written
     * here already holds what a fold would project.
     */
    private suspend fun indexFileName(row: DocumentEntity) {
        val tags = db.tagDao().tagsForDocument(row.docId)
        val fields = db.documentFieldDao().fieldsForDocs(listOf(row.docId)).associate { it.fieldKey to it.fieldValue }
        db.ftsDao().upsertMetadata(listOf(FtsRows.row(row.docId, row.fileName, tags, fields, content = null)))
    }

    /** This row, or this row at its missing sentinel when another row holds its path now. */
    private suspend fun DocumentEntity.onFreePath(): DocumentEntity {
        val holder = documentDao.getByRelPath(relPath)?.docId
        return if (holder == null || holder == docId) this else copy(relPath = missingSentinel(docId))
    }

    /** Delete, best effort, what the trash holds of [docId], now that the document is back for good. */
    private suspend fun discardTrashCopies(docId: String) {
        log.runLogged("Removing the trashed copy of $docId") {
            for (path in trash.copiesOf(docId)) fs.delete(path)
        }
    }

    private fun freshTmpPath(): String = NpnPaths.Temp.importPath(Uuid.random().toString())

    private suspend fun cleanup(tmpPath: String) {
        // Best effort: a staging file left behind is never indexed (it lives under `.npn/`), and a
        // rescan sweeps it away once it is old (TempFileSweep).
        if (fs.exists(tmpPath)) runCatchingCancellable { fs.delete(tmpPath) }
    }

    private fun ensurePdfName(name: String): String {
        val sanitized = NpnPaths.sanitizeFileName(name.trim()).ifEmpty { "document" }
        return if (NpnPaths.isPdf(sanitized)) sanitized else "$sanitized.pdf"
    }

    private suspend fun chooseImportTarget(
        cleanName: String,
        toDir: String,
    ): String {
        val rule = ruleProvider()
        if (rule?.enabled != true || rule.template.isBlank()) {
            return uniqueTargetPath(toDir, cleanName)
        }
        val context =
            StoragePathContext(
                fields = emptyMap(),
                tags = emptyList(),
                originalFileName = cleanName,
                fieldTypes = fieldTypes(),
            )
        val expanded = StoragePathTemplate.expand(rule.template, context).pathOrNull
        return if (expanded.isNullOrBlank()) {
            uniqueTargetPath(toDir, cleanName)
        } else {
            resolveUniqueTarget(fs, documentDao, currentDocId = null, target = expanded)
        }
    }

    /** Build `dir/name.pdf`, adding " (2)", " (3)", ... before the extension until free. */
    private suspend fun uniqueTargetPath(
        dir: String,
        name: String,
    ): String {
        val normDir = NpnPaths.normalize(dir)
        val target = if (normDir.isEmpty()) name else "$normDir/$name"
        return uniquifyPath(target, startIndex = 2, suffix = { " ($it)" }, exists = fs::exists)
    }

    private companion object {
        /** The OCR states of a document whose text is indexed. */
        val INDEXED = setOf(OcrState.DONE, OcrState.ADOPTED)
    }
}
