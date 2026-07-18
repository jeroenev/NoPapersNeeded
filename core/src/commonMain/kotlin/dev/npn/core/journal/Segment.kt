package dev.npn.core.journal

/**
 * Reference to one segment file inside a device's journal directory.
 *
 * File naming:
 * - Plain segment: `<zero-padded-16-digit first-seq>.jsonl`, e.g. `0000000000000001.jsonl`.
 * - Snapshot (compaction output): `<first-seq>-<last-seq>.snap.jsonl` (both zero-padded 16),
 *   still plain JSON-lines of events, e.g. `0000000000000001-0000000000000042.snap.jsonl`.
 */
data class SegmentRef(val deviceId: String, val fileName: String) {
    val isSnapshot: Boolean get() = fileName.endsWith(SNAP_SUFFIX)

    /** The first seq encoded in the file name. */
    val firstSeq: Long
        get() {
            val base = fileName.removeSuffix(SNAP_SUFFIX).removeSuffix(PLAIN_SUFFIX)
            val firstPart = base.substringBefore('-')
            return firstPart.toLong()
        }

    /** The last seq encoded in a snapshot name, or [firstSeq] for a plain segment. */
    val lastSeq: Long
        get() {
            if (!isSnapshot) return firstSeq
            val base = fileName.removeSuffix(SNAP_SUFFIX)
            return base.substringAfter('-').toLong()
        }

    companion object {
        const val PLAIN_SUFFIX = ".jsonl"
        const val SNAP_SUFFIX = ".snap.jsonl"
        private const val WIDTH = 16

        private fun pad(seq: Long): String = seq.toString().padStart(WIDTH, '0')

        fun plainName(firstSeq: Long): String = "${pad(firstSeq)}$PLAIN_SUFFIX"

        /** True if [fileName] is a name this engine produces (plain or snapshot). */
        fun isSegmentFile(fileName: String): Boolean {
            val plain = Regex("^[0-9]+\\.jsonl$")
            val snapshot = Regex("^[0-9]+-[0-9]+\\.snap\\.jsonl$")
            return plain.matches(fileName) || snapshot.matches(fileName)
        }
    }
}

/**
 * Filesystem adapter for journal segments. Real implementations live in the platform
 * source sets; [InMemorySegmentStore] backs tests.
 *
 * Invariants relied upon by the engine:
 * - [writeSegment] is create-once: it must fail if the file already exists.
 */
interface SegmentStore {
    suspend fun listSegments(): List<SegmentRef>
    suspend fun readSegment(ref: SegmentRef): String

    /** Create-once write; must fail if a file with [fileName] already exists for [deviceId]. */
    suspend fun writeSegment(deviceId: String, fileName: String, content: String)
}
