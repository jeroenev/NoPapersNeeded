package dev.npn.core.journal

import dev.npn.core.model.DocProperty

/**
 * The result of folding a set of journal events. All raw data is stored so that [apply] is
 * commutative (order-independent): effective views (tags, props, trash, links) are computed
 * lazily from the raw data, never mutated incrementally in an order-dependent way.
 *
 * Determinism guarantees:
 * - Tags use an OR-set keyed by dot. A remove tombstones exactly the dots it lists, so a
 *   late-arriving add whose dot was already removed stays dead. Re-adding produces a new dot.
 * - Renames/merges are library-wide and applied in HLC order to every add whose HLC is <= the
 *   rename's HLC (chained). Adds strictly after a rename keep their literal name.
 * - Props are last-writer-wins by HLC. Trash/restore is LWW; doc-delete is a permanent
 *   tombstone that wins forever. Relinks resolve to the highest-HLC winner per old doc.
 *
 * Instances are mutable accumulators; [copy]-style snapshotting is not needed because folding
 * is deterministic regardless of the order events were applied.
 */
class FoldedState {

    /** A live tag add: which dot created it and at what HLC. */
    data class TagDot(val dot: Dot, val hlc: Hlc)

    // --- raw tag data ---
    // doc -> literal tag name -> (dot -> hlc) of live adds
    private val tagAdds = HashMap<String, HashMap<String, HashMap<Dot, Hlc>>>()
    // set of all dots ever removed by any tag-remove (tombstones); keep forever
    private val removedDots = HashSet<Dot>()
    // library-wide rename/merge ops, unordered; sorted by HLC when computing effective names
    private val renames = ArrayList<RenameOp>()

    data class RenameOp(val from: String, val to: String, val hlc: Hlc, val isMerge: Boolean)

    // --- raw prop data: doc -> key -> winning (value, hlc) ---
    private val props = HashMap<String, HashMap<DocProperty, PropVal>>()

    data class PropVal(val value: String?, val hlc: Hlc)

    // --- raw custom-field data: doc -> free-string key -> winning (value, hlc) ---
    private val fields = HashMap<String, HashMap<String, PropVal>>()

    // --- trash state: doc -> latest (trashed?, origPath, hlc); plus permanent delete tombstones ---
    private val trash = HashMap<String, TrashVal>()
    private val deleted = HashMap<String, Hlc>() // doc -> hlc of the delete tombstone

    data class TrashVal(val trashed: Boolean, val origPath: String?, val hlc: Hlc)

    // --- relinks: oldDoc -> all claims; winner is highest hlc ---
    private val relinks = HashMap<String, ArrayList<RelinkClaim>>()

    data class RelinkClaim(val newDoc: String, val hlc: Hlc)

    // --- tag styles: tag -> LWW (color, icon, hlc) ---
    private val tagStyles = HashMap<String, StyleVal>()

    data class StyleVal(val color: String?, val icon: String?, val hlc: Hlc)

    // --- library-wide synced settings: key -> LWW (value, hlc) ---
    private val settings = HashMap<String, PropVal>()

