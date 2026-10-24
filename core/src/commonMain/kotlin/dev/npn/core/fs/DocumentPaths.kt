package dev.npn.core.fs

import dev.npn.core.util.IdSource
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Applies journaled PDF locations. A document is moved, not copied: its file is claimed under a
 * recovery name, checked once, then moved to the target without replacing anything there. A move
 * that fails puts the file back; one interrupted by a crash leaves it under its recovery name, an
 * ordinary indexable PDF, never an invisible orphan.
 */
internal class DocumentPaths(
    private val fs: LibraryFileSystem,
    private val ids: IdSource = IdSource.Random,
) {
    /**
     * Where a document's file is once [settle] moved it toward its intended path: [path], and
     * [stat], the stat of the bytes checked as the document's there, which a row records as its
     * proof; null when the file stayed where it was.
     */
    class Settled(
        val path: String,
        val stat: FileMeta?,
    )

    /** Move [docId]'s file from [source] to [target] where it can; returns where it is now. */
    suspend fun reconcile(
        docId: String,
        source: String,
        target: String,
    ): String = settle(docId, source, target).path

    /**
     * Move [docId]'s file from [source] to [target] where it can, and say where it is now with the
     * stat of the bytes the move checked: taken before the move, of the file it moved, never of the
     * target afterwards, which by then may be another file a sync tool put there.
     */
    suspend fun settle(
        docId: String,
        source: String,
        target: String,
    ): Settled {
        if (source == target || !NpnPaths.isDocumentPath(target) || !fs.supportsDocumentRelocation) {
            return Settled(source, null)
        }
        val placed = if (fs.exists(target)) alreadyPlaced(docId, source, target) else relocate(docId, source, target)
        return if (placed != null) Settled(target, placed) else Settled(source, null)
    }

    /**
     * The stat of [target], which exists, when it already holds [docId]'s bytes, as a file other than
     * [source], and kept that stat while it was hashed; null otherwise.
     *
     * The copy at [source] stays, a duplicate the next rescan lists. Most often it is a move another
     * device made, whose deletion of [source] the sync tool is still bringing. Deleted here, it could
     * be the last copy: a device whose intent for the document is still another path deletes
     * [target] for the same reason at the same time, and the sync tool carries each deletion to the
     * other.
     */
    private suspend fun alreadyPlaced(
        docId: String,
        source: String,
        target: String,
    ): FileMeta? {
        val checked = fs.stat(target)?.takeIf { !it.isDirectory } ?: return null
        if (fs.sha256(target) != docId || fs.stat(target) != checked) return null
        if (fs.exists(source) && fs.sameFile(source, target)) return null
        return checked
    }

    /**
     * Move [source]'s bytes to [target], returning the stat they had under their recovery name, which
     * a move within a volume keeps (a copy to another volume does not, and the next rescan hashes the
     * file); null, with the file back at [source], when they are not [docId]'s.
     */
    private suspend fun relocate(
        docId: String,
        source: String,
        target: String,
    ): FileMeta? {
        if (!fs.exists(source)) return null
        // Claim the source name before checking identity: the bytes checked are then the bytes
        // moved, and an unrelated PDF an external writer installs at the old name is never touched.
        val recovery = claim(source)
        try {
            if (fs.sha256(recovery) != docId) return null
            // The recovery name is this device's alone: nothing else writes the file under it.
            val checked = fs.stat(recovery) ?: return null
            place(docId, recovery, target)
            return checked.copy(relPath = target, name = NpnPaths.fileName(target))
        } finally {
            // Cancelled or not, the document's file goes back to its name.
            withContext(NonCancellable) {
                if (fs.exists(recovery) && !fs.exists(source)) fs.claimDocument(recovery, source)
            }
        }
    }

    /**
     * Put the checked file at [recovery] in place as [target], never replacing what is there. A
     * link or no-replace rename moves it within a volume; a target on another volume gets a
     * verified copy instead, after which the recovery name is let go.
     */
    private suspend fun place(
        docId: String,
        recovery: String,
        target: String,
    ) {
        runCatchingCancellable { fs.move(recovery, target) }.onFailure { failure ->
            // Taken meanwhile: leave it alone, and the caller puts the source back.
            if (fs.exists(target)) throw failure
            val staging = NpnPaths.Temp.relocationPath(target, ids.next())
            fs.publishStagedOnce(staging, target, docId) { fs.copyOnce(recovery, staging) }
            fs.delete(recovery)
        }
    }

    /** Atomically take [source]'s name away under a fresh recovery name in the same folder. */
    private suspend fun claim(source: String): String =
        NpnPaths.Temp.recoveryPath(beside = source, nonce = ids.next()).also { fs.claimDocument(source, it) }
}
