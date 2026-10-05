package dev.npn.app

import dev.npn.core.app.DocLifecycle
import dev.npn.core.app.TagAdmin

// Write-side test doubles for the tag and lifecycle facets; the metadata writer's is shared, in
// :core-testing. Each records its calls so tests can assert what was invoked, and each `fail*`
// hook makes the matching op throw.

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
