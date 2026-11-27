package dev.npn.core.sim

import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp

/**
 * Whether the app holds an authorizing fact each time it takes from one folder the last copy there of
 * a document's bytes (a delete with no other of the library's copies left in that folder): the
 * folder's journal has the document deleted for good, in the trash, or superseded by a relink. A
 * delete with no such fact is the shape of every last-copy loss the simulation found (a dual
 * retirement, a rollback, a purge of a restored document), caught here at the delete itself rather
 * than once the sync tool has carried it to the last replica.
 *
 * [journalObjects] gives the folder's published journal objects by path; each is decoded once, as
 * an object never changes.
 */
class SimDeleteAuthority(
    private val journalObjects: () -> Map<String, ByteArray>,
) {
    private val decoded = HashMap<String, List<JournalEvent>>()

    /** Each delete of a last copy here that no fact in this folder's journal allowed. */
    val violations = ArrayList<String>()

    /**
     * Off for a harness test of the last-copy check across replicas, whose "app" deletes with no
     * fact on purpose; that check, not this one, is what such a test is about.
     */
    var enabled = true

    /** The app took the bytes hashing to [hash] from [path] at [atMillis], no other copy of them left here. */
    fun tookLastCopy(
        path: String,
        hash: String,
        atMillis: Long,
        history: () -> List<String>,
    ) {
        // Only a document's bytes are judged: the library's own folders, the trash and the keep
        // store, not the journal's staging files or the other internals under `.npn/`.
        val internal =
            path.startsWith("${NpnPaths.NPN_DIR}/") && !path.startsWith("${NpnPaths.TRASH_DIR}/") &&
                !path.startsWith("${NpnPaths.KEEP_DIR}/")
        if (!enabled || internal || authority(hash) != null) return
        violations +=
            "t=$atMillis the app deleted '$path', the last copy here of ${hash.take(ID)}, with no tombstone, " +
            "trash or relink of it in this folder's journal (${history().joinToString("; ")})"
    }

    /** What in this folder's journal lets the app delete [hash]'s bytes, or null when nothing does. */
    fun authority(hash: String): String? {
        val events =
            journalObjects()
                .flatMap { (path, bytes) -> decoded.getOrPut(path) { journalEventsIn(bytes) } }
                .filter { it.doc == hash }
                .sortedBy { it.hlc }

        fun last(vararg kinds: String) = events.lastOrNull { it.op.wire in kinds }?.op?.wire
        return when {
            events.any { it.op is JournalOp.Relink } -> "relink"

            last(JournalOp.DocDelete.WIRE, JournalOp.DocUndelete.WIRE) == JournalOp.DocDelete.WIRE -> "tombstone"

            last(JournalOp.DocTrash.WIRE, JournalOp.DocRestore.WIRE, JournalOp.DocUndelete.WIRE) ==
                JournalOp.DocTrash.WIRE -> "trash"

            else -> null
        }
    }

    private companion object {
        const val ID = 8
    }
}
