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
    val titleSets = mutableListOf<Pair<String, String?>>()
    val notesSets = mutableListOf<Pair<String, String?>>()
    val correspondentSets = mutableListOf<Pair<List<String>, String?>>()
    val docTypeSets = mutableListOf<Pair<List<String>, String?>>()
    val docDateSets = mutableListOf<Pair<String, String?>>()
    val fieldSets = mutableListOf<Triple<String, String, String?>>()

    /** If set, every batched op (addTag/removeTag/setReviewed) throws this. */
    var failBatchOps: (() -> Nothing)? = null

    /** addTag throws when any of these ids is in the call's docIds (per-doc partial-failure testing). */
    var failAddTagForDocs: Set<String> = emptySet()

    /** removeTag throws when any of these ids is in the call's docIds (per-doc partial-failure testing). */
    var failRemoveTagForDocs: Set<String> = emptySet()

    /** If set, setTitle throws. */
    var failSetTitle: (() -> Nothing)? = null

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

    override suspend fun setCorrespondent(
        docIds: List<String>,
        value: String?,
    ) {
        correspondentSets += docIds to value
    }

    override suspend fun setDocType(
        docIds: List<String>,
        value: String?,
    ) {
        docTypeSets += docIds to value
    }

    override suspend fun setDocDate(
        docId: String,
        isoDate: String?,
    ) {
        docDateSets += docId to isoDate
    }

    override suspend fun setTitle(
        docId: String,
        title: String?,
    ) {
        failSetTitle?.invoke()
        titleSets += docId to title
    }

    override suspend fun setNotes(
        docId: String,
        notes: String?,
    ) {
        notesSets += docId to notes
    }

    override suspend fun setField(
        docId: String,
        key: String,
        value: String?,
    ) {
        failSetField?.invoke()
        fieldSets += Triple(docId, key, value)
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

    override suspend fun renameTag(
        from: String,
        to: String,
    ) {
        failTagAdmin?.invoke()
        renamedTags += from to to
    }

    override suspend fun mergeTag(
        from: String,
        to: String,
    ) {
        failTagAdmin?.invoke()
        mergedTags += from to to
    }

    override suspend fun deleteTag(name: String) {
        failTagAdmin?.invoke()
        deletedTags += name
    }

    override suspend fun setTagStyle(
        tag: String,
        color: String?,
        icon: String?,
    ) {
        failTagAdmin?.invoke()
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
