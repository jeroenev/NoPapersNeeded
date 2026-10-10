package dev.npn.app

import dev.npn.core.app.DocLifecycle
import dev.npn.core.app.PermanentDeleteOutcome
import dev.npn.core.app.PermanentDeleteTally
import dev.npn.core.app.RelocationOutcome
import dev.npn.core.app.TagAdmin
import dev.npn.core.app.TrashReceipt
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CompletableDeferred

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

    /** trashDoc finds these already trashed or deleted elsewhere, and trashes nothing. */
    var goneDocIds: Set<String> = emptySet()

    /** If set, trashDoc waits for it first. */
    var trashGate: CompletableDeferred<Unit>? = null

    /** If set, restoreDoc throws. */
    var failRestore: (() -> Nothing)? = null

    /** deleteDocPermanently deletes these, but their files cannot be removed. */
    var filesLeftDocIds: Set<String> = emptySet()

    /** What renameFile and moveFile answer. */
    var relocation: RelocationOutcome = RelocationOutcome.MOVED

    /** The trash time each document trashed here has now, as a receipt reports it. */
    private val trashTimes = HashMap<String, Long>()
    private var lastTrashTime = 0L

    override suspend fun trashDoc(docId: String): TrashReceipt? {
        trashGate?.await()
        if (docId in failTrashDocIds) error("trash failed for $docId")
        if (docId in goneDocIds) return null
        trashedDocs += docId
        trashTimes[docId] = ++lastTrashTime
        return TrashReceipt(docId, lastTrashTime)
    }

    /** [docId] restored and trashed again since, as by another device: its earlier trash's Undo leaves it. */
    fun trashAgain(docId: String) {
        trashTimes[docId] = ++lastTrashTime
    }

    override suspend fun restoreDoc(docId: String) {
        failRestore?.invoke()
        restoredDocs += docId
    }

    override suspend fun restoreDocs(docIds: List<String>): Int =
        docIds.count { runCatchingCancellable { restoreDoc(it) }.isFailure }

    override suspend fun undoTrash(trashes: List<TrashReceipt>): Int =
        restoreDocs(trashes.filter { trashTimes[it.docId] == it.trashedAt }.map { it.docId })

    override suspend fun deleteDocPermanently(docId: String): PermanentDeleteOutcome {
        deletedDocs += docId
        return if (docId in filesLeftDocIds) PermanentDeleteOutcome.FILE_LEFT else PermanentDeleteOutcome.DELETED
    }

    override suspend fun deleteDocsPermanently(docIds: List<String>): PermanentDeleteTally {
        val outcomes = docIds.map { runCatchingCancellable { deleteDocPermanently(it) } }
        return PermanentDeleteTally(
            failed = outcomes.count { it.isFailure },
            filesLeft = outcomes.count { it.getOrNull() == PermanentDeleteOutcome.FILE_LEFT },
        )
    }

    override suspend fun renameFile(
        docId: String,
        newFileName: String,
    ): RelocationOutcome {
        renamedFiles += docId to newFileName
        return relocation
    }

    override suspend fun moveFile(
        docId: String,
        newDirRelPath: String,
    ): RelocationOutcome {
        movedFiles += docId to newDirRelPath
        return relocation
    }
}
