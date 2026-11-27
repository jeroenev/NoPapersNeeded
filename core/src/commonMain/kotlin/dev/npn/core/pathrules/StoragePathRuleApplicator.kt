package dev.npn.core.pathrules

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.fieldTypes
import dev.npn.core.db.isLive
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.uniquifyByHash
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Applies the active [StoragePathRule] by moving a document to the path derived from its metadata.
 *
 * Moves are only performed when the rule is enabled and the computed path differs from the current
 * one. Trashed, deleted, or missing documents are never moved. Collisions are resolved by suffixing
 * the filename with `-1`, `-2`, etc. The intended path is journaled before physical reconciliation,
 * once: [setPath] writes nothing when the journal already holds that location for the document, as
 * it does while the document's file has not reached it yet (a move that failed, to be tried again).
 *
 * Applications run one at a time, each from its read to its path commit: each reads after the
 * write that asked for it committed, so the last to run sees the latest metadata, and no two pick
 * the same free target. Lock order: [dev.npn.core.rules.RuleApplier]'s lock, this one, then the
 * coordinator's ([setPath] takes it); [applyFor] never runs under the coordinator's lock.
 */
internal class StoragePathRuleApplicator(
    private val fs: LibraryFileSystem,
    private val db: NpnDatabase,
    private val ruleProvider: suspend () -> StoragePathRule?,
    private val setPath: suspend (String, String) -> Unit,
) {
    private val documentDao get() = db.documentDao()

    private val mutex = Mutex()

    /**
     * Compute the active rule's path for [docId] and, when it differs from where the document is,
     * record it as the document's intended path ([setPath]). Does nothing for a disabled, blank or
     * invalid rule, or a document that is not live.
     */
    suspend fun applyFor(docId: String): Unit = mutex.withLock { apply(docId) }

    private suspend fun apply(docId: String) {
        val rule = ruleProvider()?.takeIf { it.enabled && it.template.isNotBlank() } ?: return
        val doc = documentDao.getByDocId(docId)?.takeIf { it.isLive } ?: return
        val context =
            StoragePathContext(
                fields = db.documentFieldDao().valuesOf(docId),
                tags = db.tagDao().tagsForDocument(docId),
                originalFileName = doc.fileName,
                fieldTypes = db.fieldDefDao().fieldTypes(),
            )
        val expanded = StoragePathTemplate.expand(rule.template, context).pathOrNull ?: return
        val target = resolveUniqueTarget(fs, documentDao, docId, expanded, hash = docId)
        if (NpnPaths.normalize(target) != NpnPaths.normalize(doc.relPath)) setPath(docId, target)
    }
}

/**
 * Build a unique library-relative path starting from [target], a `.pdf` added when it has none:
 * [target] while [pathTaken] says it is free for [currentDocId], else `name (7f3a).pdf`, named for
 * the bytes [hash] the document holds ([uniquifyByHash]), so two documents a template maps to one
 * name on two devices that have not synced yet never both take the same other name.
 */
internal suspend fun resolveUniqueTarget(
    fs: LibraryFileSystem,
    documentDao: DocumentDao,
    currentDocId: String?,
    target: String,
    hash: String,
): String {
    val normalized = NpnPaths.normalize(target)
    val withExtension =
        if (NpnPaths.isPdf(NpnPaths.fileName(normalized))) {
            normalized
        } else {
            "$normalized.pdf"
        }
    return uniquifyByHash(withExtension, hash) { pathTaken(fs, documentDao, currentDocId, it) }
}

/**
 * Whether [candidate] is taken for [currentDocId], the document about to occupy it (null for a file
 * that is no document yet): a file is there, or another live document's row names it. A live row
 * whose file went outside the app names its path until a rescan finds it missing; a trashed,
 * deleted or missing row gives its path up to a live document.
 */
internal suspend fun pathTaken(
    fs: LibraryFileSystem,
    documentDao: DocumentDao,
    currentDocId: String?,
    candidate: String,
): Boolean {
    // relPath has a unique index (DocumentEntity), so at most one row can own a candidate.
    val owner = documentDao.getByRelPath(candidate)
    if (owner != null && owner.docId == currentDocId) return false
    return fs.exists(candidate) || owner?.isLive == true
}
