package dev.npn.core.xmp

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The PDF cannot carry baked metadata, or the rewrite did not verify; the library file is untouched. */
class BakeRefusedException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Outcome of [BakeService.bakeInPlace]. */
data class BakeResult(
    val oldSha256: String,
    val newSha256: String,
    val finalRelPath: String,
)

/**
 * Orchestrates XMP baking on top of a [LibraryFileSystem] and a [PdfXmpIO].
 *
 * Two flows (PLAN section 2, "XMP baking"):
 * - [bakeToBytes]: export/share path. Produces baked bytes; the library file is never touched.
 * - [bakeInPlace]: explicit, user-invoked rewrite of the library file, followed by a relink so
 *   document identity (the sha256) follows the new bytes.
 *
 * This class holds no journal dependency. The relink is emitted through the [emitRelink]
 * lambda supplied by the caller, which is the decoupling point: the caller wires it to
 * [dev.npn.core.index.RelinkService] (a `relink` [dev.npn.core.journal.JournalOp]) without this
 * package importing the journal.
 */
class BakeService(
    private val fs: LibraryFileSystem,
    private val io: PdfXmpIO,
    private val log: NpnLog = NpnLog.None,
) {
    /**
     * Bake [data] into a copy of the library file at [relPath] and return the new bytes.
     * The library file is not modified. The document id, when present in [data], is written
     * verbatim (callers typically set it to the current sha256).
     */
    suspend fun bakeToBytes(
        relPath: String,
        data: NpnXmpData,
    ): ByteArray = bake(fs.readBytes(relPath), data)

    /** Merge [data] into the XMP already in [src] and write it, with the keywords and title, into a copy. */
    private suspend fun bake(
        src: ByteArray,
        data: NpnXmpData,
    ): ByteArray {
        val effective = XmpMerger.merge(io.readXmp(src), data)
        return io.writeBaked(src, XmpSerializer.serialize(effective), data.tags, effective.title)
    }

    /**
     * Rewrite the library file at [relPath] with baked metadata, then emit a relink from the
     * old sha256 to the new one.
     *
     * Sequence: read old bytes and old sha -> produce baked bytes -> write them to a sibling
     * temp file -> re-read the temp and verify it parses and carries our metadata -> delete the
     * original -> move the temp into the original path -> compute the new sha -> [emitRelink]
     * (skipped when the bytes came out identical).
     *
     * Atomicity compromise: [LibraryFileSystem.move] refuses to overwrite an existing target
     * (fail-if-exists, required for the write-once invariant elsewhere), and SAF offers no atomic
     * replace. So the final step is delete-then-move, leaving a small window where the original
     * path does not exist. If the move fails, the original bytes, still in memory, are written
     * back and the failure rethrown: the document keeps its hash, so no relink is owed. A crash
     * inside that window leaves the temp file (`*.npnbake.tmp`, the baked bytes) next to the
     * missing original as recovery material.
     *
     * A PDF that cannot carry the metadata, or a rewrite that does not verify, throws
     * [BakeRefusedException]: the temp file is removed and the original is left untouched.
     */
    suspend fun bakeInPlace(
        relPath: String,
        data: NpnXmpData,
        emitRelink: suspend (oldSha256: String, newSha256: String) -> Unit,
    ): BakeResult {
        val norm = NpnPaths.normalize(relPath)
        val oldSha = fs.sha256(norm)

        val src = fs.readBytes(norm)
        // Ensure documentId reflects the pre-bake identity when the caller did not set one.
        val effectiveData = if (data.documentId == null) data.copy(documentId = oldSha) else data
        val bakedBytes =
            runCatchingCancellable { bake(src, effectiveData) }.getOrElse {
                throw BakeRefusedException("$norm cannot carry baked metadata", it)
            }

        val tmpPath = NpnPaths.Temp.bakePath(norm)
        // Clean any stale temp from a prior interrupted bake so writeBytesOnce does not fail.
        if (fs.exists(tmpPath)) fs.delete(tmpPath)
        fs.writeBytesOnce(tmpPath, bakedBytes)

        // Verify by re-reading the temp file (round-trips through the real filesystem).
        val verified = runCatchingCancellable { carriesNpnMetadata(fs.readBytes(tmpPath)) }.getOrDefault(false)
        if (!verified) {
            // Left behind, the next bake of this document removes it first.
            log.runLogged("Removing the unverified bake of $norm") { fs.delete(tmpPath) }
            throw BakeRefusedException("Bake verification failed for $norm; original left untouched")
        }

        // Non-atomic replace: delete original, then move temp into place.
        fs.delete(norm)
        var moved = false
        try {
            fs.move(tmpPath, norm)
            moved = true
        } finally {
            if (!moved) restoreOriginal(norm, src, oldSha, tmpPath)
        }

        val newSha = fs.sha256(norm)
        if (newSha != oldSha) emitRelink(oldSha, newSha)
        return BakeResult(oldSha256 = oldSha, newSha256 = newSha, finalRelPath = norm)
    }

    /** Whether [pdfBytes] carry recognizable npn metadata: a `npn:documentId` or at least one `dc:subject` tag. */
    private suspend fun carriesNpnMetadata(pdfBytes: ByteArray): Boolean {
        val data = XmpParser.parse(io.readXmp(pdfBytes) ?: return false)
        return data.documentId != null || data.tags.isNotEmpty()
    }

    /**
     * Put the original bytes [src] back at [norm] after the final move failed, so the document
     * keeps its hash [oldSha]. Runs even when the bake was cancelled. The baked temp is removed
     * only once the original is back; until then it is the recovery material. The caller still
     * sees the move's failure; a restore that fails too is only logged.
     */
    private suspend fun restoreOriginal(
        norm: String,
        src: ByteArray,
        oldSha: String,
        tmpPath: String,
    ) {
        withContext(NonCancellable) {
            log.runLogged("Restoring $norm after a failed bake (baked bytes kept)") {
                // A move that reported failure may still have landed the baked bytes.
                if (fs.exists(norm) && fs.sha256(norm) != oldSha) fs.delete(norm)
                if (!fs.exists(norm)) fs.writeBytesOnce(norm, src)
                if (fs.exists(tmpPath)) fs.delete(tmpPath)
            }
        }
    }
}
