package dev.npn.core.xmp

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths

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
 * lambda supplied by the caller, which is the decoupling point: the caller wires it to a
 * `SegmentWriter` (a `relink` [dev.npn.core.journal.JournalOp]) without this module importing
 * the journal package.
 */
class BakeService(
    private val fs: LibraryFileSystem,
    private val io: PdfXmpIO,
) {

    /** Suffix for the sibling temp file written during [bakeInPlace]. */
    private val tmpSuffix = ".npnbake.tmp"

    /**
     * Bake [data] into a copy of the library file at [relPath] and return the new bytes.
     * The library file is not modified. The document id, when present in [data], is written
     * verbatim (callers typically set it to the current sha256).
     */
    suspend fun bakeToBytes(relPath: String, data: NpnXmpData): ByteArray {
        val src = fs.readBytes(relPath)
        val existing = io.readXmp(src)
        val xmp = XmpMerger.merge(existing, data)
        val effectiveTitle = XmpParser.parse(xmp).title
        return io.writeBaked(src, xmp, data.tags, effectiveTitle)
    }

    /**
     * Rewrite the library file at [relPath] with baked metadata, then emit a relink from the
     * old sha256 to the new one.
     *
     * Sequence: read old bytes and old sha -> produce baked bytes -> write them to a sibling
     * temp file -> re-read the temp and verify it parses and carries our metadata -> delete the
     * original -> move the temp into the original path -> compute the new sha -> [emitRelink].
     *
     * Atomicity compromise (documented, v1): [LibraryFileSystem.move] refuses to overwrite an
     * existing target (fail-if-exists, required for the write-once invariant elsewhere), and
     * SAF offers no atomic replace. So the final step is delete-then-move, leaving a small
     * non-atomic window where the original path does not exist. If the move fails after the
     * delete, this attempts to restore the original bytes from the still-present temp file and
     * rethrows. A crash inside that window leaves the temp file (`*.npnbake.tmp`) as recovery
     * material next to the missing original. This is the pragmatic approach given SAF; a future
     * version can add a platform atomic-replace primitive.
     *
     * On verification failure the temp file is removed and the original is left untouched.
     */
    suspend fun bakeInPlace(
        relPath: String,
        data: NpnXmpData,
        emitRelink: suspend (oldSha256: String, newSha256: String) -> Unit,
    ): BakeResult {
        val norm = NpnPaths.normalize(relPath)
        val oldSha = fs.sha256(norm)

        val src = fs.readBytes(norm)
        val existing = io.readXmp(src)
        // Ensure documentId reflects the pre-bake identity when the caller did not set one.
        val effectiveData = if (data.documentId == null) data.copy(documentId = oldSha) else data
        val xmp = XmpMerger.merge(existing, effectiveData)
        val effectiveTitle = XmpParser.parse(xmp).title
        val bakedBytes = io.writeBaked(src, xmp, effectiveData.tags, effectiveTitle)

        val tmpPath = norm + tmpSuffix
        // Clean any stale temp from a prior interrupted bake so writeBytesOnce does not fail.
        if (fs.exists(tmpPath)) fs.delete(tmpPath)
        fs.writeBytesOnce(tmpPath, bakedBytes)

        // Verify by re-reading the temp file (round-trips through the real filesystem).
        val verified = try {
            val readBack = fs.readBytes(tmpPath)
            io.verify(readBack)
        } catch (_: Throwable) {
            false
        }
        if (!verified) {
            runCatching { fs.delete(tmpPath) }
            error("Bake verification failed for $norm; original left untouched")
        }

        // Non-atomic replace: delete original, then move temp into place.
        fs.delete(norm)
        try {
            fs.move(tmpPath, norm)
        } catch (e: Throwable) {
            // Restore from the temp file, which still holds the baked bytes.
            runCatching {
                val recovered = fs.readBytes(tmpPath)
                if (!fs.exists(norm)) fs.writeBytesOnce(norm, recovered)
            }
            runCatching { fs.delete(tmpPath) }
            throw e
        }

        val newSha = fs.sha256(norm)
        emitRelink(oldSha, newSha)
        return BakeResult(oldSha256 = oldSha, newSha256 = newSha, finalRelPath = norm)
    }
}
