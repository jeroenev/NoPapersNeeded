package dev.npn.app

import dev.npn.core.app.DocLifecycle
import dev.npn.core.app.MetadataWriter
import dev.npn.core.app.TagAdmin

// Write-side test doubles, one per write facet. Each records its calls so tests can assert what
// was invoked, and each `fail*` hook makes the matching op throw.

/** Per-document and bulk metadata edits. */
class FakeMetadataWriter : MetadataWriter {
    val addedTags = mutableListOf<Pair<List<String>, String>>()
    val removedTags = mutableListOf<Pair<List<String>, String>>()
    val reviewedSets = mutableListOf<Pair<List<String>, Boolean>>()

    /** Every setField call (both single- and multi-doc overloads) as (docIds, key, value). */
    val fieldSets = mutableListOf<Triple<List<String>, String, String?>>()
    val batchedEdits = mutableListOf<Triple<List<String>, List<String>, Map<String, String?>>>()

    /** If set, every batched op (addTag/removeTag/setReviewed/applyEdits) throws this. */
    var failBatchOps: (() -> Nothing)? = null

    /** addTag throws when any of these ids is in the call's docIds (per-doc partial-failure testing). */
    var failAddTagForDocs: Set<String> = emptySet()

    /** removeTag throws when any of these ids is in the call's docIds (per-doc partial-failure testing). */
    var failRemoveTagForDocs: Set<String> = emptySet()

    /** applyEdits throws when any of these ids is in the call's docIds (per-doc partial-failure testing). */
    var failApplyEditsForDocs: Set<String> = emptySet()

    /** If set, setField throws. */
    var failSetField: (() -> Nothing)? = null

    override suspend fun addTag(
        docIds: List<String>,
        tag: String,
    ) {
        failBatchOps?.invoke()
        if (docIds.any { it in failAddTagForDocs }) error("addTag failed for $docIds")
        addedTags += docIds to tag
    }

    override suspend fun removeTag(
        docIds: List<String>,
        tag: String,
    ) {
        failBatchOps?.invoke()
        if (docIds.any { it in failRemoveTagForDocs }) error("removeTag failed for $docIds")
        removedTags += docIds to tag
    }

    override suspend fun setReviewed(
        docIds: List<String>,
        reviewed: Boolean,
    ) {
        failBatchOps?.invoke()
        reviewedSets += docIds to reviewed
    }

    override suspend fun setField(
        docId: String,
        key: String,
        value: String?,
    ) {
        failSetField?.invoke()
        fieldSets += Triple(listOf(docId), key, value)
    }

    override suspend fun setField(
        docIds: List<String>,
        key: String,
        value: String?,
    ) {
        failSetField?.invoke()
        fieldSets += Triple(docIds, key, value)
    }

    override suspend fun applyEdits(
        docIds: List<String>,
        addTags: List<String>,
        fields: Map<String, String?>,
    ) {
        failBatchOps?.invoke()
        if (docIds.any { it in failApplyEditsForDocs }) error("applyEdits failed for $docIds")
        batchedEdits += Triple(docIds, addTags, fields)
    }
}

/** Vocabulary-level tag administration. */
class FakeTagAdmin : TagAdmin {
    val renamedTags = mutableListOf<Pair<String, String>>()
    val mergedTags = mutableListOf<Pair<String, String>>()
    val deletedTags = mutableListOf<String>()
    val tagStyles = mutableListOf<Triple<String, String?, String?>>()

    /** If set, every op on this facet throws. */
    var failTagAdmin: (() -> Nothing)? = null

    /** Ops on these tags (a merge by its source) throw, for per-tag partial-failure testing. */
    var failTags: Set<String> = emptySet()

    private fun failFor(tag: String) {
        failTagAdmin?.invoke()
        if (tag in failTags) error("cannot write tag $tag")
    }

    override suspend fun renameTag(
        from: String,
        to: String,
    ) {
        failFor(from)
        renamedTags += from to to
    }

    override suspend fun mergeTag(
        from: String,
        to: String,
    ) {
        failFor(from)
        mergedTags += from to to
    }

    override suspend fun deleteTag(name: String) {
        failFor(name)
        deletedTags += name
    }

    override suspend fun setTagStyle(
        tag: String,
        color: String?,
        icon: String?,
    ) {
        failFor(tag)
        tagStyles += Triple(tag, color, icon)
    }
}

/** Trash lifecycle plus file rename/move. [failTrashDocIds] fails [trashDoc] for exactly those ids. */
class FakeDocLifecycle : DocLifecycle {
    val trashedDocs = mutableListOf<String>()
    val restoredDocs = mutableListOf<String>()
    val deletedDocs = mutableListOf<String>()
    val renamedFiles = mutableListOf<Pair<String, String>>()
    val movedFiles = mutableListOf<Pair<String, String>>()

    /** trashDoc throws for these ids only (per-doc partial-failure testing). */
    var failTrashDocIds: Set<String> = emptySet()

    /** If set, restoreDoc throws. */
    var failRestore: (() -> Nothing)? = null

    override suspend fun trashDoc(docId: String) {
        if (docId in failTrashDocIds) error("trash failed for $docId")
        trashedDocs += docId
    }

    override suspend fun restoreDoc(docId: String) {
        failRestore?.invoke()
        restoredDocs += docId
    }

    override suspend fun deleteDocPermanently(docId: String) {
        deletedDocs += docId
    }

    override suspend fun renameFile(
        docId: String,
        newFileName: String,
    ) {
        renamedFiles += docId to newFileName
    }

    override suspend fun moveFile(
        docId: String,
        newDirRelPath: String,
    ) {
        movedFiles += docId to newDirRelPath
    }
}
