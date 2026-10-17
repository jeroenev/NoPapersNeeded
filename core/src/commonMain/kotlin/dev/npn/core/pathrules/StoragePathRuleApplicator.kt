package dev.npn.core.pathrules

import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.fieldTypes
import dev.npn.core.db.isLive
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Applies the active [StoragePathRule] by moving a document to the path derived from its metadata.
 *
 * Moves are only performed when the rule is enabled and the computed path differs from the current
 * one. Trashed, deleted, or missing documents are never moved. Collisions are resolved by suffixing
 * the filename with `-1`, `-2`, etc. The intended path is journaled before physical reconciliation,
 * once: [setPath] writes nothing when the journal already holds that location for the document, as
 * it does for one its storage cannot move (a library opened through Android's folder picker), where
 * the intent waits for a replica that can carry it out.
 *
 * Applications run one at a time, each from its read to its path commit: each reads after the
 * write that asked for it committed, so the last to run sees the latest metadata, and no two pick
 * the same free target. Lock order: [dev.npn.core.rules.RuleApplier]'s lock, this one, then the
 * coordinator's ([setPath] takes it); [applyFor] never runs under the coordinator's lock.
 */
class StoragePathRuleApplicator(
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
        val target = resolveUniqueTarget(fs, documentDao, docId, expanded)
        if (NpnPaths.normalize(target) != NpnPaths.normalize(doc.relPath)) setPath(docId, target)
    }
}
