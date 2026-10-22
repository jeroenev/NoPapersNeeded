package dev.npn.core.xmp

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.holds
import dev.npn.core.fs.readDocument
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.util.IdSource
import dev.npn.core.util.NpnLog
import dev.npn.core.util.error
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The PDF cannot carry baked metadata, or the rewrite did not verify; the library file is untouched. */
internal class BakeRefusedException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Another process put its own file at a bake's path while the bake was undone: that file stays, and
 * the original is kept under a recovery name the next rescan indexes. A race, not a bug.
 */
internal class BakeDisplacedException(
    message: String,
) : IllegalStateException(message)

/**
 * Orchestrates XMP baking on top of a [LibraryFileSystem] and a [PdfXmpIO].
 *
 * Two flows:
 * - [bakeToBytes]: export/share path. Produces baked bytes; the library file is never touched.
 * - [bakeInPlace]: explicit, user-invoked rewrite of the library file, followed by a relink so
 *   document identity (the sha256) follows the new bytes.
 *
 * This class holds no journal dependency. The relink is emitted through the [emitRelink]
 * lambda supplied by the caller, which is the decoupling point: the caller wires it to
 * [dev.npn.core.app.BakeRelink] (a `relink` [dev.npn.core.journal.JournalOp]) without this package
 * importing the journal.
 */
