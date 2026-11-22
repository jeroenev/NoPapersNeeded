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
@Suppress("TooManyFunctions") // One accumulator answers every view; a split would split the state they share.
internal class FoldedState {
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

    private data class RenameOp(
        val from: String,
        val to: String,
        val hlc: Hlc,
        val isMerge: Boolean,
    )

    // --- raw prop data: doc -> key -> winning (value, hlc) ---
    private val props = HashMap<String, HashMap<SystemProperty, PropVal>>()

    // --- every path a storagePath event named for a doc, winning or not (a grow-only set) ---
    private val namedPaths = HashMap<String, HashSet<String>>()

    /** One last-writer-wins register: its winning value (null for a clear) and the HLC that won it. */
    internal data class PropVal(
        val value: String?,
        override val hlc: Hlc,
    ) : HlcStamped

    // --- raw custom-field data: doc -> free-string key -> winning (value, hlc) ---
    private val fields = HashMap<String, HashMap<String, PropVal>>()

    // --- trash state: doc -> latest (trashed?, origPath, hlc); plus delete and undelete, LWW ---
    private val trash = HashMap<String, TrashVal>()
    private val deleted = HashMap<String, Hlc>() // doc -> hlc of the latest delete
    private val undeleted = HashMap<String, Hlc>() // doc -> hlc of the latest undelete

    private data class TrashVal(
        val trashed: Boolean,
        val origPath: String?,
        override val hlc: Hlc,
    ) : HlcStamped

    // --- relinks: oldDoc -> all claims; winner is highest hlc ---
    private val relinks = HashMap<String, ArrayList<RelinkClaim>>()

    // [predecessorsOf] for every target, derived on demand and cached until the next relink applies.
    private var predecessorsCache: Map<String, List<String>>? = null

    private data class RelinkClaim(
        val newDoc: String,
        val hlc: Hlc,
    )

    // --- tag styles: tag -> LWW (color, icon, hlc) ---
    private val tagStyles = HashMap<String, StyleVal>()

    internal data class StyleVal(
        val color: String?,
        val icon: String?,
        override val hlc: Hlc,
    ) : HlcStamped

    // --- library-wide synced settings: key -> LWW (value, hlc) ---
    private val settings = HashMap<String, PropVal>()

    // --- field registry: key -> LWW (definition-or-null, hlc); null means removed ---
    private val fieldDefs = HashMap<String, DefVal>()

    private data class DefVal(
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
        if (applyLibraryWide(event.op, event.hlc)) return
        // Every remaining op is addressed to a document; one without a doc folds to nothing.
        val doc = event.doc ?: return
        applyToDoc(doc, event)
    }

    /** Apply [op] when it is library-wide (it ignores the event's doc); false for a per-document op. */
    private fun applyLibraryWide(
        op: JournalOp,
        hlc: Hlc,
    ): Boolean {
        when (op) {
            is JournalOp.TagRemove -> removedDots.addAll(op.dots)

            is JournalOp.TagRename -> addRename(RenameOp(op.from, op.to, hlc, isMerge = false))

            is JournalOp.TagMerge -> addRename(RenameOp(op.from, op.to, hlc, isMerge = true))

            is JournalOp.TagStyle -> tagStyles.putIfNewerHlc(op.tag, hlc) { StyleVal(op.color, op.icon, hlc) }

            is JournalOp.SettingSet -> settings.putIfNewerHlc(op.key, hlc) { PropVal(op.value, hlc) }

            is JournalOp.FieldDefSet -> fieldDefs.putIfNewerHlc(op.key, hlc) { DefVal(op.def, hlc) }

            // Tolerant skip: an op from a newer build folds to nothing here.
            is JournalOp.Unknown -> Unit

            is JournalOp.TagAdd, is JournalOp.PropSet, is JournalOp.FieldSet, is JournalOp.DocTrash,
            JournalOp.DocRestore, JournalOp.DocDelete, JournalOp.DocUndelete, is JournalOp.Relink,
            -> return false
        }
        return true
    }

    private fun addRename(rename: RenameOp) {
        renames.add(rename)
        sortedRenamesCache = null
    }

