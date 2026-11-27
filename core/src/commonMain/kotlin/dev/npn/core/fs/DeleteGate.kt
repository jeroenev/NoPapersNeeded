package dev.npn.core.fs

import dev.npn.core.util.runCatchingCancellable

/**
 * What the journal says of a document, as far as deleting a copy of its bytes goes: answered under
 * the writer lock ([dev.npn.core.index.LockedJournal]), or by a fold the caller holds.
 */
internal interface DocumentFacts {
    /** Whether the journal has [docId] deleted for good. */
    suspend fun isDeleted(docId: String): Boolean

    /** Whether the journal has [docId] in the trash (and not deleted). */
    suspend fun isTrashed(docId: String): Boolean

    /** The document [docId]'s bytes became, its relink chain followed to the end; [docId] when none superseded it. */
    suspend fun successorOf(docId: String): String

    /** A journal with no facts: nothing deleted, trashed or superseded, so no fact of it lets a delete. */
    companion object None : DocumentFacts {
        override suspend fun isDeleted(docId: String): Boolean = false

        override suspend fun isTrashed(docId: String): Boolean = false

        override suspend fun successorOf(docId: String): String = docId
    }
}

/**
 * Why the app may delete a file holding a document's bytes. Every reason but [ExtraCopy] is a fact
 * of the journal, read from the [DocumentFacts] the caller holds when the gate acts, not when the
 * caller decided: a sync tool can bring the event that undoes it in between.
 */
internal sealed interface DeleteAuthority {
    /** The document is deleted for good: a delete for good, an import taken back, cloud sync's copy of one. */
    class Tombstone(
        val facts: DocumentFacts,
    ) : DeleteAuthority

    /** The document is in the trash, or deleted for good: the trash purge, which decides by its retention. */
    class Trash(
        val facts: DocumentFacts,
    ) : DeleteAuthority

    /** A relink superseded the document's bytes: a bake's original. */
    class Superseded(
        val facts: DocumentFacts,
    ) : DeleteAuthority

    /**
     * Another file, [kept], holds the same bytes, by [keptStat] while it is unchanged, else by its
     * hash: the file deleted is an extra copy, and the library keeps the document.
     */
    class ExtraCopy(
        val kept: String,
        val keptStat: FileMeta? = null,
    ) : DeleteAuthority
}

/**
 * The one way the app deletes a file holding a document's bytes from the library folder: the trash
 * purge, a delete for good, a duplicate's removal, an import taken back, a bake's original, and cloud
 * sync's copies of deleted or superseded documents. A sync tool carries a delete to every device,
 * so a delete with no fact behind it, one the deleting device inferred from its own view, is how the
 * library's last copy of a document is lost; every such loss the simulation found ended in one.
 *
 * A file goes only while its [DeleteAuthority] holds and it still holds the document's bytes: by
 * the stat it was proved with ([reap]'s `checked`) while that is unchanged, else by its hash. A
 * temporary file the app made itself and that holds no document is no copy, and is not deleted
 * through here.
 */
internal class DeleteGate(
    private val fs: LibraryFileSystem,
) {
    /**
     * Delete [path], a copy of the document [docId]'s bytes, for [authority]; [checked] is a stat it
     * had when it was proved to hold them. Returns whether it went: false, and the file left as it
     * is, when the authority does not hold, or the file is gone or holds other bytes now.
     */
    suspend fun reap(
        path: String,
        docId: String,
        authority: DeleteAuthority,
        checked: FileMeta? = null,
    ): Boolean {
        if (!allows(path, docId, authority) || !holds(path, docId, checked)) return false
        fs.delete(path)
        return true
    }

    /**
     * Delete the file at [path], a document path other processes and sync tools also write, as a copy
     * of [docId]'s bytes for [authority]: claimed first, by an exclusive rename to [aside] beside it
     * (a recovery name, an ordinary PDF a rescan indexes), so nothing that lands at [path] after the
     * check is what goes; then the claimed file is checked, by its hash, and deleted. A file that is
     * not the document's, or one the authority no longer covers, goes back to [path], or stays under
     * [aside] when [path] was taken again meanwhile. Returns whether the document's file went.
     */
    suspend fun reapClaimed(
        path: String,
        aside: String,
        docId: String,
        authority: DeleteAuthority,
    ): Boolean {
        if (fs.stat(path)?.isDirectory != false) return false
        fs.claimDocument(path, aside)
        if (reap(aside, docId, authority)) return true
        if (!fs.exists(path)) fs.claimDocument(aside, path)
        return false
    }

    private suspend fun allows(
        path: String,
        docId: String,
        authority: DeleteAuthority,
    ): Boolean =
        when (authority) {
            is DeleteAuthority.Tombstone -> {
                authority.facts.isDeleted(docId)
            }

            is DeleteAuthority.Trash -> {
                authority.facts.isTrashed(docId) || authority.facts.isDeleted(docId)
            }

            is DeleteAuthority.Superseded -> {
                authority.facts.successorOf(docId) != docId
            }

            is DeleteAuthority.ExtraCopy -> {
                NpnPaths.normalize(authority.kept) != NpnPaths.normalize(path) &&
                    holds(authority.kept, docId, authority.keptStat)
            }
        }

    /** Whether [path] holds [docId]'s bytes: by [checked] while its stat is unchanged, else by its hash. */
    private suspend fun holds(
        path: String,
        docId: String,
        checked: FileMeta?,
    ): Boolean {
        val now = fs.stat(path)?.takeIf { !it.isDirectory } ?: return false
        if (checked != null && now.hasStoredStat(checked.size, checked.mtimeMillis)) return true
        // A file that cannot be read now proves nothing, and stays.
        return runCatchingCancellable { fs.sha256(path) }.getOrNull() == docId
    }
}
