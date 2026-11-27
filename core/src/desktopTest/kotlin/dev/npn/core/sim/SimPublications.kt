package dev.npn.core.sim

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.index.AckRecord
import dev.npn.core.index.AckRecords
import dev.npn.core.journal.EventDecoderRegistry
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.util.NpnJson

/**
 * What the app publishes in one folder, checked as it lands:
 * - each `doc-trash` records, when it records one, a stat the app moved the document's bytes into
 *   `.npn/trash/` with (`JournalOp.DocTrash.stat`), or, for a trash that publishes its event before
 *   its file leaves (into the keep store), a stat a file at a document path had while it held them
 *   ([saw]): another process may change the file between the trash's check of it and the event.
 *   That one that moved a file records it is the unit tests': here, cloud sync's own moves into the
 *   trash, carrying another device's trash, are the app's moves too, and a trash of the same
 *   document here then rightly records none;
 * - each acknowledgment record (`AckRecords`) links to its device's previous one, vouches only for
 *   journal objects this folder's app published itself, and claims of another device's chain only
 *   records whose objects are all in this folder.
 *
 * The folder's [InMemoryLibraryFileSystem] reports the files the app writes ([wrote]) and moves
 * ([moved]), every file leaving a path ([left]) and every object it publishes ([published]);
 * [bytesAt] reads a path's bytes.
 */
class SimPublications(
    private val bytesAt: (path: String) -> ByteArray?,
) {
    /** Every size and mtime a file at a document path had while it held each document's bytes, by their hash. */
    private val seenStats = HashMap<String, MutableSet<Pair<Long, Long>>>()

    /** The file at [path], holding the bytes hashing to [hash], has [size] and [mtime] now. */
    fun saw(
        path: String,
        hash: String,
        size: Long,
        mtime: Long,
    ) {
        if (NpnPaths.isDocumentPath(path)) seenStats.getOrPut(hash) { HashSet() } += size to mtime
    }

    /** The journal objects, by hash, this folder's app published from a transaction it made. */
    private val ownObjects = HashSet<String>()

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

    /** The app wrote the file at [path] itself. */
    fun wrote(path: String) {
        written += path
    }

    /** The file at [path] left it: moved away or removed. */
    fun left(path: String) {
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
        trashedStats.getOrPut(sha256Bytes(bytes)) { HashSet() } += bytes.size.toLong() to mtime
    }

    /**
     * [bytes], staged at [staging], were published at [path]. When that is a journal object of a
     * transaction the app made (it wrote the staging file itself; one a cloud sync brought is another
     * device's), each `doc-trash` in it records a stat the app moved the document's bytes into the
     * trash with, or none.
     */
    fun published(
        staging: String,
        path: String,
        bytes: ByteArray,
    ) {
        val own = written.remove(staging)
        if (own && path.startsWith(ACKS_PREFIX)) checkAck(path, bytes)
        if (JournalPaths.classify(path) !is JournalPath.ObjectV2) return
        if (own) {
            ownObjects += sha256Bytes(bytes)
            trashesIn(bytes).forEach { (doc, trash) -> check(path, doc, trash) }
        }
    }

    /** The acknowledgment record [bytes] the app published at [path]: its link, what it vouches for, what it claims. */
    private fun checkAck(
        path: String,
        bytes: ByteArray,
    ) {
        val record = parseAck(bytes)
        val foreign = record?.authored.orEmpty().filter { it !in ownObjects }
        val problem =
            when {
                record == null -> "does not parse"
                AckRecords.path(record.device, record.n) != path -> "is record ${record.n} of ${record.device}"
                record.prev != hashOf(record.device, record.n - 1) -> "does not link to the record before it"
                foreign.isNotEmpty() -> "vouches for objects its device did not publish: ${foreign.map { it.take(ID) }}"
                else -> record.seen.entries.firstNotNullOfOrNull { (device, n) -> unseen(device, n) }
            }
        problem?.let { ackViolations += "the acknowledgment record '$path' $it" }
    }

    /** The hash of [device]'s record [n] here; null for record 0, which no record has, or one not here. */
    private fun hashOf(
        device: String,
        n: Long,
    ): String? = if (n < 1) null else bytesAt(AckRecords.path(device, n))?.let(::sha256Bytes)

    /** Why a claim to have applied [device]'s chain through record [n] is false here, or null when it holds. */
    private fun unseen(
        device: String,
        n: Long,
    ): String? =
        (1..n)
            .firstOrNull { i ->
                val earlier = bytesAt(AckRecords.path(device, i))?.let(::parseAck)
                earlier == null || earlier.authored.any { bytesAt(JournalPaths.objectPath(it)) == null }
            }?.let { "claims record $n of $device, but record $it or an object it vouches for is not here" }

    private fun parseAck(bytes: ByteArray): AckRecord? =
        runCatching { NpnJson.Persisted.decodeFromString(AckRecord.serializer(), bytes.decodeToString()) }.getOrNull()

    /** Where an acknowledgment record the app published breaks its chain, or vouches or claims what it may not. */
    val ackViolations = ArrayList<String>()

    private fun check(
        path: String,
        doc: String,
        trash: JournalOp.DocTrash,
    ) {
        val stat = trash.stat ?: return
        val recorded = stat.size to stat.mtimeMillis
        if (recorded in trashedStats[doc].orEmpty() || recorded in seenStats[doc].orEmpty()) return
        violations += "the doc-trash of ${doc.take(ID)} in '$path' records size ${stat.size}, mtime " +
            "${stat.mtimeMillis}, which no move of its bytes into the trash had (${trashedStats[doc].orEmpty()})"
    }

    /** Each `doc-trash` in a journal object's [bytes], with its document. */
    private fun trashesIn(bytes: ByteArray): List<Pair<String, JournalOp.DocTrash>> =
        journalEventsIn(bytes).mapNotNull { event ->
            (event.op as? JournalOp.DocTrash)?.let { trash -> event.doc?.let { it to trash } }
        }

    private companion object {
        const val TRASH_PREFIX = "${NpnPaths.TRASH_DIR}/"
        const val ACKS_PREFIX = "${NpnPaths.ACKS_DIR}/"
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
