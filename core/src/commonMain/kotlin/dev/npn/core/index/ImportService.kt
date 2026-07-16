package dev.npn.core.index

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.OcrQueueDao
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.SystemEpochClock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Outcome of an import attempt. */
sealed interface ImportResult {
    /** The file was imported. */
    data class Imported(val docId: String, val relPath: String) : ImportResult

    /** An identical (by hash) non-deleted document already exists; nothing was added. */
    data class Duplicate(val existingDocId: String, val existingPath: String) : ImportResult
}

/**
 * Imports external PDFs (or byte payloads) into the library (PLAN.md sec. 5, "Scanning" /
 * "Import always dedups").
 *
 * Because [LibraryFileSystem.sha256] hashes a library-relative path (there is no stream-hash of an
 * [ExternalSource]), imports stage into `.npn/tmp/` first, hash there, dedup-check, then move into
 * place or delete. `.npn/tmp/` is under `.npn/` so it is excluded from indexing. It is cleaned on
 * both success and failure.
 */
@OptIn(ExperimentalUuidApi::class)
class ImportService(
    private val fs: LibraryFileSystem,
    private val documentDao: DocumentDao,
    private val ocrQueueDao: OcrQueueDao,
    private val clock: EpochClock = SystemEpochClock,
) {
    /** Temp staging dir, inside `.npn/` so it is never indexed. Defined here, not in NpnPaths. */
    private val tmpDir = "${NpnPaths.NPN_DIR}/tmp"

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
        val target = uniqueTargetPath(toDir, cleanName)
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
                ocrState = "pending",
            ),
        )
        ocrQueueDao.upsert(OcrQueueEntity(docId = hash, state = "pending", updatedAt = now))
        return ImportResult.Imported(hash, target)
    }

    private fun freshTmpPath(): String = "$tmpDir/${Uuid.random()}.part"

    private suspend fun cleanup(tmpPath: String) {
        if (fs.exists(tmpPath)) {
            runCatching { fs.delete(tmpPath) }
        }
    }

    private fun ensurePdfName(name: String): String {
        val trimmed = name.trim().ifEmpty { "document" }
        return if (NpnPaths.isPdf(trimmed)) trimmed else "$trimmed.pdf"
    }

    /** Build `dir/name.pdf`, adding " (2)", " (3)", ... before the extension until free. */
    private suspend fun uniqueTargetPath(dir: String, name: String): String {
        val normDir = NpnPaths.normalize(dir)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        fun path(candidate: String) = if (normDir.isEmpty()) candidate else "$normDir/$candidate"
        var candidate = path(name)
        if (!fs.exists(candidate)) return candidate
        var i = 2
        while (true) {
            candidate = path("$base ($i)$ext")
            if (!fs.exists(candidate)) return candidate
            i++
        }
    }
}