    /** Apply one event. Commutative: final state is identical regardless of apply order. */
    fun apply(event: JournalEvent) {
        when (val op = event.op) {
            is JournalOp.TagAdd -> {
                val doc = event.doc ?: return
                val byTag = tagAdds.getOrPut(doc) { HashMap() }
                val dots = byTag.getOrPut(op.tag) { HashMap() }
                dots[event.dot] = event.hlc
            }
            is JournalOp.TagRemove -> {
                op.dots.forEach { removedDots.add(it) }
            }
            is JournalOp.PropSet -> {
                val doc = event.doc ?: return
                val byKey = props.getOrPut(doc) { HashMap() }
                val existing = byKey[op.key]
                if (existing == null || event.hlc > existing.hlc) {
                    byKey[op.key] = PropVal(op.value, event.hlc)
                }
            }
            is JournalOp.FieldSet -> {
                val doc = event.doc ?: return
                val byKey = fields.getOrPut(doc) { HashMap() }
                val existing = byKey[op.key]
                if (existing == null || event.hlc > existing.hlc) {
                    byKey[op.key] = PropVal(op.value, event.hlc)
                }
            }
            is JournalOp.DocTrash -> {
                val doc = event.doc ?: return
                mergeTrash(doc, TrashVal(trashed = true, origPath = op.origPath, hlc = event.hlc))
            }
            JournalOp.DocRestore -> {
                val doc = event.doc ?: return
                mergeTrash(doc, TrashVal(trashed = false, origPath = null, hlc = event.hlc))
            }
            JournalOp.DocDelete -> {
                val doc = event.doc ?: return
                val existing = deleted[doc]
                if (existing == null || event.hlc > existing) deleted[doc] = event.hlc
            }
            is JournalOp.Relink -> {
                val doc = event.doc ?: return
                relinks.getOrPut(doc) { ArrayList() }.add(RelinkClaim(op.newDoc, event.hlc))
            }
            is JournalOp.TagRename ->
                renames.add(RenameOp(op.from, op.to, event.hlc, isMerge = false))
            is JournalOp.TagMerge ->
                renames.add(RenameOp(op.from, op.to, event.hlc, isMerge = true))
            is JournalOp.TagStyle -> {
                val existing = tagStyles[op.tag]
                if (existing == null || event.hlc > existing.hlc) {
                    tagStyles[op.tag] = StyleVal(op.color, op.icon, event.hlc)
                }
            }
            is JournalOp.SettingSet -> {
                val existing = settings[op.key]
                if (existing == null || event.hlc > existing.hlc) {
                    settings[op.key] = PropVal(op.value, event.hlc)
                }
            }
            is JournalOp.Unknown -> Unit // tolerant skip
        }
    }

    private fun mergeTrash(doc: String, incoming: TrashVal) {
        val existing = trash[doc]
        if (existing == null || incoming.hlc > existing.hlc) trash[doc] = incoming
    }

    // ---------------- effective views (computed lazily from raw data) ----------------

    /**
     * Effective tag names for a document. A tag add is live if its dot is not in [removedDots].
     * The literal tag name of each live add is then rewritten by every rename/merge whose HLC is
     * >= the add's HLC, applied in HLC order (chained a->b, b->c => c).
     */
    fun effectiveTags(doc: String): Set<String> {
        val byTag = tagAdds[doc] ?: return emptySet()
        val sortedRenames = renames.sortedBy { it.hlc }
        val result = LinkedHashSet<String>()
        for ((literal, dots) in byTag) {
            for ((dot, addHlc) in dots) {
                if (dot in removedDots) continue
                result.add(applyRenames(literal, addHlc, sortedRenames))
            }
        }
        return result
    }

    /**
     * Rewrite [literal] by all renames with hlc >= [addHlc], in HLC order. The comparison is
     * `rename.hlc >= addHlc`: a rename concurrent-with-or-after the add follows the add
     * (spec: "an add of the old name concurrent with or after the rename follows the rename").
     */
    private fun applyRenames(literal: String, addHlc: Hlc, sortedRenames: List<RenameOp>): String {
        var name = literal
        for (r in sortedRenames) {
            if (r.hlc >= addHlc && r.from == name) name = r.to
        }
        return name
    }

    /** Effective value of a single-value property, or null if unset/cleared. */
    fun effectiveProp(doc: String, key: DocProperty): String? = props[doc]?.get(key)?.value

    fun allProps(doc: String): Map<DocProperty, String?> =
        props[doc]?.mapValues { it.value.value } ?: emptyMap()

