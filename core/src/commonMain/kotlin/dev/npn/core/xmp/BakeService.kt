package dev.npn.core.xmp

import dev.npn.core.fs.BakeAsides
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.hasStoredStat
import dev.npn.core.fs.holds
import dev.npn.core.fs.readDocument
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.util.IdSource
import dev.npn.core.util.NpnLog
import dev.npn.core.util.Sensitive.Kind.DOCUMENT
import dev.npn.core.util.Sensitive.Kind.FOLDER
import dev.npn.core.util.error
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import dev.npn.core.util.sensitive
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
    /**
     * Where a bake keeps its original during the swap, held there so that the session's rescans
     * leave the file to the bake ([dev.npn.core.index.Indexer.bakeAsides]).
     */
    private val bakeAsides: BakeAsides = BakeAsides(),
    /**
     * Whether the library shows the document whose id is this hash while no file at its row's path
     * holds its bytes: a leftover temporary file holding them may be its only copy
     * ([setAsideLeftover]). Answered by the caller, as the relink is, so this package reads no journal.
     */
    private val shownWithoutItsFile: suspend (String) -> Boolean = { false },
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
     * metadata -> re-stat the original -> move it aside, under a recovery name -> check it hashes to
     * [docId] -> move the temp into its path -> [emitRelink] (skipped when the bytes came out
     * identical) -> delete the original aside. The new sha256 is that of the verified bytes.
     *
     * The bake only replaces the bytes it read and proved to be the document's: another app or a
     * sync tool may write over the file at any point, so the file goes only while it still has the
     * size and mtime it had when it was read, and only once what was moved aside hashes to the
     * document, since the file can be replaced between that stat and the move. Otherwise the file
     * moved aside goes back, the temp is removed and nothing is swapped.
     *
     * Atomicity compromise: [LibraryFileSystem.move] refuses to overwrite an existing target
     * (fail-if-exists, required for the write-once invariant elsewhere), and that is all it offers:
     * there is no atomic replace. So the swap is move-aside-then-move, leaving a small window where the original path
     * does not exist; the original is under its recovery name (an ordinary, indexable PDF) and the
     * baked bytes in the temp file (`*.npnbake.tmp`) throughout, and a crash there leaves both. Until
     * the swap ends the recovery name is held in [bakeAsides]: a rescan of the session that lists
     * the original there leaves it to the bake, rather than move it on to the document's path while
     * the bake restores or deletes it. The
     * swap and the relink run to their end even when the bake is cancelled: cut short, the baked
     * bytes could be left in place without their relink. A failed swap is undone ([restoreOriginal])
     * and rethrown: the document keeps its hash, so no relink is owed.
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
        // Clear any stale temp from a prior interrupted bake so writeBytesOnce does not fail.
        if (fs.exists(tmpPath)) setAsideLeftover(norm, tmpPath)
        fs.writeBytesOnce(tmpPath, bakedBytes)

        // Verify by re-reading the temp file (round-trips through the real filesystem).
        val verified =
            runCatchingCancellable {
                val written = fs.readBytes(tmpPath)
                written.contentEquals(bakedBytes) && carriesNpnMetadata(written)
            }.getOrDefault(false)
        if (!verified) {
            // Left behind, the next bake of this document removes it first.
            log.runLogged("Removing the unverified bake of ${sensitive(norm, DOCUMENT)}") { fs.delete(tmpPath) }
            throw BakeRefusedException("Bake verification failed for $norm; original left untouched")
        }
        val newSha = sha256Bytes(bakedBytes)

        return withContext(NonCancellable) {
            // Only the file that was read goes: same size and mtime as then.
            if (fs.stat(norm) != read) {
                log.runLogged("Removing the bake of ${sensitive(norm, DOCUMENT)}, written over since it was read") {
                    fs.delete(tmpPath)
                }
                return@withContext null
            }
            // Non-atomic replace: move the original aside, then the temp into place. Aside, not
            // deleted: another process or a sync tool may have put its own file at the path since the
            // stat above, and only the document's own bytes may go. Kept under an ordinary recovery
            // name until the relink stands, so a crash in between loses nothing.
            val aside = NpnPaths.Temp.recoveryPath(beside = norm, nonce = ids.next())
            // Held from before the move aside until the original is back or deleted: a rescan
            // meanwhile sees the document under its recovery name, and must not move it on.
            bakeAsides.holding(aside) {
                var standing = false
                var movedAside = false
                try {
                    fs.move(norm, aside)
                    movedAside = true
                    if (!holdsWhatWasRead(aside, read, docId)) {
                        standing = true
                        putBack(norm, aside, tmpPath)
                        return@holding null
                    }
                    fs.move(tmpPath, norm)
                    if (newSha != docId) emitRelink(docId, newSha)
                    standing = true
                } finally {
                    @Suppress("SuspendFunInFinallySection") // The whole block runs in NonCancellable.
                    if (!standing) restoreOriginal(norm, docId, newSha, tmpPath, aside, movedAside)
                }
                log.runLogged("Removing the original of ${sensitive(norm, DOCUMENT)} after its bake") {
                    if (holdsWhatWasRead(aside, read, docId)) fs.delete(aside)
                }
                newSha
            }
        }
    }

    /**
     * Clear the temporary file [tmpPath] an earlier bake at [norm] left: one interrupted or undone
     * here, or another device's that a sync tool carried here, of this document or of another one
     * that had this path then. A bake undone after its relink landed parks the baked bytes there,
     * and they may be the only copy of the document they are, which the library shows: such a file
     * goes aside under a recovery name beside [norm], an ordinary one the next rescan indexes as that
     * document's file (seeds 4230199 and 4240158). Any other is a spare (a failed bake's output that
     * no relink names, or bytes the document's own file holds) and is deleted, so nothing the library
     * does not show turns up as a document.
     */
    private suspend fun setAsideLeftover(
        norm: String,
        tmpPath: String,
    ) {
        val held = runCatchingCancellable { fs.sha256(tmpPath) }.getOrNull()
        if (held == null || !shownWithoutItsFile(held)) {
            fs.delete(tmpPath)
            return
        }
        val aside = NpnPaths.Temp.recoveryPath(beside = norm, nonce = ids.next())
        fs.move(tmpPath, aside)
        log.warn(
            "${sensitive(tmpPath, DOCUMENT)} held the only copy of $held, which the library shows; " +
                "it is at ${sensitive(aside, DOCUMENT)} now",
        )
    }

    /**
     * Whether [path] holds the document [docId]'s bytes, as [read] (its file's stat when the bake
     * read it) still proves for a file a rename moved here ([hasStoredStat]); else by its hash.
     */
    private suspend fun holdsWhatWasRead(
        path: String,
        read: FileMeta,
        docId: String,
    ): Boolean {
        val now = fs.stat(path)?.takeIf { !it.isDirectory } ?: return false
        return now.hasStoredStat(read.size, read.mtimeMillis) || fs.holds(path, docId)
    }

    /**
     * The file moved aside from [norm] for a bake is not the document's: another process or a sync
     * tool wrote it there after the bake's last look. It goes back, and the bake's temp goes; where
     * the path was taken again meanwhile it stays under its recovery name, an ordinary indexable one.
     */
    private suspend fun putBack(
        norm: String,
        aside: String,
        tmpPath: String,
    ) {
        log.runLogged("Putting back the file at ${sensitive(norm, DOCUMENT)}, written over before its bake") {
            fs.move(aside, norm)
        }
        log.runLogged("Removing the bake of ${sensitive(norm, DOCUMENT)}, written over since it was read") {
            fs.delete(tmpPath)
        }
    }

    /** Whether [pdfBytes] carry recognizable npn metadata: a `npn:documentId` or at least one `dc:subject` tag. */
    private suspend fun carriesNpnMetadata(pdfBytes: ByteArray): Boolean {
        val data = XmpParser.parse(io.readXmp(pdfBytes) ?: return false)
        return data.documentId != null || data.tags.isNotEmpty()
    }

    /**
     * Move the original from [aside] back to [norm] when [asideHolds] it, and fail unless [norm] then
     * holds [oldSha]: with the move's own failure where the storage refused it, an I/O failure like
     * the swap's, rather than a failed check. A rename keeps the original's size and mtime, which its
     * row still stores. One that reports a failure may still have landed: where the bytes are
     * afterwards is what counts. One that succeeded put the original back: another hand that
     * replaces the file there since (a sync tool carrying another device's bake of the document,
     * seed 4230002) takes it from the document's path as it could at any later time, which leaves the
     * bake nothing to restore.
     */
    private suspend fun moveOriginalBack(
        norm: String,
        aside: String,
        oldSha: String,
        asideHolds: Boolean,
    ) {
        val back = if (asideHolds) runCatchingCancellable { fs.move(aside, norm) } else null
        if (back?.isSuccess == true || fs.holds(norm, oldSha)) return
        throw back?.exceptionOrNull() ?: IllegalStateException("$norm does not read back as its original")
    }

    /**
     * Put the original back at [norm] after the swap failed, so the document keeps its hash
     * [oldSha]. The original is at [norm] still (the move aside failed) or under [aside], an
     * ordinary recovery name, so one version is on disk at every step: baked bytes that a failed
     * move still landed at [norm] go back to [tmpPath] before the original moves back, and
     * [tmpPath] goes only once [norm] holds the original again (until then
     * [dev.npn.core.fs.TempFileSweep] keeps it, as it keeps any bake's temporary file whose bytes
     * are not at its document's path). The caller still sees the swap's failure; a restore that
     * fails too is logged with where the original is, which the next rescan indexes. One whose
     * swap never got to move the original ([movedAside] false) and finds it at neither place lost
     * nothing: another hand (the app's own path move, a sync tool, the user) took the file between
     * the bake's last look and its move, and that hand's rescan indexes it where it went.
     */
    private suspend fun restoreOriginal(
        norm: String,
        oldSha: String,
        bakedSha: String,
        tmpPath: String,
        aside: String,
        movedAside: Boolean,
    ) {
        suspend fun asideHolds() = runCatchingCancellable { fs.holds(aside, oldSha) }.getOrDefault(false)

        suspend fun normHolds() = runCatchingCancellable { fs.holds(norm, oldSha) }.getOrDefault(false)
        if (!movedAside && !normHolds() && !asideHolds()) {
            // The swap never moved the original: another hand took the file before it could, and
            // nothing of the document's is at the bake's path to restore. The baked bytes stay a
            // leftover the next bake of the document removes first.
            val document = sensitive(norm, DOCUMENT)
            val bakedAt = sensitive(tmpPath, DOCUMENT)
            log.warn("$document was moved away before its bake could swap it; the baked bytes stay at $bakedAt")
            return
        }
        runCatchingCancellable {
            if (!fs.holds(norm, oldSha)) {
                if (fs.exists(norm)) {
                    // A sync writer may have replaced the baked bytes while publication failed.
                    // Its file stays; the original stays under its recovery name.
                    if (!fs.holds(norm, bakedSha)) {
                        throw BakeDisplacedException("$norm holds another file; the original is at $aside")
                    }
                    // A move that reports a failure may still have landed, so where the bytes are
                    // afterwards is what counts.
                    val parked = runCatchingCancellable { fs.move(norm, tmpPath) }
                    if (fs.exists(norm)) {
                        throw IllegalStateException("$norm still holds the baked bytes", parked.exceptionOrNull())
                    }
                }
                moveOriginalBack(norm, aside, oldSha, asideHolds())
            }
        }.onSuccess {
            // The original is back: the baked bytes are a leftover now, which the next bake of the
            // document removes first if this cannot, and so is a copy of the original left aside.
            // Only while it is still there: another hand that replaced it since may have made the
            // baked bytes something else's last copy (a relink of them that landed before the failure).
            log.runLogged("Removing the baked bytes of ${sensitive(norm, DOCUMENT)} after its bake was undone") {
                if (fs.exists(tmpPath) && normHolds()) fs.delete(tmpPath)
                if (asideHolds()) fs.delete(aside)
            }
        }.onFailure { failure ->
            val message = "Restoring ${sensitive(norm, DOCUMENT)} after a failed bake failed"
            // The original under its recovery name, an ordinary one, is indexed by the next rescan:
            // put right, so a warning. Only an original on disk nowhere is an error.
            val kept =
                when {
                    normHolds() -> norm
                    asideHolds() -> aside
                    else -> null
                }
            val baked =
                if (runCatchingCancellable { fs.exists(tmpPath) }.getOrDefault(false)) {
                    ", the baked bytes at ${sensitive(tmpPath, DOCUMENT)}"
                } else {
                    ""
                }
            // The original is kept aside beside the document, so a folder deleted during the bake
            // (by the user, or a sync tool carrying such a delete) takes it as it takes the document:
            // nothing the delete did not ask for.
            val folder = NpnPaths.parentDir(norm)
            val folderGone = folder.isNotEmpty() && runCatchingCancellable { !fs.exists(folder) }.getOrDefault(false)
            when {
                kept != null -> {
                    val keptAt = sensitive(kept, DOCUMENT)
                    log.warn("$message; the original is at $keptAt, where the next rescan finds it$baked", failure)
                }

                // The failed read-back is no fault then, so it is not passed on as one.
                folderGone -> {
                    log.warn(
                        "$message: ${sensitive(folder, FOLDER)} was deleted meanwhile, and the original with it$baked",
                    )
                }

                else -> {
                    log.error("$message; the original is on disk nowhere$baked", failure)
                }
            }
        }
    }
}
