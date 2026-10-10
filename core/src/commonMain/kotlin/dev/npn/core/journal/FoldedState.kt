package dev.npn.core.journal

import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldType
import dev.npn.core.model.FieldValues
import dev.npn.core.model.SystemProperty

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
 * - Props are last-writer-wins by HLC. Trash/restore is LWW, and so is delete/undelete; an
 *   undelete also takes the document out of the trash, and a delete that wins hides the document
 *   whatever its trash state. Relinks resolve to the highest-HLC winner per old doc.
 * - The effective views read one id's own events. The inherited ones read a relink target's
 *   together with its predecessors': one register per field or property, LWW across all of them
 *   (a clear included), and one OR-set of tags.
 *
 * Instances are mutable accumulators; [copy]-style snapshotting is not needed because folding
 * is deterministic regardless of the order events were applied.
 */
@Suppress("TooManyFunctions")
class FoldedState {
    // --- raw tag data ---
    // doc -> literal tag name -> (dot -> hlc) of live adds
    private val tagAdds = HashMap<String, HashMap<String, HashMap<Dot, Hlc>>>()

    // set of all dots ever removed by any tag-remove (tombstones); keep forever
    private val removedDots = HashSet<Dot>()

    // library-wide rename/merge ops, unordered; sorted by HLC when computing effective names.
    // The sorted view is derived on demand and cached, invalidated whenever a rename is applied.
    private val renames = ArrayList<RenameOp>()
    private var sortedRenamesCache: List<RenameOp>? = null

    /** Renames sorted by HLC, computed once per mutation and cached (invalidated on apply). */
    private val sortedRenames: List<RenameOp>
        get() = sortedRenamesCache ?: renames.sortedBy { it.hlc }.also { sortedRenamesCache = it }

    data class RenameOp(
        val from: String,
        val to: String,
        val hlc: Hlc,
        val isMerge: Boolean,
    )

    // --- raw prop data: doc -> key -> winning (value, hlc) ---
    private val props = HashMap<String, HashMap<SystemProperty, PropVal>>()

    data class PropVal(
        val value: String?,
        override val hlc: Hlc,
    ) : HlcStamped

    // --- raw custom-field data: doc -> free-string key -> winning (value, hlc) ---
    private val fields = HashMap<String, HashMap<String, PropVal>>()

    // --- trash state: doc -> latest (trashed?, origPath, hlc); plus delete and undelete, LWW ---
    private val trash = HashMap<String, TrashVal>()
    private val deleted = HashMap<String, Hlc>() // doc -> hlc of the latest delete
    private val undeleted = HashMap<String, Hlc>() // doc -> hlc of the latest undelete

    data class TrashVal(
        val trashed: Boolean,
        val origPath: String?,
        val hlc: Hlc,
    )

    // --- relinks: oldDoc -> all claims; winner is highest hlc ---
    private val relinks = HashMap<String, ArrayList<RelinkClaim>>()

    // [predecessorsOf] for every target, derived on demand and cached until the next relink applies.
    private var predecessorsCache: Map<String, List<String>>? = null

    data class RelinkClaim(
        val newDoc: String,
        val hlc: Hlc,
    )

    // --- tag styles: tag -> LWW (color, icon, hlc) ---
    private val tagStyles = HashMap<String, StyleVal>()

    data class StyleVal(
        val color: String?,
        val icon: String?,
        override val hlc: Hlc,
    ) : HlcStamped

    // --- library-wide synced settings: key -> LWW (value, hlc) ---
    private val settings = HashMap<String, PropVal>()

    // --- field registry: key -> LWW (definition-or-null, hlc); null means removed ---
    private val fieldDefs = HashMap<String, DefVal>()

    data class DefVal(
        val def: FieldDef?,
        override val hlc: Hlc,
    ) : HlcStamped

    /**
     * Highest HLC across all applied events (any device, any op). Used to seed the local write
     * clock ([HlcWatermarkAllocator]) after a fold so new writes are ordered after everything
     * already in the store, even when the wall clock regressed between sessions or another device's
     * clock runs ahead.
     */
    var maxHlc: Hlc = Hlc.ZERO
        private set

    /** Apply one event. Commutative: final state is identical regardless of apply order. */
    fun apply(event: JournalEvent) {
        if (event.hlc > maxHlc) maxHlc = event.hlc
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
                props
                    .getOrPut(doc) { HashMap() }
                    .putIfNewerHlc(op.key, event.hlc) { PropVal(op.value, event.hlc) }
            }

