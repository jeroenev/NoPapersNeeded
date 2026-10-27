package dev.npn.core.journal

import dev.npn.core.fs.sha256Bytes
import dev.npn.core.util.reason
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * On-disk format of a compaction snapshot object, which builds before this one wrote when they
 * compacted the journal. This build reads snapshots as journal sources and writes none.
 *
 * A snapshot is a content-addressed JSONL object (same publish flow as a v2 object) living in a
 * DISTINCT bucket namespace (`objects-snap-v2-xx`) so that pre-snapshot readers classify it as
 * [JournalPath.Unknown] and ignore it, rather than trying to fold it (they would reject
 * multi-actor events) or treating it as an unsupported object store (which would block writes).
 *
 * Line layout:
 * ```
 * {"snap":2,"supersedes":["<hash>", ...]}   # manifest record (line 1)
 * {"v":1,"seq":..,"hlc":..,"dev":..,..}     # surviving events (lines 2..N)
 * ```
 *
 * The surviving events are the events (any device, any actor) carried by the objects the snapshot
 * supersedes — NOT a folded projection — as the compacting build encoded them again, so a copy may
 * lack payload keys that build did not know ([EventCopies]). Folding {snapshot + newer objects}
 * yields the same event multiset as before compaction (the accumulator dedupes by `(deviceId, seq)`),
 * so the CRDT result is unchanged and concurrent snapshots converge.
 */
internal data class SnapshotManifest(
    val version: Int,
) {
    companion object {
        const val FIELD_SNAP = "snap"

        /**
         * The objects the snapshot replaced, as the compacting build named them. Not read: whether a
         * snapshot holds what it names is the event tables' to say ([dev.npn.core.index.SourceSetValidator]).
         */
        const val FIELD_SUPERSEDES = "supersedes"
    }
}

internal object SnapshotCodec {
    val json = JournalCodec.json

    fun decodeManifest(line: String): SnapshotManifest {
        val obj =
            json.parseToJsonElement(line.trim()) as? JsonObject
                ?: throw IllegalArgumentException("Snapshot manifest is not a JSON object")
        val version =
            requireNotNull((obj[SnapshotManifest.FIELD_SNAP] as? JsonPrimitive)?.content?.toIntOrNull()) {
                "Snapshot manifest version must be an integer"
            }
        return SnapshotManifest(version)
    }

    const val SNAPSHOT_MANIFEST_VERSION = 2
}

/**
 * Strict snapshot reader. Like [StorageV2Reader] it applies zero events on any integrity or
 * structural failure and treats a still-transferring file as [JournalSourceRead.Retryable].
 * Unlike a v2 object it accepts multiple transaction actors (events come from many devices) and
 * does not require a contiguous `1..N` sequence.
 */
internal object SnapshotV2Reader {
    /**
     * Read the snapshot at [relPath]: an accepted read carries its events. Its manifest is checked,
     * and read for its version alone: whether the snapshot holds what it names is the event
     * tables' to say ([dev.npn.core.index.SourceSetValidator]). [contentHash] is [bytes]' SHA-256,
     * when the caller has it already.
     */
    fun read(
        relPath: String,
        bytes: ByteArray,
        contentHash: String = sha256Bytes(bytes),
    ): JournalSourceRead {
        val path = JournalPaths.classify(relPath)
        val snapshotPath =
            path as? JournalPath.SnapshotV2
                ?: return JournalSourceRead.Unsupported("Not a supported snapshot object path: $relPath")
        if (contentHash != snapshotPath.expectedHash) {
            return JournalSourceRead.Retryable("Snapshot hash does not match its path")
        }
        return StrictLines.read(bytes, NOUN) { lines ->
            val manifest =
                try {
                    SnapshotCodec.decodeManifest(lines.first())
                } catch (e: Exception) {
                    return@read JournalSourceRead.Retryable("Snapshot manifest is invalid: ${e.reason()}")
                }
            if (manifest.version != SnapshotCodec.SNAPSHOT_MANIFEST_VERSION) {
                return@read JournalSourceRead.Unsupported(
                    "Snapshot manifest version ${manifest.version} is unsupported",
                )
            }
            StrictLines.decodeEvents(lines.drop(1), NOUN, firstLine = 2)
        }
    }

    private const val NOUN = "Snapshot"
}
