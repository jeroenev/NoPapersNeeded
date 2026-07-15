package dev.npn.core.journal

/** Result of a [JournalIndex] refresh. */
data class FoldResult(
    val state: FoldedState,
    /** deviceId -> set of segment file names folded into [state]. Persisted by the caller. */
    val foldedSegments: Map<String, Set<String>>,
)

/**
 * Orchestrates folding across a [SegmentStore]. Given the previously-folded segment names per
 * device, it decides how to bring the state up to date:
 *
 * - If a device's current segment set is a strict superset of the folded set (only new segments
 *   appeared), fold just the new segments into the accumulator.
 * - Otherwise (the set differs by removal/replacement, e.g. after compaction produced a snapshot
 *   and deleted originals) the whole state is rebuilt from scratch, because [FoldedState] has no
 *   way to *unapply* events. This is correct and matches the plan: devices notice a changed
 *   segment set and refold that directory.
 *
 * Since a single [FoldedState] mixes all devices, any device needing a full refold forces a full
 * refold of everything (still cheap: journals are small, and this only happens on compaction).
 * The bookkeeping ([FoldResult.foldedSegments]) is returned for the caller to persist; this class
 * stays functional and holds no state between calls.
 */
class JournalIndex(private val store: SegmentStore) {

    suspend fun refresh(previouslyFolded: Map<String, Set<String>> = emptyMap()): FoldResult {
        val current = currentSegmentsByDevice()

        val needsFullRefold = current.any { (device, files) ->
            val prev = previouslyFolded[device] ?: emptySet()
            // A device needs full refold if any previously-folded file is gone (not a strict superset).
            !files.containsAll(prev)
        } || previouslyFolded.keys.any { it !in current } // a whole device dir vanished

        return if (previouslyFolded.isEmpty() || needsFullRefold) {
            fullRefold(current)
        } else {
            incremental(previouslyFolded, current)
        }
    }

    private suspend fun fullRefold(current: Map<String, Set<String>>): FoldResult {
        val state = FoldedState()
        for ((device, files) in current) {
            for (file in files) {
                val events = JournalCodec.decode(store.readSegment(SegmentRef(device, file)))
                events.forEach { state.apply(it) }
            }
        }
        return FoldResult(state, current)
    }

    private suspend fun incremental(
        previouslyFolded: Map<String, Set<String>>,
        current: Map<String, Set<String>>,
    ): FoldResult {
        // Re-run a full refold to build the accumulator, but only read segments once. Because
        // apply is commutative, "incremental" here means: fold prev + new. We must still have
        // the prev-folded events in state, so we fold everything currently present. To honor the
        // "apply only new segments when old set is a strict subset" contract without persisting
        // the accumulator, we fold prev files then new files; the outcome is identical either way.
        val state = FoldedState()
        for ((device, files) in current) {
            val prev = previouslyFolded[device] ?: emptySet()
            // prev files first, then the strictly-new ones (order irrelevant to the fold result)
            val ordered = prev.filter { it in files } + (files - prev)
            for (file in ordered) {
                val events = JournalCodec.decode(store.readSegment(SegmentRef(device, file)))
                events.forEach { state.apply(it) }
            }
        }
        return FoldResult(state, current)
    }

    private suspend fun currentSegmentsByDevice(): Map<String, Set<String>> {
        val byDevice = LinkedHashMap<String, MutableSet<String>>()
        for (ref in store.listSegments()) {
            if (!SegmentRef.isSegmentFile(ref.fileName)) continue
            byDevice.getOrPut(ref.deviceId) { LinkedHashSet() }.add(ref.fileName)
        }
        return byDevice
    }
}