    /** Apply a per-document op to [doc]; library-wide ops never reach here ([applyLibraryWide]). */
    private fun applyToDoc(
        doc: String,
        event: JournalEvent,
    ) {
        val hlc = event.hlc
        when (val op = event.op) {
            is JournalOp.TagAdd -> {
                tagAdds.getOrPut(doc) { HashMap() }.getOrPut(op.tag) { HashMap() }[event.dot] = hlc
            }

            is JournalOp.PropSet -> {
                props.getOrPut(doc) { HashMap() }.putIfNewerHlc(op.key, hlc) { PropVal(op.value, hlc) }
                val namesStoragePath = op.key == SystemProperty.STORAGE_PATH
                if (namesStoragePath) op.value?.let { namedPaths.getOrPut(doc) { HashSet() }.add(it) }
            }

            is JournalOp.FieldSet -> {
                fields.getOrPut(doc) { HashMap() }.putIfNewerHlc(op.key, hlc) { PropVal(op.value, hlc) }
            }

            is JournalOp.DocTrash -> {
                trash.putIfNewerHlc(doc, hlc) { TrashVal(trashed = true, op.origPath, hlc) }
            }

            JournalOp.DocRestore -> {
                trash.putIfNewerHlc(doc, hlc) { TrashVal(trashed = false, null, hlc) }
            }

            JournalOp.DocDelete -> {
                deleted.keepLatest(doc, hlc)
            }

            JournalOp.DocUndelete -> {
                undeleted.keepLatest(doc, hlc)
                trash.putIfNewerHlc(doc, hlc) { TrashVal(trashed = false, null, hlc) }
            }

            is JournalOp.Relink -> {
                relinks.getOrPut(doc) { ArrayList() }.add(RelinkClaim(op.newDoc, hlc))
                predecessorsCache = null
            }

            // Library-wide: [applyLibraryWide] folded these already.
            is JournalOp.TagRemove, is JournalOp.TagRename, is JournalOp.TagMerge, is JournalOp.TagStyle,
            is JournalOp.SettingSet, is JournalOp.FieldDefSet, is JournalOp.Unknown,
            -> {}
        }
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
    fun effectiveTags(doc: String): Set<String> =
        LinkedHashSet<String>().apply { forEachLiveAdd(doc) { name, _ -> add(name) } }

    /** Every live add of [doc] (its dot not in [removedDots]), with its effective (post-rename) name. */
    private inline fun forEachLiveAdd(
        doc: String,
        action: (name: String, dot: Dot) -> Unit,
    ) {
        val byTag = tagAdds[doc] ?: return
        val sortedRenames = sortedRenames
        for ((literal, dots) in byTag) {
            for ((dot, addHlc) in dots) {
                if (dot !in removedDots) action(applyRenames(literal, addHlc, sortedRenames), dot)
            }
        }
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

    /**
     * Effective value of [doc]'s own register for a single-value property, or null if unset/cleared.
     * A test seam: main code reads [inheritedProp].
     */
    internal fun effectiveProp(
        doc: String,
        key: SystemProperty,
    ): String? = props[doc]?.get(key)?.value

    /**
     * Whether any `storagePath` event, winning or not, named [path] for [doc] or a document it
     * [predecessorsOf]: a place some device was told to put the document, so a file found there may
     * be the mirror of that device's move rather than a move of its own
     * ([dev.npn.core.index.ExternalDocumentMoves]).
     */
    fun namedStoragePath(
        doc: String,
        path: String,
    ): Boolean = chainOf(doc).any { namedPaths[it]?.contains(path) == true }

    /**
     * Winning version, including explicit clears, of [doc]'s own register for [key]. A test seam:
     * main code reads [inheritedPropVersion].
     */
    internal fun propVersion(
        doc: String,
        key: SystemProperty,
    ): Hlc? = props[doc]?.get(key)?.hlc

    /**
     * Effective custom fields for a document: only keys whose winning value is non-null (a null
     * value means the field was cleared). Ordered by key for deterministic projection. Values are
     * read in the registry type's canonical form ([FieldValues.normalized]), so a number an older
     * build stored as `007` folds to `7`. [doc]'s own registers alone: every other reader goes
     * through [inheritedFields], which this answers for a document without predecessors.
     */
    internal fun effectiveFields(doc: String): Map<String, String> = fields[doc]?.let(::nonNullFields).orEmpty()

    /**
     * [doc]'s own field registers, clears included, as the fold holds them. For the auto-match
     * rules, which fill only a field no register of the chain holds (`RuleApplier`); every other
     * reader goes through [inheritedFields].
     */
    internal fun fieldRegisters(doc: String): Map<String, PropVal> = fields[doc].orEmpty()

    /**
     * Every tag [doc] has ever been given, a removed one included, by the name it was added under
     * and by the name a rename or merge gave it since. For the auto-match rules, which add only a
     * tag a document never carried, so they do not undo a removal.
     */
    fun tagsEverAdded(doc: String): Set<String> {
        val byTag = tagAdds[doc] ?: return emptySet()
        val sortedRenames = sortedRenames
        val out = LinkedHashSet<String>()
        for ((literal, dots) in byTag) {
            out += literal
            for (addHlc in dots.values) out += applyRenames(literal, addHlc, sortedRenames)
        }
        return out
    }

    // ---- a document with what it inherited: its own registers and its predecessors' as one ----

    /** [doc] and every [predecessorsOf] it: the documents whose registers [doc]'s views read as one. */
    internal fun chainOf(doc: String): List<String> = listOf(doc) + predecessorsOf(doc)

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
            fields[id]?.forEach { (key, register) -> winners.putIfNewerHlc(key, register.hlc) { register } }
        }
        return nonNullFields(winners)
    }

