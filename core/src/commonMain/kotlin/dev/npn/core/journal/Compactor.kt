package dev.npn.core.journal

import dev.npn.core.model.DocProperty

/**
 * Produces a minimal own-event list that folds to the same state as the originals when combined
 * with any superset of the other devices' events.
 *
 * Correctness assumption (from PLAN.md section 2): when a device's segment *set* changes, every
 * other replica refolds that device's directory from scratch (see [JournalIndex.refresh]). This
 * means dropped events vanish consistently everywhere: no replica keeps a stale copy of an event
 * we dropped, because they re-read the whole directory when the snapshot replaces the originals.
 *
 * Rules (conservative: a snapshot may be non-minimal but must never be wrong):
 * - prop-set: keep only the latest own prop-set per (doc, key); older own ones can never win over
 *   the newer own one, and against any other-device write the newest own write is the only own
 *   candidate that could win.
 * - tag-add / tag-remove:
 *     - Keep every own tag-remove that references at least one dot authored by another device, or
 *       any dot that is not itself an own add being dropped in this pass. Kept removes preserve
 *       their tombstones so removed dots stay dead.
 *     - Drop an (own add, own remove-of-that-add) pair together ONLY when the remove's dots are
 *       ALL own and ALL their corresponding adds are also being dropped. Such a pair leaves no
 *       trace and no other device can resurrect it (they refold on set change).
 *     - Keep every live own tag-add (dot not removed by anyone).
 *     - A kept remove may reference a dropped own add; that is fine (unique dots + refold).
 * - tag-rename / tag-merge / tag-style: keep newest own per identity where clearly safe; when
 *   unsure keep it. Here we keep the newest own tag-style per tag; renames/merges are kept in
 *   full (they are tiny, order matters for chaining, and dropping is rarely safe).
 * - trash/restore: keep the latest own trash-or-restore per doc.
 * - doc-delete: keep all own delete tombstones (they win forever).
 * - relink: keep ALL own relinks. Dropping older own relinks would lose entries the
 *   [FoldedState.relinkLosers] view must still expose (each distinct-target claim can be a
 *   loser against a future other-device claim). Relinks are rare and tiny, so keeping all is cheap.
 *
 * Output events preserve their ORIGINAL seq and hlc; nothing is rewritten. The snapshot file name
 * spans the min..max seq of the kept own events.
 */
class Compactor {

    fun compact(ownEvents: List<JournalEvent>, othersEvents: List<JournalEvent>): List<JournalEvent> {
        val classified = classify(ownEvents)
        val (keptAdds, keptRemoves) = compactOrSet(classified.ownAdds, classified.ownRemoves, othersEvents)

        val kept = ArrayList<JournalEvent>()
        kept.addAll(classified.latestPerLwwKey())
        kept.addAll(classified.alwaysKeep)
        kept.addAll(keptAdds)
        kept.addAll(keptRemoves)
        return kept.sortedBy { it.seq }
    }

    /** Own events grouped by how compaction treats them. */
    private class Classified {
        // Last-writer-wins buckets: only the newest own event per identity survives.
        val latestProp = HashMap<Pair<String, DocProperty>, JournalEvent>()
        val latestStyle = HashMap<String, JournalEvent>()
        val latestSetting = HashMap<String, JournalEvent>()
        val latestTrash = HashMap<String, JournalEvent>()

        val ownAdds = ArrayList<JournalEvent>()    // op is TagAdd
        val ownRemoves = ArrayList<JournalEvent>()  // op is TagRemove
        val alwaysKeep = ArrayList<JournalEvent>()  // deletes, relinks, renames, merges, unknowns

        fun latestPerLwwKey(): List<JournalEvent> =
            latestProp.values + latestStyle.values + latestSetting.values + latestTrash.values
    }

    /** Bucket own events (LWW dedup for prop/style/setting/trash, collect adds/removes/keep-all). */
    private fun classify(ownEvents: List<JournalEvent>): Classified {
        val c = Classified()
        for (e in ownEvents) {
            when (val op = e.op) {
                is JournalOp.PropSet -> {
                    val doc = e.doc ?: continue
                    val key = doc to op.key
                    val prev = c.latestProp[key]
                    if (prev == null || e.hlc > prev.hlc) c.latestProp[key] = e
                }
                is JournalOp.TagStyle -> {
                    val prev = c.latestStyle[op.tag]
                    if (prev == null || e.hlc > prev.hlc) c.latestStyle[op.tag] = e
                }
                is JournalOp.SettingSet -> {
                    val prev = c.latestSetting[op.key]
                    if (prev == null || e.hlc > prev.hlc) c.latestSetting[op.key] = e
                }
                is JournalOp.DocTrash, JournalOp.DocRestore -> {
                    val doc = e.doc ?: continue
                    val prev = c.latestTrash[doc]
                    if (prev == null || e.hlc > prev.hlc) c.latestTrash[doc] = e
                }
                is JournalOp.Relink -> c.alwaysKeep.add(e)
                JournalOp.DocDelete -> c.alwaysKeep.add(e)
                is JournalOp.TagRename, is JournalOp.TagMerge -> c.alwaysKeep.add(e)
                is JournalOp.TagAdd -> c.ownAdds.add(e)
                is JournalOp.TagRemove -> c.ownRemoves.add(e)
                is JournalOp.Unknown -> c.alwaysKeep.add(e)
            }
        }
        return c
    }

