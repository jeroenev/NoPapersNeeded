package dev.npn.core.journal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The bytes of a snapshot as builds before this one wrote it when they compacted the journal: the
 * manifest superseding [supersedes], then each of [events] on a line of its own. This build reads
 * snapshots and writes none, so only tests encode one.
 */
internal fun earlierBuildSnapshot(
    supersedes: List<String>,
    events: List<JournalEvent>,
): ByteArray {
    val manifest =
        JsonObject(
            mapOf(
                SnapshotManifest.FIELD_SNAP to JsonPrimitive(SnapshotCodec.SNAPSHOT_MANIFEST_VERSION),
                SnapshotManifest.FIELD_SUPERSEDES to JsonArray(supersedes.map(::JsonPrimitive)),
            ),
        )
    val header = SnapshotCodec.json.encodeToString(JsonObject.serializer(), manifest)
    return (listOf(header) + events.map(JournalCodec::encode)).joinToString("\n").encodeToByteArray()
}