    /** [doc]'s value of [key] over its own register and its [predecessorsOf]'s, as [inheritedFields]. */
    fun inheritedProp(
        doc: String,
        key: SystemProperty,
    ): String? = inheritedRegister(doc, key)?.value

    /** Winning inherited version, explicit clears included, for device-local observation baselines. */
    fun inheritedPropVersion(
        doc: String,
        key: SystemProperty,
    ): Hlc? = inheritedRegister(doc, key)?.hlc

    private fun inheritedRegister(
        doc: String,
        key: SystemProperty,
    ): PropVal? = chainOf(doc).mapNotNull { props[it]?.get(key) }.maxByOrNull { it.hlc }

    /** [doc]'s tags with its [predecessorsOf]'s: their live adds are one OR-set ([tagRemovalOps]). */
    fun inheritedTags(doc: String): Set<String> = chainOf(doc).flatMapTo(LinkedHashSet()) { effectiveTags(it) }

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

    /** Whether [doc] is one the library shows: neither trashed nor deleted, nor superseded by a relink. */
    fun isCurrent(doc: String): Boolean = hiddenBy(doc) == null && resolveDocId(doc) == doc

    /**
     * Where [doc]'s file is meant to be: its storage path with what it inherited ([inheritedProp]),
     * or null when no path is intended or [doc] is not [isCurrent], as a hidden document's file goes
     * nowhere.
     */
    fun intendedPath(doc: String): String? =
        if (isCurrent(doc)) inheritedProp(doc, SystemProperty.STORAGE_PATH) else null

    /**
     * Every document in the trash now ([isTrashed]) and not superseded by a relink: the ones the
     * Trash shows, which the projection gives a row whether or not this index ever saw their file.
     */
    fun trashedDocs(): Set<String> = trash.keys.filterTo(LinkedHashSet()) { isTrashed(it) && resolveDocId(it) == it }

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
    private fun deletedAtMillis(doc: String): Long? = if (isDeleted(doc)) deleted[doc]?.physicalMillis else null

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
     * The inherited views read [doc]'s registers and theirs as one ([chainOf]).
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
    internal fun tagStyle(tag: String): StyleVal? = tagStyles[tag]

    /**
     * Every tag a style keeps alive though no document need carry it: one the user created on the
     * Tags screen, or styled. A fold projects these so a rebuilt index shows them again, and
     * [dev.npn.core.db.TagDao.pruneUnreferenced] keeps them. A cleared style drops out.
     */
    internal fun retainedTags(): Set<String> = tagStyles.filterValues { it.color != null || it.icon != null }.keys

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
    private fun liveTagDots(
        doc: String,
        effectiveName: String,
    ): List<Dot> = ArrayList<Dot>().apply { forEachLiveAdd(doc) { name, dot -> if (name == effectiveName) add(dot) } }

    /**
     * Build the OR-set `tag-remove` [PendingOp]s for [tag] over [docIds] from THIS state's live
     * dots: for each doc, list the dots currently observed for `(doc, tag)` and for its
     * [predecessorsOf], as [inheritedTags] reads them (a tombstone names dots, whichever document it
     * is addressed to), each dot once, and emit a remove carrying them, skipping docs that do not
     * hold the tag (empty dots).
     *
     * Outside `core` this is the ONLY way to construct a `tag-remove` op ([PendingOp.tagRemove], the
     * [PendingOp] constructor and [JournalOp.TagRemove]'s are `internal`), so a correct removal can
     * only be built against a live [FoldedState] read under the
     * [dev.npn.core.index.JournalCoordinator] mutex (via `commitBuilt`). A caller with no live state
     * cannot mint one; inside `core`, only the journal decoder and tests build one otherwise.
     */
    fun tagRemovalOps(
        docIds: Collection<String>,
        tag: String,
    ): List<PendingOp> {
        val listed = HashSet<Dot>()
        // A document listed twice finds its dots listed already, so it gets one remove.
        return docIds.mapNotNull { docId ->
            val dots = chainOf(docId).flatMap { liveTagDots(it, tag) }.filter(listed::add)
            if (dots.isEmpty()) null else PendingOp.tagRemove(docId, tag, dots)
        }
    }

    /**
     * The ops that bring [doc] back once its file is in the library again at [relPath]: a
     * `doc-undelete` when it is deleted, else a `doc-restore` when it is trashed, each with where
     * the file now is, as a restore from the trash records it. Empty when nothing hides it, and for
     * a deleted document unless [writeUndelete]: builds before this one refuse a `doc-undelete`, so
     * it is written only once [JournalOp.DocUndelete.WRITTEN] is on.
     */
    fun restoreOps(
        doc: String,
        relPath: String,
        writeUndelete: Boolean,
    ): List<PendingOp> {
        val restore =
            when {
                isDeleted(doc) -> if (writeUndelete) PendingOp.docUndelete(doc) else return emptyList()
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

/** A last-writer-wins register's value, stamped with the HLC it was written at ([putIfNewerHlc]). */
internal interface HlcStamped {
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
