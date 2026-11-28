package dev.npn.core.fs

import dev.npn.core.util.IdSource
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** One entry of the keep store: the document [sha]'s bytes, kept in the episode [episode], with its stat. */
internal data class KeepEntry(
    val sha: String,
    val episode: Long,
    val meta: FileMeta,
) {
    val path: String get() = meta.relPath
}

/**
 * The keep store, `.npn/keep/<xx>/<sha256>-<episode>.pdf` ([NpnPaths.Keep.path]): a copy of the bytes
 * of each document the app took from its path, which the journal alone never could keep. An entry
 * is a copy, never a state: whether its document is in the library is the journal's to say; the
 * folder then follows ([materialize], and the trash's own copy into it).
 *
 * Published as a journal object is: copied to a staging file beside the entry, its bytes verified,
 * and published without replacement ([publishStagedOnce]). So an entry never holds bytes other than
 * its name's, and a crash leaves at most a staging copy, which the temporary-file sweep removes. The
 * episode in its name is the time of the event that kept it, taken before that event: the purge
 * takes an entry only for an event at least as late ([dev.npn.core.app.TrashPurge]), so a later
 * trash's entry, on a device whose event has not arrived here yet, is never taken for an earlier
 * one's, as a name shared by every episode would be. Nothing deletes an entry but a delete for good
 * and the purge, through the [DeleteGate].
 *
 * Read by every build that has it; written only once every device that shares the library reads it
 * (the `keep-v1` capability), as an older build cannot restore a document whose bytes are only here.
 * Cloud sync does not carry it: each device keeps its own.
 */
internal class KeepStore(
    private val fs: LibraryFileSystem,
    /** This installation's id, which names its staging files apart from another device's. */
    private val installationId: String,
    private val ids: IdSource = IdSource.Random,
) {
    private val gate = DeleteGate(fs)

    /** The latest entry holding [sha]'s bytes, or null when the store has none. */
    suspend fun entry(sha: String): KeepEntry? = entriesOf(sha).maxByOrNull { it.episode }

    /** Every entry holding [sha]'s bytes. */
    suspend fun entriesOf(sha: String): List<KeepEntry> = listed(NpnPaths.Keep.dir(sha)).filter { it.sha == sha }

    /** Every entry the store holds. */
    suspend fun entries(): List<KeepEntry> = listed(NpnPaths.KEEP_DIR)

    private suspend fun listed(dir: String): List<KeepEntry> =
        runCatchingCancellable { fs.listAllUnder(dir) }
            .getOrDefault(emptyList())
            .filter { !it.isDirectory }
            .mapNotNull { meta ->
                NpnPaths.Keep.parse(meta.relPath)?.let { (sha, episode) -> KeepEntry(sha, episode, meta) }
            }

    /**
     * Keep a copy of [from], which should hold [sha]'s bytes, in the episode [episodeMillis]:
     * published under its name unless the store holds that entry already. Returns the entry's path,
     * or null, with nothing left behind, when [from] holds other bytes (another process wrote over
     * it). Throws when the copy or its publication fails.
     */
    suspend fun put(
        from: String,
        sha: String,
        episodeMillis: Long,
    ): String? {
        val target = NpnPaths.Keep.path(sha, episodeMillis)
        if (fs.stat(target)?.isDirectory == false) return target
        val staging = staging(sha)
        var held = false
        try {
            val source = fs.stat(from) ?: return null
            fs.copyOnce(from, staging)
            held = fs.sha256(staging) == sha
            // The copy carries the original's mtime: what puts it back puts back the file it was.
            if (held) fs.setModifiedTime(staging, source.mtimeMillis)
        } finally {
            if (!held) withContext(NonCancellable) { runCatchingCancellable { fs.delete(staging) } }
        }
        if (!held) return null
        fs.publishStagedOnce(staging, target, sha, targetHeldCounts = true) {}
        return target
    }

    /**
     * Put a copy of the latest entry of [sha] at [to], which must be free: staged in the store, its
     * bytes verified, and moved into place (a move that refuses a taken target). Returns false, with
     * nothing left behind, when the store holds no such entry or [to] is taken; throws when the copy
     * fails.
     */
    suspend fun materialize(
        sha: String,
        to: String,
    ): Boolean {
        val kept = entry(sha) ?: return false
        if (fs.exists(to)) return false
        val staging = staging(sha)
        var placed = false
        try {
            fs.copyOnce(kept.path, staging)
            check(fs.sha256(staging) == sha) { "The keep store's copy of $sha holds other bytes" }
            // Put back as the file it was, mtime and all: no write a rescan waits out, and a stat
            // that tells it from a return the user made.
            fs.setModifiedTime(staging, kept.meta.mtimeMillis)
            if (fs.exists(to)) return false
            fs.move(staging, to)
            placed = true
        } finally {
            if (!placed) withContext(NonCancellable) { runCatchingCancellable { fs.delete(staging) } }
        }
        return true
    }

    /** Delete [entry] for [authority] ([DeleteGate.reap]), while it is unchanged, else still hashes to its name. */
    suspend fun reap(
        entry: KeepEntry,
        authority: DeleteAuthority,
    ): Boolean = gate.reap(entry.path, entry.sha, authority, entry.meta)

    private fun staging(sha: String): String =
        NpnPaths.Keep.dir(sha) + "/" + NpnPaths.Temp.journalStagingName(sha, installationId, ids.next())
}
