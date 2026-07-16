package dev.npn.core.journal

import dev.npn.core.model.DocProperty

/**
 * A pending op the caller wants to commit. The writer assigns seq and HLC and fills in the
 * envelope. `doc` follows the op's requirements (see [JournalOp]).
 */
data class PendingOp(val doc: String?, val op: JournalOp) {
    companion object {
        fun tagAdd(doc: String, tag: String) = PendingOp(doc, JournalOp.TagAdd(tag))
        fun tagRemove(doc: String, tag: String, dots: List<Dot>) =
            PendingOp(doc, JournalOp.TagRemove(tag, dots))
        fun propSet(doc: String, key: DocProperty, value: String?) =
            PendingOp(doc, JournalOp.PropSet(key, value))
        fun docTrash(doc: String, origPath: String) = PendingOp(doc, JournalOp.DocTrash(origPath))
        fun docRestore(doc: String) = PendingOp(doc, JournalOp.DocRestore)
        fun docDelete(doc: String) = PendingOp(doc, JournalOp.DocDelete)
        fun relink(oldDoc: String, newDoc: String) = PendingOp(oldDoc, JournalOp.Relink(newDoc))
        fun tagRename(from: String, to: String) = PendingOp(null, JournalOp.TagRename(from, to))
        fun tagMerge(from: String, to: String) = PendingOp(null, JournalOp.TagMerge(from, to))
        fun tagStyle(tag: String, color: String?, icon: String?) =
            PendingOp(null, JournalOp.TagStyle(tag, color, icon))
        fun settingSet(key: String, value: String?) =
            PendingOp(null, JournalOp.SettingSet(key, value))
    }
}

/** Result of [SegmentWriter.checkSingleWriter]. */
sealed class ForkCheck {
    /** Local state is consistent with the store; safe to keep writing under this device id. */
    data object Ok : ForkCheck()

    /**
     * The store contains an own-device segment the local state does not know about, meaning
     * another installation shares this device id (backup restore / clone). The caller must
     * generate a fresh device id to preserve the single-writer invariant.
     */
    data class ForkNeeded(val unknownSegments: List<String>) : ForkCheck()
}

/**
 * Writes new, write-once segments for a single device.
 *
 * Each [commit] serializes all its ops into one new segment file named after the first seq
 * in the batch. Seq is a per-device monotonic Long, continuous across commits and segments,
 * never reused. HLCs are generated from [hlcClock].
 *
 * Not concurrency-safe; a device has exactly one writer.
 */
class SegmentWriter(
    private val store: SegmentStore,
    val deviceId: String,
    private val hlcClock: HlcClock,
    private val localState: LocalJournalState,
) {
    /**
     * Ensure [localState] knows the last seq. If it is empty (fresh install or lost local
     * state), recover lastSeq as the max seq present across this device's own segments.
     * Returns the recovered/known last seq (null if the device has never written).
     */
    suspend fun recover(): Long? {
        val known = localState.getLastSeq()
        if (known != null) return known
        val ownSegs = ownSegments()
        if (ownSegs.isEmpty()) return null
        // A plain segment's file name only encodes its FIRST seq, so read actual contents to
        // find the true max seq this device has assigned. Snapshots would be safe from the name,
        // but reading is uniform and correct for both.
        var maxSeq = 0L
        for (ref in ownSegs) {
            val events = JournalCodec.decode(store.readSegment(ref))
            val segMax = events.maxOfOrNull { it.seq } ?: ref.lastSeq
            if (segMax > maxSeq) maxSeq = segMax
        }
        localState.setLastSeq(maxSeq)
        localState.setKnownOwnSegments(ownSegs.map { it.fileName }.toSet())
        return maxSeq
    }

    /**
     * Compare the store's own-device segments against the locally-known set. If the store has
     * an own segment the local state does not know, a fork is needed.
     */
    suspend fun checkSingleWriter(): ForkCheck {
        val known = localState.getKnownOwnSegments()
        val inStore = ownSegments().map { it.fileName }.toSet()
        val unknown = (inStore - known).toList()
        return if (unknown.isEmpty()) ForkCheck.Ok else ForkCheck.ForkNeeded(unknown)
    }

    /**
     * Assign seq + HLC to each pending op, write them as one new segment, and advance local
     * state. Returns the written events (empty list -> no-op, no file written).
     */
    suspend fun commit(events: List<PendingOp>): List<JournalEvent> {
        if (events.isEmpty()) return emptyList()
        recover()
        var seq = localState.getLastSeq() ?: 0L
        val firstSeq = seq + 1
        val built = events.map { pending ->
            seq += 1
            JournalEvent(
                version = JournalEvent.CURRENT_VERSION,
                seq = seq,
                hlc = hlcClock.next(),
                dev = deviceId,
                doc = pending.doc,
                op = pending.op,
            )
        }
        val fileName = SegmentRef.plainName(firstSeq)
        store.writeSegment(deviceId, fileName, JournalCodec.encodeLines(built))
        localState.setLastSeq(seq)
        localState.setKnownOwnSegments(localState.getKnownOwnSegments() + fileName)
        return built
    }

    private suspend fun ownSegments(): List<SegmentRef> =
        store.listSegments().filter { it.deviceId == deviceId }
}
