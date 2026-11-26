package dev.npn.core.app

import dev.npn.core.db.NpnDatabase
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.TagRetention
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldValues
import dev.npn.core.model.SystemProperty
import dev.npn.core.pathrules.StoragePathRuleApplicator
import dev.npn.core.util.NpnLog
import dev.npn.core.util.Sensitive.Kind.TAG
import dev.npn.core.util.runLogged
import dev.npn.core.util.sensitive

/**
 * Journal-backed write side. Every metadata change is one or more [PendingOp]s committed through
 * the [JournalCoordinator], which serializes access to the live [dev.npn.core.journal.FoldedState]
 * (needed to build correct OR-set tag removes) and refolds+applies to the DB in the same critical
 * section.
 *
 * A document's existence and location (trash, restore, delete for good, rename, move) are
 * [DocLifecycleImpl]'s.
 *
 * What a write sets off once it is published (the storage-path rule, the auto-match rules) runs per
 * document through [followUp]: the write landed whatever they do, so their failure is logged, never
 * thrown.
 */
internal class MetadataWriterImpl(
    private val lifetime: SessionLifetime,
    private val coordinator: JournalCoordinator,
    private val db: NpnDatabase,
    private val pathRuleApplicator: StoragePathRuleApplicator,
    private val log: NpnLog,
    private val applyAutoMatch: suspend (String) -> Unit,
) : MetadataWriter,
    TagAdmin {
    private val tagDao get() = db.tagDao()

    // Each public write runs whole as work the session owns, its follow-ups included.
    private suspend fun <T> owned(block: suspend () -> T): T = lifetime.owned(block)

    override suspend fun addTag(
        docIds: List<String>,
        tag: String,
    ) = owned {
        val t = tag.trim()
        if (t.isEmpty() || docIds.isEmpty()) return@owned
        coordinator.commitAndApply(docIds.map { PendingOp.tagAdd(it, t) })
        followUp(docIds)
    }

    override suspend fun removeTag(
        docIds: List<String>,
        tag: String,
    ) = owned {
        val t = tag.trim()
        if (t.isEmpty() || docIds.isEmpty()) return@owned
        coordinator.commitBuilt { state -> state.tagRemovalOps(docIds, t) }
        followUp(docIds)
    }

    override suspend fun setReviewed(
        docIds: List<String>,
        reviewed: Boolean,
    ) = owned {
        if (docIds.isEmpty()) return@owned
        val v = if (reviewed) "true" else "false"
        coordinator.commitAndApply(docIds.map { PendingOp.propSet(it, SystemProperty.REVIEWED, v) })
    }

    /**
     * Journal-only write of the OCR-engine pin (an LWW property). Not part of any public facet:
     * the single public home is [OcrFacade.setOcrEngine], which commits through here and then
     * re-selects the indexed version on this device.
     */
    suspend fun setOcrEngine(
        docId: String,
        engine: String?,
    ) {
        val v = engine.normalized()
        coordinator.commitAndApply(listOf(PendingOp.propSet(docId, SystemProperty.OCR_ENGINE, v)))
    }

    override suspend fun setField(
        docId: String,
        key: String,
        value: String?,
    ) = setField(listOf(docId), key, value)

    override suspend fun setField(
        docIds: List<String>,
        key: String,
        value: String?,
    ) = owned {
        val k = FieldKeys.normalize(key) ?: return@owned
        val ids = docIds.distinct()
        if (ids.isEmpty()) return@owned
        val changed =
            coordinator.commitBuilt { state ->
                val v = canonical(state, k, value)
                // Against what the document shows, inherited values included: clearing one is a write.
                ids.filter { state.inheritedFields(it)[k] != v }.map { PendingOp.fieldSet(it, k, v) }
            }
        if (changed) followUp(ids, autoMatch = true)
    }

    override suspend fun applyEdits(
        docIds: List<String>,
        addTags: List<String>,
        fields: Map<String, String?>,
        fillOnly: Boolean,
    ) = owned { edit(docIds, addTags, fields, fillOnly, asStored = false) }

    /**
     * The part of extracted [fields] that [docId] can take now, in canonical form: blank on it
     * (inherited values included), registered, and valid for their field's type. Read under the
     * journal's lock against the state a write would land on; nothing is written.
     */
    internal suspend fun fillableExtracted(
        docId: String,
        fields: Map<String, String?>,
    ): Map<String, String> =
        owned {
            var fillable = emptyMap<String, String>()
            coordinator.commitBuilt { state ->
                fillable = state.fillable(docId, fields)
                emptyList()
            }
            fillable
        }

    /**
     * Write the part of extracted [fields] that [docId] can take ([fillableExtracted]), decided at
     * commit time, and with it [marker] (a field key and value) when anything was filled: the marker
     * says where the document's values came from, so it is never written on its own here.
     */
    internal suspend fun fillExtracted(
        docId: String,
        fields: Map<String, String?>,
        marker: Pair<String, String>? = null,
    ) = owned {
        var wrote = false
        coordinator.commitBuilt { state ->
            val values = state.fillable(docId, fields)
            wrote = values.isNotEmpty()
            val marked = if (wrote && marker != null) state.fillable(docId, mapOf(marker)) else emptyMap()
            (values + marked).map { (key, value) -> PendingOp.fieldSet(docId, key, value) }
        }
        if (wrote) followUp(listOf(docId), autoMatch = true)
    }

    private fun FoldedState.fillable(
        docId: String,
        fields: Map<String, String?>,
    ): Map<String, String> {
        val filled = inheritedFields(docId).keys
        return fields
            .mapNotNull { (key, raw) ->
                if (key in filled) return@mapNotNull null
                val def = effectiveFieldDef(key) ?: return@mapNotNull null
                (FieldValues.canonicalize(def.type, raw) as? FieldParse.Ok)?.canonical?.let { key to it }
            }.toMap()
    }

    /**
     * [applyEdits] for metadata carried from another document as it holds it (a split's parts): a
     * value that no longer fits its field's type, left by a retype, is kept as its text rather than
     * refused. Only that copy writes such a value; every other write is checked against the type.
     */
    internal suspend fun copyAsStored(
        docIds: List<String>,
        addTags: List<String>,
        fields: Map<String, String?>,
    ) = edit(docIds, addTags, fields, fillOnly = false, asStored = true)

    private suspend fun edit(
        docIds: List<String>,
        addTags: List<String>,
        fields: Map<String, String?>,
        fillOnly: Boolean,
        asStored: Boolean,
    ) {
        if (docIds.isEmpty()) return
        val tags =
            addTags
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        val ids = docIds.distinct()
        val keyed = fields.mapNotNull { (key, value) -> FieldKeys.normalize(key)?.let { it to value } }
        if (tags.isEmpty() && keyed.isEmpty()) return
        // What each document gets is decided under the lock, against the state the commit lands on.
        var edits: List<DocEdit> = emptyList()
        coordinator.commitBuilt { state ->
            val values = keyed.map { (key, value) -> key to canonical(state, key, value, asStored) }
            edits =
                ids.map { docId -> if (fillOnly) state.lackedBy(docId, tags, values) else DocEdit(docId, tags, values) }
            edits.flatMap { it.ops() }
        }
        // The same follow-ups the individual setters do, but once per document for the whole edit
        // instead of once per value, and only for a document it wrote to. Any field may be a rule's
        // input; tag-only writes do not rerun rules, avoiding tag changes feeding back into them.
        for (edit in edits.filterNot { it.isEmpty }) followUp(listOf(edit.docId), autoMatch = edit.values.isNotEmpty())
    }

    /** What one document gets of an [applyEdits]: the [tags] to add and the field [values] to set. */
    private class DocEdit(
        val docId: String,
        val tags: List<String>,
        val values: List<Pair<String, String?>>,
    ) {
        val isEmpty: Boolean get() = tags.isEmpty() && values.isEmpty()

        fun ops(): List<PendingOp> =
            tags.map { PendingOp.tagAdd(docId, it) } +
                values.map { (key, value) -> PendingOp.fieldSet(docId, key, value) }
    }

    /**
     * The part of [tags] and [values] that [docId] lacks in this state, as it shows them (inherited
     * ones included): tags it does not carry in any case, and values for fields it has none for.
     */
    private fun FoldedState.lackedBy(
        docId: String,
        tags: List<String>,
        values: List<Pair<String, String?>>,
    ): DocEdit {
        val carried = inheritedTags(docId).mapTo(HashSet()) { it.lowercase() }
        val filled = inheritedFields(docId).keys
        return DocEdit(
            docId,
            tags.filterNot { it.lowercase() in carried },
            values.filter { (key, value) -> value != null && key !in filled },
        )
    }

    /**
     * What a write to [docIds] sets off once it is published: the auto-match rules when [autoMatch]
     * (a field value changed), then the storage-path rule, for each document. The write landed
     * whatever they do, so a failure is logged for its document and the others carry on; thrown, it would have a
     * caller take the write for one that did not land, and send it again.
     */
    private suspend fun followUp(
        docIds: Collection<String>,
        autoMatch: Boolean = false,
    ) {
        for (docId in docIds) {
            if (autoMatch) log.runLogged("Auto-matching $docId after a write") { applyAutoMatch(docId) }
            log.runLogged("Applying the storage-path rule to $docId") { pathRuleApplicator.applyFor(docId) }
        }
    }

    /** The follow-up of [docId]'s rename or move ([DocLifecycleImpl]): as for a field write. */
    internal suspend fun followUpRelocation(docId: String) = followUp(listOf(docId), autoMatch = true)

    /** The documents tagged [tag] now, for a [followUp]; failing to list them is logged as its failure is. */
    private suspend fun taggedNow(tag: String): List<String> =
        log
            .runLogged("Listing the documents tagged ${sensitive(tag, TAG)} after a write") {
                tagDao.docIdsWithTag(tag)
            }.orEmpty()

    /**
     * The canonical stored form of [raw] for field [key], per the live registry: an unregistered
     * key is TEXT. Runs under the coordinator mutex so the type it validates against is the one
     * the write is folded with. A value that does not fit the type is refused, or kept as its
     * trimmed text [asStored] ([copyAsStored]).
     */
    private fun canonical(
        state: FoldedState,
        key: String,
        raw: String?,
        asStored: Boolean = false,
    ): String? {
        val type = state.effectiveFieldType(key)
        return when (val parsed = FieldValues.canonicalize(type, raw)) {
            is FieldParse.Ok -> {
                parsed.canonical
            }

            is FieldParse.Invalid -> {
                if (asStored) return parsed.raw
                throw IllegalArgumentException("'${parsed.raw}' is not a valid ${type.wire} for field '$key'")
            }
        }
    }

    override suspend fun createTag(
        name: String,
        color: String?,
        icon: String?,
    ) = owned {
        val t = name.trim()
        if (t.isEmpty()) return@owned
        // The marker keeps the tag; a style is optional.
        val style = if (color != null || icon != null) listOf(PendingOp.tagStyle(t, color, icon)) else emptyList()
        coordinator.commitAndApply(style + PendingOp.settingSet(TagRetention.key(t), "1"))
    }

    override suspend fun renameTag(
        from: String,
        to: String,
    ) = owned { retag(from, to, PendingOp::tagRename) }

    override suspend fun mergeTag(
        from: String,
        to: String,
    ) = owned { retag(from, to, PendingOp::tagMerge) }

    /**
     * Move tag [from] onto [to] with [op] (a rename or a merge), then follow up on what [to] tags.
     *
     * A style is vocabulary, so it follows the name: the target keeps its own style, or takes the
     * source's when it has none, and the source's is cleared in the same write. Without that, a
     * style would stay behind under the old name and the name it moved to would lose its color.
     *
     * A created tag's marker ([TagRetention]) goes with it only onto a name no document carries:
     * a tag documents already carry stays one only while they do, so merging an empty created tag
     * into it does not make it permanent.
     */
    private suspend fun retag(
        from: String,
        to: String,
        op: (String, String) -> PendingOp,
    ) {
        val f = from.trim()
        val t = to.trim()
        if (f.isEmpty() || t.isEmpty() || f == t) return
        coordinator.commitBuilt { state ->
            val source = state.tagStyle(f)
            val target = state.tagStyle(t)
            buildList {
                add(op(f, t))
                if (state.effectiveSetting(TagRetention.key(f)) == "1") {
                    add(PendingOp.settingSet(TagRetention.key(f), null))
                    if (!state.isCarried(t)) add(PendingOp.settingSet(TagRetention.key(t), "1"))
                }
                if (source != null && (source.color != null || source.icon != null)) {
                    add(PendingOp.tagStyle(f, null, null))
                    if (target == null || (target.color == null && target.icon == null)) {
                        add(PendingOp.tagStyle(t, source.color, source.icon))
                    }
                }
            }
        }
        followUp(taggedNow(t))
    }

    override suspend fun deleteTag(name: String) =
        owned {
            val t = name.trim()
            if (t.isEmpty()) return@owned
            val affected = tagDao.docIdsWithTag(t)
            // No dedicated journal op by design: remove the tag from every doc currently holding it,
            // listing all observed dots (OR-set delete-everywhere). A style of its own is cleared in
            // the same write, or a tag the user created and then deleted would stay in the list.
            coordinator.commitBuilt { state ->
                val styled = state.tagStyle(t)?.let { it.color != null || it.icon != null } == true
                state.tagRemovalOps(state.taggedDocs(), t) +
                    (if (styled) listOf(PendingOp.tagStyle(t, null, null)) else emptyList()) +
                    (
                        if (state.effectiveSetting(TagRetention.key(t)) == "1") {
                            listOf(PendingOp.settingSet(TagRetention.key(t), null))
                        } else {
                            emptyList()
                        }
                    )
            }
            followUp(affected)
        }

    override suspend fun setTagStyle(
        tag: String,
        color: String?,
        icon: String?,
    ) = owned {
        val t = tag.trim()
        if (t.isEmpty()) return@owned
        coordinator.commitAndApply(listOf(PendingOp.tagStyle(t, color, icon)))
    }

    /** Trim a system-property input, coercing null/blank to null (clears the property). */
    private fun String?.normalized(): String? = this?.trim()?.ifEmpty { null }
}
