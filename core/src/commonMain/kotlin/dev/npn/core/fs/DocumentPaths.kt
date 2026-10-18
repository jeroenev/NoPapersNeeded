package dev.npn.core.fs

import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Applies journaled PDF locations. A document is moved, not copied: its file is claimed under a
 * recovery name, checked once, then moved to the target without replacing anything there. A move
 * that fails puts the file back; one interrupted by a crash leaves it under its recovery name, an
 * ordinary indexable PDF, never an invisible orphan.
 */
internal class DocumentPaths(
    private val fs: LibraryFileSystem,
) {
    suspend fun reconcile(
        docId: String,
        source: String,
        target: String,
    ): String {
        if (source == target || !NpnPaths.isDocumentPath(target) || !fs.supportsDocumentRelocation) return source
        val placed = if (fs.exists(target)) alreadyPlaced(docId, source, target) else relocate(docId, source, target)
        return if (placed) target else source
    }

    /**
     * Whether [target], which exists, already holds [docId]'s bytes, as a file other than [source].
     * The copy at [source] is then retired.
     */
    private suspend fun alreadyPlaced(
        docId: String,
        source: String,
        target: String,
    ): Boolean {
        if (fs.stat(target)?.isDirectory != false || fs.sha256(target) != docId) return false
        if (!fs.exists(source)) return true
        if (fs.sameFile(source, target)) return false
        retireSource(docId, source, target)
        return true
    }

    /** Move [source]'s bytes to [target]; false, with the file back at [source], when they are not [docId]'s. */
    private suspend fun relocate(
        docId: String,
        source: String,
        target: String,
    ): Boolean {
        if (!fs.exists(source)) return false
        // Claim the source name before checking identity: the bytes checked are then the bytes
        // moved, and an unrelated PDF an external writer installs at the old name is never touched.
        val recovery = claim(source)
        try {
            if (fs.sha256(recovery) != docId) return false
            place(docId, recovery, target)
            return true
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
            val staging = NpnPaths.Temp.relocationPath(target, Uuid.random().toString())
            fs.publishStagedOnce(staging, target, docId) { fs.copyOnce(recovery, staging) }
            fs.delete(recovery)
        }
    }

    /** Delete the extra copy at [source] of bytes that already sit at [target]. */
    private suspend fun retireSource(
        docId: String,
        source: String,
        target: String,
    ) {
        // Claim before checking, as in relocate: hash-then-delete at the old pathname could unlink
        // an unrelated PDF installed by an external writer between those operations.
        val recovery = claim(source)
        try {
            if (fs.sha256(recovery) == docId && fs.exists(target) && fs.sha256(target) == docId) {
                fs.delete(recovery)
            }
        } finally {
            // Cancelled or not, the document's file goes back to its name.
            withContext(NonCancellable) {
                if (fs.exists(recovery) && !fs.exists(source)) fs.claimDocument(recovery, source)
            }
        }
    }

    /** Atomically take [source]'s name away under a fresh recovery name in the same folder. */
    private suspend fun claim(source: String): String =
        NpnPaths.Temp.recoveryPath(beside = source).also { fs.claimDocument(source, it) }
}
