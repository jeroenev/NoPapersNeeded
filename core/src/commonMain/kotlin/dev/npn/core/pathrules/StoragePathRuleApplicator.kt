package dev.npn.core.pathrules

import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.fieldTypes
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths

/**
 * Applies the active [StoragePathRule] by moving a document to the path derived from its metadata.
 *
 * Moves are only performed when the rule is enabled and the computed path differs from the current
 * one. Trashed, deleted, or missing documents are never moved. Collisions are resolved by suffixing
 * the filename with `-1`, `-2`, etc. The intended path is journaled before physical reconciliation.
 */
class StoragePathRuleApplicator(
    private val fs: LibraryFileSystem,
    private val db: NpnDatabase,
    private val ruleProvider: suspend () -> StoragePathRule?,
    private val setPath: suspend (String, String) -> Unit,
) {
    private val documentDao get() = db.documentDao()

    /**
     * Compute and apply the active rule for [docId]. Returns `true` when the document was moved,
     * `false` when no move was needed or the rule is disabled/invalid.
     */
    @Suppress("ReturnCount")
    suspend fun applyFor(docId: String): Boolean {
        val rule = ruleProvider() ?: return false
        if (!rule.enabled || rule.template.isBlank()) return false

        val doc = documentDao.getByDocId(docId) ?: return false
        if (doc.trashed || doc.deleted || doc.missing) return false

        val tags = documentDao.tagsForDocs(listOf(docId)).map { it.tag }.distinct()
        val context =
            StoragePathContext(
                fields = db.documentFieldDao().fieldsForDocs(listOf(docId)).associate { it.fieldKey to it.fieldValue },
                tags = tags,
                originalFileName = doc.fileName,
                fieldTypes = db.fieldDefDao().fieldTypes(),
            )
        val expanded = StoragePathTemplate.expand(rule.template, context).pathOrNull ?: return false
        val target = resolveUniqueTarget(fs, documentDao, docId, expanded)

        if (NpnPaths.normalize(target) == NpnPaths.normalize(doc.relPath)) return false

        setPath(docId, target)
        return true
    }
}