internal class BakeService(
    private val fs: LibraryFileSystem,
    private val io: PdfXmpIO,
    private val log: NpnLog = NpnLog.None,
    private val ids: IdSource = IdSource.Random,
) {
    /**
     * Bake [data] into a copy of the document [docId]'s file at [relPath] and return the new bytes,
     * or null when the file there is not the document's: another file would leave carrying this
     * document's metadata. The library file is not modified. The document id, when present in
     * [data], is written verbatim (callers typically set it to [docId]).
     */
    suspend fun bakeToBytes(
        relPath: String,
        docId: String,
        data: NpnXmpData,
    ): ByteArray? = fs.readDocument(relPath, docId)?.let { bake(it, data) }

    /** Merge [data] into the XMP already in [src] and write it, with the keywords and title, into a copy. */
    private suspend fun bake(
        src: ByteArray,
        data: NpnXmpData,
    ): ByteArray {
        val effective = XmpMerger.merge(io.readXmp(src), data)
        return io.writeBaked(src, XmpSerializer.serialize(effective), data.tags, effective.title)
    }

    /**
     * Rewrite the library file at [relPath], the document [docId]'s, with baked metadata, then emit
     * a relink from [docId] to the new sha256. Returns null, with nothing written or relinked, when
     * the file is not the document's: its bytes do not hash to [docId], or it changed before the swap.
     *
     * Sequence: read the file once and check its hash -> produce baked bytes -> write them to a
     * sibling temp file -> re-read the temp and verify it is what was written and carries our
     * metadata -> re-stat the original -> delete it -> move the temp into its path -> [emitRelink]
     * (skipped when the bytes came out identical). The new sha256 is that of the verified bytes.
     *
     * The bake only replaces the bytes it read and proved to be the document's: another app or a
     * sync tool may write over the file at any point, so the file goes only while it still has the
     * size and mtime it had when it was read; otherwise the temp is removed and nothing is swapped.
     *
     * Atomicity compromise: [LibraryFileSystem.move] refuses to overwrite an existing target
     * (fail-if-exists, required for the write-once invariant elsewhere), and SAF offers no atomic
     * replace. So the swap is delete-then-move, leaving a small window where the original path does
     * not exist; the baked bytes are in the temp file (`*.npnbake.tmp`) throughout, and a crash
     * there leaves them as recovery material next to the missing original. The swap and the relink
     * run to their end even when the bake is cancelled: cut short, the baked bytes could be left in
     * place without their relink. A failed swap is undone ([restoreOriginal]) and rethrown: the
     * document keeps its hash, so no relink is owed.
     *
     * [emitRelink] must throw only when no relink was published: failures after publication are
     * recovered or logged by the caller, since the baked bytes then belong to their new identity.
     * A relink that cannot be published is undone as a failed swap is.
     *
     * A PDF that cannot carry the metadata, or a rewrite that does not verify, throws
     * [BakeRefusedException]: the temp file is removed and the original is left untouched.
     *
     * Returns the sha256 of the baked bytes now at [relPath], or null when the file there was not
     * the document's (or stopped being it before the swap) and nothing was baked.
     */
    suspend fun bakeInPlace(
        relPath: String,
        docId: String,
        data: NpnXmpData,
        emitRelink: suspend (oldSha256: String, newSha256: String) -> Unit,
    ): String? {
        val norm = NpnPaths.normalize(relPath)
        // Taken before the read, so a file written to during or after it no longer matches.
        val read = fs.stat(norm)?.takeIf { !it.isDirectory } ?: return null
        val src = fs.readDocument(norm, docId) ?: return null
        // Ensure documentId reflects the pre-bake identity when the caller did not set one.
        val effectiveData = if (data.documentId == null) data.copy(documentId = docId) else data
        val bakedBytes =
            runCatchingCancellable { bake(src, effectiveData) }.getOrElse {
                throw BakeRefusedException("$norm cannot carry baked metadata", it)
            }

        val tmpPath = NpnPaths.Temp.bakePath(norm)
        // Clean any stale temp from a prior interrupted bake so writeBytesOnce does not fail.
        if (fs.exists(tmpPath)) fs.delete(tmpPath)
        fs.writeBytesOnce(tmpPath, bakedBytes)

        // Verify by re-reading the temp file (round-trips through the real filesystem).
        val verified =
            runCatchingCancellable {
                val written = fs.readBytes(tmpPath)
                written.contentEquals(bakedBytes) && carriesNpnMetadata(written)
            }.getOrDefault(false)
        if (!verified) {
            // Left behind, the next bake of this document removes it first.
            log.runLogged("Removing the unverified bake of $norm") { fs.delete(tmpPath) }
            throw BakeRefusedException("Bake verification failed for $norm; original left untouched")
        }
        val newSha = sha256Bytes(bakedBytes)

        return withContext(NonCancellable) {
            // Only the file that was read goes: same size and mtime as then.
            if (fs.stat(norm) != read) {
                log.runLogged("Removing the bake of $norm, written over since it was read") { fs.delete(tmpPath) }
                return@withContext null
            }
            // Non-atomic replace: delete original, then move temp into place.
            var standing = false
            try {
                fs.delete(norm)
                fs.move(tmpPath, norm)
                if (newSha != docId) emitRelink(docId, newSha)
                standing = true
            } finally {
                @Suppress("SuspendFunInFinallySection") // The whole block runs in NonCancellable.
                if (!standing) restoreOriginal(norm, src, docId, newSha, tmpPath)
            }
            newSha
        }
    }

    /** Whether [pdfBytes] carry recognizable npn metadata: a `npn:documentId` or at least one `dc:subject` tag. */
    private suspend fun carriesNpnMetadata(pdfBytes: ByteArray): Boolean {
        val data = XmpParser.parse(io.readXmp(pdfBytes) ?: return false)
        return data.documentId != null || data.tags.isNotEmpty()
    }

    /**
     * Put the original bytes [src] back at [norm] after the swap failed, so the document keeps its
     * hash [oldSha]. One of the two versions stays on disk at every step: baked bytes that a failed
     * move still landed at [norm] go back to [tmpPath] before the original is written, and [tmpPath]
     * goes only once [norm] holds the original again (until then [dev.npn.core.fs.TempFileSweep]
     * keeps it, as it keeps any bake's temporary file whose bytes are not at its document's path).
     * The caller still sees the swap's failure; a restore that fails too is logged with where the
     * document's bytes are, and saves the original under a recovery name first ([keepOriginal])
     * whenever [norm] no longer holds them, so no run of failures loses them.
     */
    private suspend fun restoreOriginal(
        norm: String,
        src: ByteArray,
        oldSha: String,
        bakedSha: String,
        tmpPath: String,
    ) {
        var recovery: String? = null
        runCatchingCancellable {
            if (!fs.holds(norm, oldSha)) {
                if (fs.exists(norm)) {
                    // A sync writer may have replaced the baked bytes while publication failed.
                    // Its file stays; save the original under an ordinary, indexable recovery name.
                    if (!fs.holds(norm, bakedSha)) {
                        recovery = keepOriginal(norm, src)
                        throw BakeDisplacedException("$norm holds another file; the original is at $recovery")
                    }
                    // A move that reports a failure may still have landed, so where the bytes are
                    // afterwards is what counts.
                    val parked = runCatchingCancellable { fs.move(norm, tmpPath) }
                    if (fs.exists(norm)) {
                        // [norm] keeps the baked bytes and [writeBytesOnce] below cannot land: the
                        // original is saved before the failure is raised, as in the branch above.
                        recovery = runCatchingCancellable { keepOriginal(norm, src) }.getOrNull()
                        val saved = recovery?.let { "; the original is at $it" }.orEmpty()
                        throw IllegalStateException("$norm still holds the baked bytes$saved", parked.exceptionOrNull())
                    }
                }
                fs.writeBytesOnce(norm, src)
                check(fs.holds(norm, oldSha)) { "$norm does not read back as its original" }
            }
        }.onSuccess {
            // The original is back: the baked bytes are a leftover now, which the next bake of the
            // document removes first if this cannot.
            log.runLogged("Removing the baked bytes of $norm after its bake was undone") {
                if (fs.exists(tmpPath)) fs.delete(tmpPath)
            }
        }.onFailure { failure ->
            // Any failure here can leave the original nowhere but in memory: a later one saves it
            // under a recovery name too, before the log says where a reader would find it.
            if (recovery == null && runCatchingCancellable { !fs.holds(norm, oldSha) }.getOrDefault(true)) {
                recovery = runCatchingCancellable { keepOriginal(norm, src) }.getOrNull()
            }
            val where = onDisk(listOfNotNull(norm, tmpPath, recovery))
            val message = "Restoring $norm after a failed bake failed; the document's bytes are at: $where"
            // An original saved under its recovery name, an ordinary one, is indexed by the next
            // rescan: put right, so a warning. Only an original on disk nowhere is an error.
            val kept = recovery ?: norm.takeIf { runCatchingCancellable { fs.holds(norm, oldSha) }.getOrDefault(false) }
            if (kept != null) {
                log.warn("$message; the original is at $kept, where the next rescan finds it", failure)
            } else {
                log.error(message, failure)
            }
        }
    }

    private suspend fun keepOriginal(
        norm: String,
        src: ByteArray,
    ): String {
        val recovery = NpnPaths.Temp.recoveryPath(beside = norm, nonce = ids.next())
        fs.writeBytesOnce(recovery, src)
        return recovery
    }

    /** Which of [paths] hold a file, for a log line that says where a failed bake left a document. */
    private suspend fun onDisk(paths: List<String>): String =
        paths.filter { runCatchingCancellable { fs.exists(it) }.getOrDefault(false) }.joinToString().ifEmpty { "-" }
}
