package dev.npn.core.index

import dev.npn.core.db.FoldedSegmentDao

/**
 * Persists the "which journal segment files have been folded into the cache" bookkeeping that
 * [dev.npn.core.journal.JournalIndex] returns in [dev.npn.core.journal.FoldResult.foldedSegments].
 *
 * The map is deviceId -> set of segment file names. Segments are write-once immutable files, so a
 * filename either is or isn't folded (PLAN.md sec. 2). On compaction a device's set changes
 * (originals deleted, snapshot added); [save] replaces that device's set wholesale, matching
 * JournalIndex's full-refold-on-set-change behaviour.
 */
class DbFoldedSegmentTracker(private val dao: FoldedSegmentDao) {

    /** Load the previously-folded segment names, grouped by device. */
    suspend fun load(): Map<String, Set<String>> {
        val out = LinkedHashMap<String, MutableSet<String>>()
        for (row in dao.allSegments()) {
            out.getOrPut(row.deviceId) { LinkedHashSet() }.add(row.fileName)
        }
        return out
    }

    /**
     * Replace the tracked segment sets with [foldedSegments]. Each device's set is replaced
     * wholesale; devices absent from the map have their tracked set cleared (their directory
     * vanished).
     */
    suspend fun save(foldedSegments: Map<String, Set<String>>) {
        val existing = dao.allSegments().map { it.deviceId }.toSet()
        // Refresh every device present now.
        for ((deviceId, names) in foldedSegments) {
            dao.replaceForDevice(deviceId, names.toList())
        }
        // Clear devices we tracked before but that are gone now.
        for (deviceId in existing - foldedSegments.keys) {
            dao.deleteForDevice(deviceId)
        }
    }
}
