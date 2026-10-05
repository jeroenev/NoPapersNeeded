package dev.npn.core.journal

import dev.npn.core.fs.SHA256_HEX
import dev.npn.core.fs.sha256Bytes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * On-disk format of a compaction snapshot object.
 *
 * A snapshot is a content-addressed JSONL object (same publish flow as a v2 object) living in a
 * DISTINCT bucket namespace (`objects-snap-v2-xx`) so that pre-snapshot readers classify it as
 * [JournalPath.Unknown] and ignore it, rather than trying to fold it (they would reject
 * multi-actor events) or treating it as an unsupported object store (which would block writes).
 *
 * Line layout:
 * ```
 * {"snap":2,"supersedes":["<hash>", ...]}   # manifest record (line 1)
 * {"v":1,"seq":..,"hlc":..,"dev":..,..}     # surviving events (lines 2..N), VERBATIM
 * ```
 *
 * The surviving events are the exact events (any device, any actor) carried by the objects the
 * snapshot supersedes — NOT a folded projection. Folding {snapshot + newer objects} therefore
 * yields byte-for-byte the same event multiset as before compaction (the accumulator dedupes by
 * `(deviceId, seq)`), so the CRDT result is unchanged and concurrent snapshots converge.
 */
data class SnapshotManifest(
    val version: Int,
    val supersedes: List<String>,
) {
    fun encode(): String {
        val obj =
            buildJsonObject {
                put(FIELD_SNAP, JsonPrimitive(version))
                put(FIELD_SUPERSEDES, buildJsonArray { supersedes.forEach { add(JsonPrimitive(it)) } })
            }
        return SnapshotCodec.json.encodeToString(JsonObject.serializer(), obj)
    }

    companion object {
        const val FIELD_SNAP = "snap"
        const val FIELD_SUPERSEDES = "supersedes"
    }
}

object SnapshotCodec {
    val json = JournalCodec.json

    fun decodeManifest(line: String): SnapshotManifest {
        val obj =
            json.parseToJsonElement(line.trim()) as? JsonObject
                ?: throw IllegalArgumentException("Snapshot manifest is not a JSON object")
        val version = (obj[SnapshotManifest.FIELD_SNAP] as? JsonPrimitive)?.content?.toIntOrNull()
        val supersedes = (obj[SnapshotManifest.FIELD_SUPERSEDES] as? JsonArray)?.map { it.jsonPrimitive.content }
        require(version != null) { "Snapshot manifest version must be an integer" }
        require(supersedes != null) { "Snapshot manifest supersedes must be an array" }
        require(supersedes.all { SHA256_HEX.matches(it) }) { "Superseded hash is not a SHA-256" }
        return SnapshotManifest(version, supersedes)
    }

    fun encodeSnapshot(
        manifest: SnapshotManifest,
        events: List<JournalEvent>,
    ): ByteArray {
        val lines =
            buildList {
                add(manifest.encode())
                events.forEach { add(JournalCodec.encode(it)) }
            }
        return lines.joinToString("\n").encodeToByteArray()
    }

    const val SNAPSHOT_MANIFEST_VERSION = 2
}

/**
 * Strict snapshot reader. Like [StorageV2Reader] it applies zero events on any integrity or
 * structural failure and treats a still-transferring file as [JournalSourceRead.Retryable].
 * Unlike a v2 object it accepts multiple transaction actors (events come from many devices) and
 * does not require a contiguous `1..N` sequence.
 */
object SnapshotV2Reader {
    /**
     * Read the snapshot at [relPath]: an accepted read carries its events and its manifest.
     * [contentHash] is [bytes]' SHA-256, when the caller has it already.
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
                    return@read JournalSourceRead.Retryable("Snapshot manifest is invalid: ${e.message}")
                }
            if (manifest.version != SnapshotCodec.SNAPSHOT_MANIFEST_VERSION) {
                return@read JournalSourceRead.Unsupported(
                    "Snapshot manifest version ${manifest.version} is unsupported",
                )
            }
            when (val events = StrictLines.decodeEvents(lines.drop(1), NOUN, firstLine = 2)) {
                is JournalSourceRead.Accepted -> events.copy(manifest = manifest)
                else -> events
            }
        }
    }

    private const val NOUN = "Snapshot"
}