            is JournalOp.FieldSet -> {
                val doc = event.doc ?: return
                fields
                    .getOrPut(doc) { HashMap() }
                    .putIfNewerHlc(op.key, event.hlc) { PropVal(op.value, event.hlc) }
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
                deleted.keepLatest(doc, event.hlc)
            }

            JournalOp.DocUndelete -> {
                val doc = event.doc ?: return
                undeleted.keepLatest(doc, event.hlc)
                mergeTrash(doc, TrashVal(trashed = false, origPath = null, hlc = event.hlc))
            }

            is JournalOp.Relink -> {
                val doc = event.doc ?: return
                relinks.getOrPut(doc) { ArrayList() }.add(RelinkClaim(op.newDoc, event.hlc))
                predecessorsCache = null
            }

            is JournalOp.TagRename -> {
                renames.add(RenameOp(op.from, op.to, event.hlc, isMerge = false))
                sortedRenamesCache = null
            }

            is JournalOp.TagMerge -> {
                renames.add(RenameOp(op.from, op.to, event.hlc, isMerge = true))
                sortedRenamesCache = null
            }

            is JournalOp.TagStyle -> {
                tagStyles.putIfNewerHlc(op.tag, event.hlc) { StyleVal(op.color, op.icon, event.hlc) }
            }

            is JournalOp.SettingSet -> {
                settings.putIfNewerHlc(op.key, event.hlc) { PropVal(op.value, event.hlc) }
            }

            is JournalOp.FieldDefSet -> {
                fieldDefs.putIfNewerHlc(op.key, event.hlc) { DefVal(op.def, event.hlc) }
            }

