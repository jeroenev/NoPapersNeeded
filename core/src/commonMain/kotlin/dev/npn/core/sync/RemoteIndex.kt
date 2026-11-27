package dev.npn.core.sync

import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths

/**
 * What a path in the mirrored layout stands for. The remote mirrors the library folder one to one,
 * so the same layout rules ([NpnPaths], [JournalPaths]) classify a remote path and a local one;
 * keeping a single classifier is what guarantees the two sides agree on which files are documents,
 * which are the write-once metadata the fold reads, and which are noise.
 */
sealed interface SyncPathClass {
    /** A content-addressed journal object or compaction snapshot; [hash] is its expected SHA-256. */
    data class JournalObject(
        val hash: String,
        val snapshot: Boolean,
    ) : SyncPathClass

    /**
     * A write-once internal file with no hash in its name: an OCR sidecar, or an installation's
     * device marker (`DeviceIdentity`), which other replicas need to find a cloned installation out.
     */
    data object WriteOnce : SyncPathClass

    /** A file parked in `.npn/trash/`. */
    data object Trash : SyncPathClass

    /** An indexable PDF at a library path. */
    data object Document : SyncPathClass

    /** Staging files, temp files, conflict copies, unknown internals: never transferred. */
    data object Ignored : SyncPathClass

    /** Whether this is the write-once metadata the fold reads, which both sides keep the union of. */
    val isMetadata: Boolean get() = this is JournalObject || this == WriteOnce
}

object SyncPaths {
    fun classify(path: String): SyncPathClass {
        if (!NpnPaths.isSafe(path) || NpnPaths.normalize(path) != path) return SyncPathClass.Ignored
        if (!NpnPaths.isInternal(path)) {
            return if (NpnPaths.isDocumentPath(path)) SyncPathClass.Document else SyncPathClass.Ignored
        }
        val name = NpnPaths.fileName(path)
        if (NpnPaths.Temp.kindOf(path) != null || NpnPaths.isSyncTemp(name) || NpnPaths.isSyncConflict(name)) {
            return SyncPathClass.Ignored
        }
        return when (val journal = JournalPaths.classify(path)) {
            // Only the canonical name: a sync tool's conflict copy of an object names its hash too.
            is JournalPath.ObjectV2 -> {
                journalObject(path, journal.expectedHash, snapshot = false)
            }

            is JournalPath.SnapshotV2 -> {
                journalObject(path, journal.expectedHash, snapshot = true)
            }

            is JournalPath.UnsupportedObjectStore, JournalPath.Staging, JournalPath.SyncTemp -> {
                SyncPathClass.Ignored
            }

            JournalPath.Unknown -> {
                when {
                    isChild(path, NpnPaths.OCR_DIR, depth = 1) && name.endsWith(".json") -> SyncPathClass.WriteOnce

                    isChild(path, NpnPaths.DEVICES_DIR, depth = 2) && name.endsWith(".json") -> SyncPathClass.WriteOnce

                    // Acknowledgment records: every device's, carried both ways like the markers.
                    isChild(path, NpnPaths.ACKS_DIR, depth = 2) && name.endsWith(".json") -> SyncPathClass.WriteOnce

                    isChild(path, NpnPaths.TRASH_DIR, depth = 1) -> SyncPathClass.Trash

                    else -> SyncPathClass.Ignored
                }
            }
        }
    }

    private fun journalObject(
        path: String,
        hash: String,
        snapshot: Boolean,
    ): SyncPathClass =
        if (path == JournalPaths.objectPath(hash, snapshot)) {
            SyncPathClass.JournalObject(hash, snapshot)
        } else {
            SyncPathClass.Ignored
        }

    /** Whether [path] is [depth] segments below [dir]. */
    private fun isChild(
        path: String,
        dir: String,
        depth: Int,
    ): Boolean = path.startsWith("$dir/") && path.removePrefix("$dir/").count { it == '/' } == depth - 1
}

/** The remote listing, classified once per pass. */
class RemoteIndex(
    entries: List<RemoteEntry>,
) {
    val documents: Map<String, RemoteEntry>
    val trash: Map<String, RemoteEntry>

    /** Journal objects, snapshots and write-once internals, by path. */
    val metadata: Map<String, RemoteEntry>

    /** Expected SHA-256 of every content-addressed remote object, by path. */
    val objectHashes: Map<String, String>

    private val all: Map<String, RemoteEntry> = entries.associateBy { it.path }

    init {
        val documents = LinkedHashMap<String, RemoteEntry>()
        val trash = LinkedHashMap<String, RemoteEntry>()
        val metadata = LinkedHashMap<String, RemoteEntry>()
        val hashes = HashMap<String, String>()
        for (entry in entries.sortedBy { it.path }) {
            when (val kind = SyncPaths.classify(entry.path)) {
                is SyncPathClass.JournalObject -> {
                    metadata[entry.path] = entry
                    hashes[entry.path] = kind.hash
                }

                SyncPathClass.WriteOnce -> {
                    metadata[entry.path] = entry
                }

                SyncPathClass.Trash -> {
                    trash[entry.path] = entry
                }

                SyncPathClass.Document -> {
                    documents[entry.path] = entry
                }

                SyncPathClass.Ignored -> {}
            }
        }
        this.documents = documents
        this.trash = trash
        this.metadata = metadata
        this.objectHashes = hashes
    }

    /** Every listed path, whatever its kind. */
    val paths: Set<String> get() = all.keys

    fun has(path: String): Boolean = path in all

    operator fun get(path: String): RemoteEntry? = all[path]
}
