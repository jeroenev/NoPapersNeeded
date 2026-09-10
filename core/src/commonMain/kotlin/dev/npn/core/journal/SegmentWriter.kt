package dev.npn.core.journal

import dev.npn.core.model.FieldDef
import dev.npn.core.model.SystemProperty

/**
 * A pending op the caller wants to commit. The writer assigns seq and HLC and fills in the
 * envelope. `doc` follows the op's requirements (see [JournalOp]).
 */
data class PendingOp(
    val doc: String?,
    val op: JournalOp,
) {
    companion object {
        fun tagAdd(
            doc: String,
            tag: String,
        ) = PendingOp(doc, JournalOp.TagAdd(tag))

        /**
         * `internal` on purpose: a correct OR-set `tag-remove` must carry the dots CURRENTLY live
         * for `(doc, tag)`. The only sanctioned way to obtain those is [FoldedState.tagRemovalOps],
         * which reads a live [FoldedState] under the coordinator mutex. Keeping this factory out of
         * the public surface makes a removal against stale/fabricated dots unexpressible.
         */
        internal fun tagRemove(
            doc: String,
            tag: String,
            dots: List<Dot>,
        ) = PendingOp(doc, JournalOp.TagRemove(tag, dots))

        fun propSet(
            doc: String,
            key: SystemProperty,
            value: String?,
        ) = PendingOp(doc, JournalOp.PropSet(key, value))

        fun fieldSet(
            doc: String,
            key: String,
            value: String?,
        ) = PendingOp(doc, JournalOp.FieldSet(key, value))

        fun docTrash(
            doc: String,
            origPath: String,
        ) = PendingOp(doc, JournalOp.DocTrash(origPath))

        fun docRestore(doc: String) = PendingOp(doc, JournalOp.DocRestore)

        fun docDelete(doc: String) = PendingOp(doc, JournalOp.DocDelete)

        fun relink(
            oldDoc: String,
            newDoc: String,
        ) = PendingOp(oldDoc, JournalOp.Relink(newDoc))

        fun tagRename(
            from: String,
            to: String,
        ) = PendingOp(null, JournalOp.TagRename(from, to))

        fun tagMerge(
            from: String,
            to: String,
        ) = PendingOp(null, JournalOp.TagMerge(from, to))

        fun tagStyle(
            tag: String,
            color: String?,
            icon: String?,
        ) = PendingOp(null, JournalOp.TagStyle(tag, color, icon))

        fun settingSet(
            key: String,
            value: String?,
        ) = PendingOp(null, JournalOp.SettingSet(key, value))

        /** Define or update a registry field. */
        fun fieldDefSet(def: FieldDef) = PendingOp(null, JournalOp.FieldDefSet(def.key, def))

        /** Remove a registry field's definition; stored values are untouched. */
        fun fieldDefRemove(key: String) = PendingOp(null, JournalOp.FieldDefSet(key, null))
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
    data class ForkNeeded(
        val unknownSegments: List<String>,
    ) : ForkCheck()
}

/**
 * Writes new, write-once segments for a single device.
 *
 * Each [commit] serializes all its ops into one new segment file named after the first seq
 * in the batch. Seq is a per-device monotonic Long, continuous across commits and segments,
 * never reused. HLCs are generated from [hlcClock].
 *
 * Not concurrency-safe; a device has exactly one writer.
 *
 * `internal` to the `core` module so the single-writer invariant is enforced at the type
 * level, not by convention: the only sanctioned commit path is [JournalCoordinator], which
 * serializes every write behind its mutex and keeps the live [FoldedState]/DB projection in
 * step. A caller outside `core` cannot reach this writer's raw [commit] and so cannot bypass
 * that ordering (e.g. publishing a `tag-remove` against stale OR-set dots). `core`'s own test
 * source sets stay in-module and may still construct and drive it directly.
 */
internal class SegmentWriter(
    private val store: SegmentStore,
    val deviceId: String,
    private val hlcClock: HlcClock,
    private val localState: LocalJournalState,
) {
    private var recoveryComplete = false

    /**
     * This device's own segments, cached across the boot sequence. [recover] and
     * [checkSingleWriter] are called back to back when a session opens and both need the same
     * listing, and listing means walking the whole journal directory — on Android that is one SAF
     * query per directory, so resolving it twice doubles a measurable slice of startup.
     *
     * Safe because the fork check runs exactly once, at boot (`DeviceIdentity.ensureSingleWriter`),
     * and [recover] is idempotent after [recoveryComplete]. Note this is NOT the same as "only
     * [commit] can add an own segment": a second installation writing under the same deviceId into
     * the same folder can too, and catching that is precisely what [checkSingleWriter] is for. So
     * a future caller that re-runs the fork check mid-session must drop this cache first, or it
     * will be answered from a listing taken at boot.
     */
    private var ownSegmentsCache: List<SegmentRef>? = null

    /**
     * Ensure [localState] knows the last seq. If it is empty (fresh install or lost local
     * state), recover lastSeq as the max seq present across this device's own segments.
     * Returns the recovered/known last seq (null if the device has never written).
     */
    suspend fun recover(): Long? {
        if (recoveryComplete) return localState.getLastSeq()
        val ownSegs = ownSegments()
        val known = localState.getLastSeq()
        if (ownSegs.isEmpty()) {
            recoveryComplete = true
            return known
        }
        // A plain segment's file name only encodes its FIRST seq, so read actual contents to
        // find the true max seq this device has assigned. Snapshots would be safe from the name,
        // but reading is uniform and correct for both.
        var maxSeq = 0L
        for (ref in ownSegs) {
            val events = JournalCodec.decode(store.readSegment(ref))
            val segMax = events.maxOfOrNull { it.seq } ?: ref.lastSeq
            if (segMax > maxSeq) maxSeq = segMax
        }
        val recoveredSeq = maxOf(known ?: 0L, maxSeq)
        if (known != recoveredSeq) localState.setLastSeq(recoveredSeq)
        if (known == null) localState.setKnownOwnSegments(ownSegs.map { it.fileName }.toSet())
        recoveryComplete = true
        return recoveredSeq
    }

    /**
     * Advance the local HLC clock past [remote] (standard HLC receive rule). Called with the
     * fold's max HLC after every refold: the clock starts at zero on boot, so without this seed
     * a backwards/slow wall clock would stamp new events BELOW ones already in the store and
     * every LWW write (prop/field set or clear) would silently lose to stale state.
     */
    fun observeHlc(remote: Hlc) {
        if (remote > hlcClock.last) hlcClock.observe(remote)
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
        val built =
            events.map { pending ->
                seq += 1
                JournalEvent(
                    version = JournalEventVersions.CANONICAL,
                    seq = seq,
                    hlc = hlcClock.next(),
                    dev = deviceId,
                    doc = pending.doc,
                    op = pending.op,
                )
            }
        val fileName = SegmentRef.plainName(firstSeq)
        store.writeSegment(deviceId, fileName, JournalCodec.encodeLines(built))
        ownSegmentsCache = null
        localState.setLastSeq(seq)
        localState.setKnownOwnSegments(localState.getKnownOwnSegments() + fileName)
        return built
    }

    private suspend fun ownSegments(): List<SegmentRef> =
        ownSegmentsCache ?: store
            .listSegments()
            .filter { it.deviceId == deviceId }
            .also { ownSegmentsCache = it }
}
