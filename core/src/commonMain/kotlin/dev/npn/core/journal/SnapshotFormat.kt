package dev.npn.core.journal

import dev.npn.core.fs.NpnPaths
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
 * [NpnPaths.JournalPath.Unknown] and ignore it, rather than trying to fold it (they would reject
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
data class SnapshotManifest(val version: Int, val supersedes: List<String>) {
    fun encode(): String {
        val obj = buildJsonObject {
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

    /** True when [line] is a snapshot manifest record (a `snap` versioned object). */
    fun isManifestLine(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return false
        val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return false
        return obj.containsKey(SnapshotManifest.FIELD_SNAP)
    }

    fun decodeManifest(line: String): SnapshotManifest {
        val obj = json.parseToJsonElement(line.trim()) as? JsonObject
            ?: throw IllegalArgumentException("Snapshot manifest is not a JSON object")
        val version = (obj[SnapshotManifest.FIELD_SNAP] as? JsonPrimitive)?.content?.toIntOrNull()
        val supersedes = (obj[SnapshotManifest.FIELD_SUPERSEDES] as? JsonArray)?.map { it.jsonPrimitive.content }
        require(version != null) { "Snapshot manifest version must be an integer" }
        require(supersedes != null) { "Snapshot manifest supersedes must be an array" }
        require(supersedes.all { HEX64.matches(it) }) { "Superseded hash is not a SHA-256" }
        return SnapshotManifest(version, supersedes)
    }

    fun encodeSnapshot(manifest: SnapshotManifest, events: List<JournalEvent>): ByteArray {
        val lines = buildList {
            add(manifest.encode())
            events.forEach { add(JournalCodec.encode(it)) }
        }
        return lines.joinToString("\n").encodeToByteArray()
    }

    private val HEX64 = Regex("^[0-9a-f]{64}$")
    const val SNAPSHOT_MANIFEST_VERSION = 2
}

/** The parsed result of a snapshot object: its manifest plus surviving canonical events. */
data class SnapshotRead(
    val manifest: SnapshotManifest,
    val events: List<CanonicalJournalEvent>,
)

/**
 * Strict snapshot reader. Like [StorageV2Reader] it applies zero events on any integrity or
 * structural failure and treats a still-transferring file as [JournalSourceRead.Retryable].
 * Unlike a v2 object it accepts multiple transaction actors (events come from many devices) and
 * does not require a contiguous `1..N` sequence.
 */
object SnapshotV2Reader {
    fun read(relPath: String, bytes: ByteArray): JournalSourceRead {
        val path = NpnPaths.journalPath(relPath)
        val snapshotPath = path as? NpnPaths.JournalPath.SnapshotV2
            ?: return JournalSourceRead.Unsupported("Not a supported snapshot object path: $relPath")
        if (sha256Bytes(bytes) != snapshotPath.expectedHash) {
            return JournalSourceRead.Retryable("Snapshot hash does not match its path")
        }
        return when (val parsed = parse(bytes)) {
            is SnapshotParse.Ok -> JournalSourceRead.Accepted(parsed.read.events)
            is SnapshotParse.Retry -> JournalSourceRead.Retryable(parsed.message)
            is SnapshotParse.Unsupported -> JournalSourceRead.Unsupported(parsed.message)
        }
    }

    /** Read the manifest + events together for the compactor and supersession bookkeeping. */
    fun readSnapshot(relPath: String, bytes: ByteArray): SnapshotRead? {
        val path = NpnPaths.journalPath(relPath)
        if (path !is NpnPaths.JournalPath.SnapshotV2) return null
        if (sha256Bytes(bytes) != path.expectedHash) return null
        return (parse(bytes) as? SnapshotParse.Ok)?.read
    }

    private sealed interface SnapshotParse {
        data class Ok(val read: SnapshotRead) : SnapshotParse
        data class Retry(val message: String) : SnapshotParse
        data class Unsupported(val message: String) : SnapshotParse
    }

    private fun parse(bytes: ByteArray): SnapshotParse {
        val lines = splitLines(bytes) ?: return SnapshotParse.Retry("Snapshot is not valid UTF-8")
        if (lines.isEmpty() || lines.any { it.isBlank() }) {
            return SnapshotParse.Retry("Snapshot is empty or contains a blank line")
        }
        val manifest = try {
            SnapshotCodec.decodeManifest(lines.first())
        } catch (e: Exception) {
            return SnapshotParse.Retry("Snapshot manifest is invalid: ${e.message}")
        }
        if (manifest.version != SnapshotCodec.SNAPSHOT_MANIFEST_VERSION) {
            return SnapshotParse.Unsupported("Snapshot manifest version ${manifest.version} is unsupported")
        }
        return decodeEvents(lines.drop(1), manifest)
    }

    private fun splitLines(bytes: ByteArray): List<String>? {
        val content = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return null
        }
        return content.split('\n').let { split ->
            if (split.lastOrNull()?.isEmpty() == true) split.dropLast(1) else split
        }
    }

    private fun decodeEvents(eventLines: List<String>, manifest: SnapshotManifest): SnapshotParse {
        val decoded = ArrayList<CanonicalJournalEvent>(eventLines.size)
        for ((index, line) in eventLines.withIndex()) {
            val event = try {
                EventDecoderRegistry.decode(line, allowMissingV1 = false)
            } catch (e: UnsupportedEventVersionException) {
                return SnapshotParse.Unsupported(
                    "Snapshot line ${index + 2} uses unsupported event version ${e.version}",
                )
            } catch (e: Exception) {
                return SnapshotParse.Retry("Snapshot line ${index + 2} is invalid: ${e.message}")
            }
            if (event.event.op is JournalOp.Unknown) {
                return SnapshotParse.Unsupported(
                    "Snapshot line ${index + 2} uses unknown operation ${event.event.op.wire}",
                )
            }
            decoded += event
        }
        return SnapshotParse.Ok(SnapshotRead(manifest, decoded))
    }
}