            // Tolerant skip: an op from a newer build folds to nothing here.
            is JournalOp.Unknown -> {}
        }
    }

    private fun mergeTrash(
        doc: String,
        incoming: TrashVal,
    ) {
        val existing = trash[doc]
        if (existing == null || incoming.hlc > existing.hlc) trash[doc] = incoming
    }

    private fun HashMap<String, Hlc>.keepLatest(
        doc: String,
        hlc: Hlc,
    ) {
        val existing = this[doc]
        if (existing == null || hlc > existing) this[doc] = hlc
    }

    // ---------------- effective views (computed lazily from raw data) ----------------

    /**
     * Effective tag names for a document. A tag add is live if its dot is not in [removedDots].
     * The literal tag name of each live add is then rewritten by every rename/merge whose HLC is
     * >= the add's HLC, applied in HLC order (chained a->b, b->c => c).
     */
    fun effectiveTags(doc: String): Set<String> {
        val byTag = tagAdds[doc] ?: return emptySet()
        val sortedRenames = sortedRenames
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
    private fun applyRenames(
        literal: String,
        addHlc: Hlc,
        sortedRenames: List<RenameOp>,
    ): String {
        var name = literal
        for (r in sortedRenames) {
            if (r.hlc >= addHlc && r.from == name) name = r.to
        }
        return name
    }

    /** Effective value of a single-value property, or null if unset/cleared. */
    fun effectiveProp(
        doc: String,
        key: SystemProperty,
    ): String? = props[doc]?.get(key)?.value

    /** Winning version, including explicit clears, for comparing device-local observation baselines. */
    fun propVersion(
        doc: String,
        key: SystemProperty,
    ): Hlc? = props[doc]?.get(key)?.hlc

    /**
     * Effective custom fields for a document: only keys whose winning value is non-null (a null
     * value means the field was cleared). Ordered by key for deterministic projection. Values are
     * read in the registry type's canonical form ([FieldValues.normalized]), so a number an older
     * build stored as `007` folds to `7`.
     */
    fun effectiveFields(doc: String): Map<String, String> = fields[doc]?.let(::nonNullFields).orEmpty()

    // ---- a document with what it inherited: its own registers and its predecessors' as one ----

    /**
     * [doc]'s fields as [effectiveFields] reads them, over its own registers and its
     * [predecessorsOf]'s as one set: per key, the latest write among them wins, a clear included.
     * A value cleared on [doc] stays cleared, and a chain shows its newest value however its ids sort.
     */
    fun inheritedFields(doc: String): Map<String, String> {
        val predecessors = predecessorsOf(doc)
        if (predecessors.isEmpty()) return effectiveFields(doc)
        val winners = HashMap<String, PropVal>()
        for (id in listOf(doc) + predecessors) {
            fields[id]?.forEach { (key, value) -> winners.putIfNewerHlc(key, value.hlc) { value } }
        }
        return nonNullFields(winners)
    }

    /** [doc]'s value of [key] over its own register and its [predecessorsOf]'s, as [inheritedFields]. */
    fun inheritedProp(
        doc: String,
        key: SystemProperty,
    ): String? = (listOf(doc) + predecessorsOf(doc)).mapNotNull { props[it]?.get(key) }.maxByOrNull { it.hlc }?.value

    /** [doc]'s tags with its [predecessorsOf]'s: their live adds are one OR-set ([tagRemovalOps]). */
    fun inheritedTags(doc: String): Set<String> =
        predecessorsOf(doc).fold(effectiveTags(doc)) { tags, predecessor -> tags + effectiveTags(predecessor) }

    private fun nonNullFields(byKey: Map<String, PropVal>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (key in byKey.keys.sorted()) {
            byKey[key]?.value?.let { out[key] = FieldValues.normalized(effectiveFieldType(key), it) }
        }
        return out
    }

    /** True if the document is deleted: its latest delete is newer than its latest undelete. */
    fun isDeleted(doc: String): Boolean {
        val delete = deleted[doc] ?: return false
        val undelete = undeleted[doc] ?: return true
        return delete > undelete
    }

    /** The winning event that hides [doc] (its delete, else its trash), or null when nothing does. */
    fun hiddenBy(doc: String): Hlc? =
        when {
            isDeleted(doc) -> deleted[doc]
            trash[doc]?.trashed == true -> trash[doc]?.hlc
            else -> null
        }

    /** True if the document is currently in trash. A deleted document is [isDeleted] only, never both. */
    fun isTrashed(doc: String): Boolean = !isDeleted(doc) && trash[doc]?.trashed == true

    /** Original path recorded by the winning doc-trash, if currently trashed. */
    fun trashOrigPath(doc: String): String? {
        val t = trash[doc] ?: return null
        return if (t.trashed) t.origPath else null
    }

    /** Wall time of the winning doc-trash's HLC, if currently trashed. */
    fun trashedAtMillis(doc: String): Long? {
        val t = trash[doc] ?: return null
        return if (t.trashed) t.hlc.physicalMillis else null
    }

    /** Wall time of the winning doc-delete's HLC, while the document is deleted. */
    fun deletedAtMillis(doc: String): Long? = if (isDeleted(doc)) deleted[doc]?.physicalMillis else null

    /**
     * For the trash purge, every document an event names that is deleted, or else not superseded by a
     * relink, mapped to when it was deleted for good ([deletedAtMillis]), else trashed
     * ([trashedAtMillis]), or to null while it is neither.
     */
    fun trashTimes(): Map<String, Long?> {
        val named =
            docsWithMetadata() + trash.keys + deleted.keys + undeleted.keys +
                relinks.values.flatMap { it.map(RelinkClaim::newDoc) }
        return named
            .filter { isDeleted(it) || resolveDocId(it) == it }
            .associateWith { deletedAtMillis(it) ?: trashedAtMillis(it) }
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
            current = relinks[current]?.maxByOrNull { it.hlc }?.newDoc ?: break
        }
        return current
    }

    /**
     * Every older id whose relink chain [resolveDocId]s onto [doc], in id order: a->b->c gives c
     * both a and b. Empty for a document nothing resolves onto, which includes every superseded one.
     */
    fun predecessorsOf(doc: String): List<String> =
        (predecessorsCache ?: reversedRelinks().also { predecessorsCache = it })[doc].orEmpty()

    private fun reversedRelinks(): Map<String, List<String>> {
        val reverse = HashMap<String, MutableList<String>>()
        for (old in relinks.keys) {
            val target = resolveDocId(old)
            if (target != old) reverse.getOrPut(target) { ArrayList() }.add(old)
        }
        reverse.values.forEach { it.sort() }
        return reverse
    }

    /** Effective style for a tag, or null if none set. */
    fun tagStyle(tag: String): StyleVal? = tagStyles[tag]

    /** Effective value of a library-wide synced setting, or null if never set/cleared. */
    fun effectiveSetting(key: String): String? = settings[key]?.value

    /**
     * Every library-wide synced setting ever written, with its effective value: null for one that
     * was cleared. A key absent from the map was never set.
     */
    fun settings(): Map<String, String?> = settings.mapValues { it.value.value }

    /** The live field registry: every key whose winning definition is non-null, in display order. */
    fun effectiveFieldDefs(): List<FieldDef> =
        fieldDefs.values
            .mapNotNull { it.def }
            .sortedWith(compareBy<FieldDef> { it.position }.thenBy { it.key })

    /** The winning definition for [key], or null when never defined or removed. */
    fun effectiveFieldDef(key: String): FieldDef? = fieldDefs[key]?.def

    /**
     * The one rule for "what type is this field": the folded definition's, or TEXT for a key no
     * definition covers. Callers that need a value's canonical form or numeric shadow go through
     * this, so the unregistered-is-text default lives in one place.
     */
    fun effectiveFieldType(key: String): FieldType = effectiveFieldDef(key)?.type ?: FieldType.TEXT

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
    fun liveTagDots(
        doc: String,
        effectiveName: String,
    ): List<Dot> {
        val byTag = tagAdds[doc] ?: return emptyList()
        val sortedRenames = sortedRenames
        val out = ArrayList<Dot>()
        for ((literal, dots) in byTag) {
            for ((dot, addHlc) in dots) {
                if (dot in removedDots) continue
                if (applyRenames(literal, addHlc, sortedRenames) == effectiveName) out.add(dot)
            }
        }
        return out
    }

    /**
     * Build the OR-set `tag-remove` [PendingOp]s for [tag] over [docIds] from THIS state's live
     * dots: for each doc, list the dots currently observed for `(doc, tag)`, its [predecessorsOf]'s
     * included as [inheritedTags] reads them (a tombstone names dots, whichever document it is
     * addressed to), and emit a remove carrying them, skipping docs that do not hold the tag (empty
     * dots). A dot is listed once however many of [docIds] share it.
     *
     * This is the ONLY way to construct a `tag-remove` op ([PendingOp.tagRemove] is `internal`),
     * so a correct removal can only be built against a live [FoldedState] read under the
     * [dev.npn.core.app.JournalCoordinator] mutex (via `commitBuilt`). That makes a removal against
     * stale or fabricated dots unexpressible: a caller with no live state cannot mint one.
     */
    fun tagRemovalOps(
        docIds: Collection<String>,
        tag: String,
    ): List<PendingOp> {
        val listed = HashSet<Dot>()
        return docIds.mapNotNull { docId ->
            val dots = (listOf(docId) + predecessorsOf(docId)).flatMap { liveTagDots(it, tag) }.filter(listed::add)
            if (dots.isEmpty()) null else PendingOp.tagRemove(docId, tag, dots)
        }
    }

    /**
     * The ops that bring [doc] back once its file is in the library again at [relPath]: a
     * `doc-undelete` when it is deleted, else a `doc-restore` when it is trashed, each with where
     * the file now is, as a restore from the trash records it. Empty when nothing hides it.
     */
    fun restoreOps(
        doc: String,
        relPath: String,
    ): List<PendingOp> {
        val restore =
            when {
                isDeleted(doc) -> PendingOp.docUndelete(doc)
                isTrashed(doc) -> PendingOp.docRestore(doc)
                else -> return emptyList()
            }
        return listOf(restore, PendingOp.propSet(doc, SystemProperty.STORAGE_PATH, relPath))
    }

    /** All documents this state has any tag, prop, or custom-field data for (before resolveDocId). */
    fun docsWithMetadata(): Set<String> =
        LinkedHashSet<String>().apply {
            addAll(tagAdds.keys)
            addAll(props.keys)
            addAll(fields.keys)
        }
}

interface HlcStamped {
    val hlc: Hlc
}

/**
 * Last-writer-wins insert: store [value] under [key] only when no value exists yet or [hlc]
 * strictly exceeds the stored value's HLC. Tie-breaking matches every other LWW map (equal HLCs
 * keep the existing value); [value] is built lazily so it is only allocated when it wins.
 */
internal inline fun <K, V : HlcStamped> HashMap<K, V>.putIfNewerHlc(
    key: K,
    hlc: Hlc,
    value: () -> V,
) {
    val existing = this[key]
    if (existing == null || hlc > existing.hlc) this[key] = value()
}
