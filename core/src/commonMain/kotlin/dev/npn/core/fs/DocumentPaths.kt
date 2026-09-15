package dev.npn.core.fs

import kotlin.uuid.Uuid

/** Applies journaled PDF locations. A failed or interrupted publication leaves the source intact. */
internal class DocumentPaths(
    private val fs: LibraryFileSystem,
) {
    suspend fun reconcile(
        docId: String,
        source: String,
        target: String,
    ): String {
        if (source == target || !isDocumentPath(target) || !fs.supportsDocumentRelocation) return source
        if (fs.exists(target)) {
            if (fs.stat(target)?.isDirectory != false || fs.sha256(target) != docId) return source
            if (fs.exists(source) && fs.sameFile(source, target)) return source
        } else {
            if (!fs.exists(source) || fs.sha256(source) != docId) return source
            val staging = "$target.npn-${Uuid.random()}.tmp"
            try {
                fs.copyOnce(source, staging)
                fs.publishVerifiedObjectOnce(staging, target, docId)
            } finally {
                if (fs.exists(staging)) fs.delete(staging)
            }
        }
        check(fs.sha256(target) == docId) { "Published document hash mismatch: $target" }
        if (fs.exists(source)) retireSource(docId, source, target)
        return target
    }

    private suspend fun retireSource(
        docId: String,
        source: String,
        target: String,
    ) {
        // Claim the source name before checking identity. Hash-then-delete at the old pathname
        // could unlink an unrelated PDF installed by an external writer between those operations.
        // A crash leaves an ordinary, indexable recovery PDF, never an invisible orphan.
        val directory = source.substringBeforeLast('/', "")
        val name = "npn-recovery-${Uuid.random()}.pdf"
        val recovery = if (directory.isEmpty()) name else "$directory/$name"
        fs.claimDocument(source, recovery)
        try {
            if (fs.sha256(recovery) == docId && fs.exists(target) && fs.sha256(target) == docId) {
                fs.delete(recovery)
            }
        } finally {
            if (fs.exists(recovery) && !fs.exists(source)) fs.claimDocument(recovery, source)
        }
    }

    companion object {
        fun isDocumentPath(path: String): Boolean =
            NpnPaths.isSafe(path) && NpnPaths.normalize(path) == path &&
                NpnPaths.indexable(FileMeta(path, path.substringAfterLast('/'), 0, 0, false))
    }
}
