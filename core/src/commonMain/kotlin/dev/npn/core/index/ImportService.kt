package dev.npn.core.index

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.OcrQueueDao
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.uniquifyPath
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.model.FieldType
import dev.npn.core.ocr.OcrPipeline
import dev.npn.core.pathrules.StoragePathContext
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pathrules.StoragePathTemplate
import dev.npn.core.pathrules.pathOrNull
import dev.npn.core.pathrules.resolveUniqueTarget
import dev.npn.core.util.runCatchingCancellable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

sealed interface ImportResult {
    data class Imported(
        val docId: String,
        val relPath: String,
    ) : ImportResult

    /** An identical (by hash) non-deleted document already exists; nothing was added. */
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
 * The dedup check, the move into place and the row write run as one step through [serialize],
 * which the session points at the journal coordinator's lock. Without it two imports of the same
 * bytes both pass the check and place two files for one hash, and a rescan listing the library
 * between the move and the row write inserts a row of its own that the import then overwrites
 * (or that overwrites the import's).
 *
 * Bytes the journal had deleted or trashed come back through [restore], which records that they
 * are back (`JournalCoordinator.restoreReturned`); without that record, the next fold would hide
 * the document again.
 */
@OptIn(ExperimentalUuidApi::class)
class ImportService(
    private val fs: LibraryFileSystem,
    private val documentDao: DocumentDao,
    private val ocrQueueDao: OcrQueueDao,
    private val clock: EpochClock = SystemEpochClock,
    private val ruleProvider: suspend () -> StoragePathRule? = { null },
    /** The registry's field types by key, which decide where a template accepts a date pattern. */
    private val fieldTypes: suspend () -> Map<String, FieldType>,
    private val serialize: suspend (suspend () -> Unit) -> Unit = { it() },
    /** Record that the document is back at the path, when the journal still hides it. */
    private val restore: suspend (docId: String, relPath: String) -> Unit = { _, _ -> },
) {
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
        // An empty file is never a document; its hash would claim the empty-content identity.
        if (fs.stat(tmpPath)?.size == 0L) throw EmptyImportException()
        val hash = fs.sha256(tmpPath)
        var result: ImportResult? = null
        serialize { result = place(tmpPath, hash, suggestedName, toDir) }
        val placed = checkNotNull(result)
        // After [serialize]: the restore is a journal write, which takes the same lock.
        if (placed is ImportResult.Imported) {
            runCatchingCancellable { restore(placed.docId, placed.relPath) }.onFailure { failure ->
                undoImport(placed)
                throw failure
            }
        }
        return placed
    }

    /**
     * Take back an import this service just made, for a caller whose larger operation failed after
     * it: a split whose later part could not be made. An import writes no journal event beyond the
     * restore of bytes the journal had hidden, so taking it back is removing the file and its rows,
     * not a trash or a tombstone; a tombstone would hide the same bytes when the caller retries and
     * makes the same part again. Leaves alone a document that no longer sits where the import put
     * it.
     */
    suspend fun undoImport(imported: ImportResult.Imported) =
        serialize {
            val row = documentDao.getByDocId(imported.docId)?.takeIf { it.relPath == imported.relPath }
            if (row == null || row.deleted || row.trashed) return@serialize
            if (fs.exists(imported.relPath)) fs.delete(imported.relPath)
            documentDao.delete(imported.docId)
            ocrQueueDao.delete(imported.docId)
        }

    private suspend fun place(
        tmpPath: String,
        hash: String,
        suggestedName: String,
        toDir: String,
    ): ImportResult {
        // Dedup: known, non-deleted hash -> duplicate.
        val existing = documentDao.getByDocId(hash)
        if (existing != null && !existing.deleted) {
            return ImportResult.Duplicate(existing.docId, existing.relPath)
        }

        val cleanName = ensurePdfName(suggestedName)
        val target = chooseImportTarget(cleanName, toDir)
        fs.move(tmpPath, target)

        val meta = fs.stat(target) ?: error("Imported file vanished: $target")
        val now = clock.nowMillis()
        documentDao.upsert(
            DocumentEntity(
                docId = hash,
                relPath = target,
                fileName = target.substringAfterLast('/'),
                size = meta.size,
                mtime = meta.mtimeMillis,
                addedAt = now,
                ocrState = OcrPipeline.OCR_PENDING,
            ),
        )
        ocrQueueDao.upsert(OcrQueueEntity(docId = hash, state = OcrPipeline.STATE_PENDING, updatedAt = now))
        return ImportResult.Imported(hash, target)
    }

    private fun freshTmpPath(): String = NpnPaths.importStagingPath(Uuid.random().toString())

    private suspend fun cleanup(tmpPath: String) {
        // Best effort: a staging file left behind is never indexed (it lives under `.npn/`).
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
}
