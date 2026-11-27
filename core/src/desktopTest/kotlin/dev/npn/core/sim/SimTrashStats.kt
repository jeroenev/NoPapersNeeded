package dev.npn.core.sim

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.EventDecoderRegistry
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths

/**
 * Whether each `doc-trash` the app publishes in one folder records the stat of the file its trash
 * moved into `.npn/trash/` (`JournalOp.DocTrash.stat`), and none when it moved none. The folder's
 * [InMemoryLibraryFileSystem] reports the files the app writes ([wrote]) and moves ([moved]), every
 * file leaving a path ([left]) and every object it publishes ([published]); [bytesAt] reads a path's
 * bytes.
 */
class SimTrashStats(
    private val bytesAt: (path: String) -> ByteArray?,
) {
    /** A file the app moved into the trash: its bytes' hash, and the size and mtime the move kept. */
    private class TrashMove(
        val hash: String,
        val size: Long,
        val mtime: Long,
    )

    /**
     * The app's moves into the trash whose `doc-trash` this folder has not published yet, by the
     * trash path each put its file at. A trash moves the file, then publishes its event under the
     * writer lock, so the next journal object the app publishes names it. Forgotten at every journal
     * object, at an injected fault and at a crash or close ([forget]): a trash cut short leaves its
     * file in the trash and publishes nothing, and a later trash of the document, which moves nothing,
     * must not answer for it.
     */
    private val pending = HashMap<String, TrashMove>()

    /** Every size and mtime the app moved each document's bytes into the trash with, by their hash. */
    private val trashedStats = HashMap<String, MutableSet<Pair<Long, Long>>>()

    /**
     * The files the app wrote here itself, as its journal writer stages a transaction it made: an
     * object a cloud sync brings is staged from outside (an import), and holds another device's
     * events, which are not judged here.
     */
    private val written = HashSet<String>()

    /** Where a `doc-trash` the app published does not record the stat of the file its trash moved. */
    val violations = ArrayList<String>()

    /** The process that made the pending trash moves ended (a crash or a close), or a fault cut one short. */
    fun forget() = pending.clear()

    /** The app wrote the file at [path] itself. */
    fun wrote(path: String) {
        written += path
    }

    /** The file at [path] left it: moved away or removed. */
    fun left(path: String) {
        pending.remove(path)
        written.remove(path)
    }

    /** The app moved [bytes], of [mtime], from [from] to [to]: a move into the trash, when it is one. */
    fun moved(
        from: String,
        to: String,
        bytes: ByteArray,
        mtime: Long,
    ) {
        if (from.startsWith(TRASH_PREFIX) || !to.startsWith(TRASH_PREFIX) || NpnPaths.Temp.kindOf(to) != null) return
        val move = TrashMove(sha256Bytes(bytes), bytes.size.toLong(), mtime)
        pending[to] = move
        trashedStats.getOrPut(move.hash) { HashSet() } += move.size to move.mtime
    }

    /**
     * [bytes], staged at [staging], were published at [path]. When that is a journal object of a
     * transaction the app made (it wrote the staging file itself; one a cloud sync brought is another
     * device's), each `doc-trash` in it records a stat the app moved the document's bytes into the
     * trash with, or none, and a document whose bytes a pending move put in the trash, still there,
     * has its trash record one.
     */
    fun published(
        staging: String,
        path: String,
        bytes: ByteArray,
    ) {
        val own = written.remove(staging)
        if (JournalPaths.classify(path) !is JournalPath.ObjectV2) return
        if (own) trashesIn(bytes).forEach { (doc, trash) -> check(path, doc, trash) }
        pending.clear()
    }

    private fun check(
        path: String,
        doc: String,
        trash: JournalOp.DocTrash,
    ) {
        val stat = trash.stat
        val moves =
            pending.filter { (at, move) -> move.hash == doc && bytesAt(at)?.let(::sha256Bytes) == doc }
        val problem =
            when {
                stat != null && (stat.size to stat.mtimeMillis) !in trashedStats[doc].orEmpty() -> {
                    "records size ${stat.size}, mtime ${stat.mtimeMillis}, which no move of its bytes into the " +
                        "trash had (${trashedStats[doc].orEmpty()})"
                }

                stat == null && moves.isNotEmpty() -> {
                    "records no stat, though the app moved its file to ${moves.keys} and it is still there"
                }

                else -> {
                    null
                }
            }
        problem?.let { violations += "the doc-trash of ${doc.take(ID)} in '$path' $it" }
    }

    /** Each `doc-trash` in a journal object's [bytes], with its document. */
    private fun trashesIn(bytes: ByteArray): List<Pair<String, JournalOp.DocTrash>> =
        journalEventsIn(bytes).mapNotNull { event ->
            (event.op as? JournalOp.DocTrash)?.let { trash -> event.doc?.let { it to trash } }
        }

    private companion object {
        const val TRASH_PREFIX = "${NpnPaths.TRASH_DIR}/"
        const val ID = 8
    }
}

/** The events of a journal object's [bytes], one per line; a line that does not decode is passed over. */
internal fun journalEventsIn(bytes: ByteArray): List<JournalEvent> =
    bytes
        .decodeToString()
        .lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line -> runCatching { EventDecoderRegistry.decode(line).event }.getOrNull() }
        .toList()