    /**
     * OR-set compaction: return (keptAdds, keptRemoves). Drops an (own add, own remove-of-that-add)
     * pair only when the remove's dots are ALL own and ALL their adds are also dropped, so the pair
     * leaves no trace and no other device can resurrect it (they refold on set change).
     */
    private fun compactOrSet(
        ownAdds: List<JournalEvent>,
        ownRemoves: List<JournalEvent>,
        othersEvents: List<JournalEvent>,
    ): Pair<List<JournalEvent>, List<JournalEvent>> {
        // All removed dots seen anywhere (own removes + others' removes).
        val removedDotsAll = HashSet<Dot>()
        (ownRemoves + othersEvents).forEach { e ->
            (e.op as? JournalOp.TagRemove)?.dots?.forEach { removedDotsAll.add(it) }
        }
        val ownAddDots = ownAdds.map { it.dot }.toHashSet()

        // A remove is a candidate for dropping only if ALL its dots are own adds. Removes touching
        // any other-device dot, or any dot not in our own add set, must be kept.
        val droppableRemoveCandidates = ownRemoves.filter { e ->
            val dots = (e.op as JournalOp.TagRemove).dots
            dots.isNotEmpty() && dots.all { it in ownAddDots }
        }

        // dot -> own removes referencing it.
        val removesByDot = HashMap<Dot, MutableList<JournalEvent>>()
        ownRemoves.forEach { e ->
            (e.op as JournalOp.TagRemove).dots.forEach { d ->
                removesByDot.getOrPut(d) { ArrayList() }.add(e)
            }
        }
        val droppableRemoveSet = droppableRemoveCandidates.toHashSet()
        // An own add is droppable if it is dead and referenced only by droppable-candidate removes.
        val droppableAddDots = HashSet<Dot>()
        for (dot in ownAddDots) {
            if (dot !in removedDotsAll) continue // still live -> keep the add
            val refs = removesByDot[dot] ?: continue // removed by someone else's remove only
            if (refs.all { it in droppableRemoveSet }) droppableAddDots.add(dot)
        }

        // A droppable-candidate remove can actually be dropped only if ALL its dots are in
        // droppableAddDots (i.e. every add it removes is also being dropped).
        val removesToDrop = droppableRemoveCandidates.filter { e ->
            (e.op as JournalOp.TagRemove).dots.all { it in droppableAddDots }
        }.toHashSet()
        // An add is only truly dropped if the remove(s) referencing it are dropped.
        val finalDroppedAddDots = HashSet<Dot>()
        for (dot in droppableAddDots) {
            val refs = removesByDot[dot] ?: continue
            if (refs.all { it in removesToDrop }) finalDroppedAddDots.add(dot)
        }

        val keptAdds = ownAdds.filter { it.dot !in finalDroppedAddDots }
        val keptRemoves = ownRemoves.filter { it !in removesToDrop }
        return keptAdds to keptRemoves
    }

    /**
     * Read this device's own segments, compact against [othersEvents], write the resulting
     * snapshot (write-once), then delete the original own segments. Snapshot is only written if
     * there is at least one kept event; originals are deleted only after a successful write.
     */
    suspend fun compactOwnSegments(
        store: SegmentStore,
        deviceId: String,
        othersEvents: List<JournalEvent>,
    ): SegmentRef? {
        val ownRefs = store.listSegments().filter { it.deviceId == deviceId }
        if (ownRefs.isEmpty()) return null
        val ownEvents = ownRefs.flatMap { JournalCodec.decode(store.readSegment(it)) }
        if (ownEvents.isEmpty()) return null

        val compacted = compact(ownEvents, othersEvents)
        if (compacted.isEmpty()) return null

        val firstSeq = compacted.minOf { it.seq }
        val lastSeq = compacted.maxOf { it.seq }
        val name = SegmentRef.snapshotName(firstSeq, lastSeq)
        store.writeSegment(deviceId, name, JournalCodec.encodeLines(compacted))
        // Delete originals only after the snapshot exists.
        for (ref in ownRefs) {
            if (ref.fileName == name) continue
            store.deleteSegment(ref)
        }
        return SegmentRef(deviceId, name)
    }
}