    /**
     * Effective custom fields for a document: only keys whose winning value is non-null (a null
     * value means the field was cleared). Ordered by key for deterministic projection.
     */
    fun effectiveFields(doc: String): Map<String, String> {
        val byKey = fields[doc] ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in byKey.keys.sorted()) {
            byKey[key]?.value?.let { out[key] = it }
        }
        return out
    }



    /** True if the document is permanently deleted (delete tombstone present). Wins forever. */
    fun isDeleted(doc: String): Boolean = deleted.containsKey(doc)

    /** True if the document is currently in trash (and not deleted). */
    fun isTrashed(doc: String): Boolean {
        if (isDeleted(doc)) return true
        return trash[doc]?.trashed == true
    }

    /** Original path recorded by the winning doc-trash, if currently trashed. */
    fun trashOrigPath(doc: String): String? {
        val t = trash[doc] ?: return null
        return if (t.trashed) t.origPath else null
    }

    /**
     * Resolve the effective (current) document id by following the relink chain from [doc].
     * a->b->c yields c. Concurrent relinks for the same old doc resolve to the highest-HLC
     * winner (deviceId tiebreak inside HLC guarantees determinism).
     */
    fun resolveDocId(doc: String): String {
        var current = doc
        val seen = HashSet<String>()
        while (seen.add(current)) {
            val winner = relinkWinner(current) ?: break
            current = winner
        }
        return current
    }

    private fun relinkWinner(oldDoc: String): String? {
        val claims = relinks[oldDoc] ?: return null
        return claims.maxByOrNull { it.hlc }?.newDoc
    }

    /**
     * Relink losers: for each old doc with concurrent relink claims, the claims that did NOT
     * win. The caller imports each loser's target file as a new document with metadata copied.
     * Returned as (oldDoc, losingNewDoc).
     */
    fun relinkLosers(): List<Pair<String, String>> {
        val losers = ArrayList<Pair<String, String>>()
        for ((oldDoc, claims) in relinks) {
            if (claims.size < 2) continue
            val winner = claims.maxByOrNull { it.hlc }!!
            for (c in claims) {
                if (c !== winner && c.newDoc != winner.newDoc) losers.add(oldDoc to c.newDoc)
            }
        }
        return losers
    }

    /** Effective style for a tag, or null if none set. */
    fun tagStyle(tag: String): StyleVal? = tagStyles[tag]

    /** Effective value of a library-wide synced setting, or null if never set/cleared. */
    fun effectiveSetting(key: String): String? = settings[key]?.value

    /** All documents this state has any tag data for (literal keys, before resolveDocId). */
    fun taggedDocs(): Set<String> = tagAdds.keys.toSet()

    /**
     * Live add dots for [doc] whose (post-rename) effective name equals [effectiveName]. These are
     * exactly the dots a `tag-remove` must list to remove the tag from the document under OR-set
     * semantics (spec sec. 2). A dot is live when it is not already tombstoned in [removedDots].
     *
     * The literal add name is rewritten through the same rename chain as [effectiveTags], so
     * removing a tag by its current (renamed) name still targets the original add dots.
     */
    fun liveTagDots(doc: String, effectiveName: String): List<Dot> {
        val byTag = tagAdds[doc] ?: return emptyList()
        val sortedRenames = renames.sortedBy { it.hlc }
        val out = ArrayList<Dot>()
        for ((literal, dots) in byTag) {
            for ((dot, addHlc) in dots) {
                if (dot in removedDots) continue
                if (applyRenames(literal, addHlc, sortedRenames) == effectiveName) out.add(dot)
            }
        }
        return out
    }

    /** All documents this state has any tag, prop, or custom-field data for (before resolveDocId). */
    fun docsWithMetadata(): Set<String> =
        LinkedHashSet<String>().apply {
            addAll(tagAdds.keys)
            addAll(props.keys)
            addAll(fields.keys)
        }

    // --------- accessors used by compaction (raw data, read-only copies) ---------

    internal fun rawRemovedDots(): Set<Dot> = removedDots.toSet()
    internal fun isDotLive(dot: Dot): Boolean = dot !in removedDots
}
