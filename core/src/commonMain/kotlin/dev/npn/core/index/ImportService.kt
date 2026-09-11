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
import dev.npn.core.ocr.OcrPipeline
import dev.npn.core.pathrules.StoragePathContext
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pathrules.StoragePathTemplate
import dev.npn.core.pathrules.resolveUniqueTarget
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

/**
 * Imports external PDFs or byte payloads into the library, deduplicating by content hash.
 *
 * Because [LibraryFileSystem.sha256] hashes a library-relative path (there is no stream-hash of an
 * [ExternalSource]), imports stage into [NpnPaths.TMP_DIR] first, hash there, dedup-check, then
 * move into place or delete. The staging file is cleaned on both success and failure.
 */
@OptIn(ExperimentalUuidApi::class)
class ImportService(
    private val fs: LibraryFileSystem,
    private val documentDao: DocumentDao,
    private val ocrQueueDao: OcrQueueDao,
    private val clock: EpochClock = SystemEpochClock,
    private val ruleProvider: suspend () -> StoragePathRule? = { null },
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
        val hash = fs.sha256(tmpPath)

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
        if (fs.exists(tmpPath)) {
            runCatching { fs.delete(tmpPath) }
        }
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
            StoragePathContext(fields = emptyMap(), tags = emptyList(), originalFileName = cleanName)
        val expanded = StoragePathTemplate.expand(rule.template, context).getOrNull()
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
