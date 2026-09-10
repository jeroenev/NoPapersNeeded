package dev.npn.core.pathrules

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.toFieldDef
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.index.FtsRows
import dev.npn.core.index.FtsWriteGate

/**
 * Applies the active [StoragePathRule] by moving a document to the path derived from its metadata.
 *
 * Moves are only performed when the rule is enabled and the computed path differs from the current
 * one. Trashed, deleted, or missing documents are never moved. Collisions are resolved by suffixing
 * the filename with `-1`, `-2`, etc. No journal events are emitted.
 */
class StoragePathRuleApplicator(
    private val fs: LibraryFileSystem,
    private val db: NpnDatabase,
    private val ftsWriteGate: FtsWriteGate,
    private val ruleProvider: suspend () -> StoragePathRule?,
) {
    private val documentDao get() = db.documentDao()
    private val ftsDao get() = db.ftsDao()

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
                fieldTypes =
                    db
                        .fieldDefDao()
                        .all()
                        .mapNotNull { it.toFieldDef() }
                        .associate { it.key to it.type },
            )
        val expanded = StoragePathTemplate.expand(rule.template, context).getOrNull() ?: return false
        val target = resolveUniqueTarget(fs, documentDao, docId, expanded)

        if (NpnPaths.normalize(target) == NpnPaths.normalize(doc.relPath)) return false

        return runCatching { move(doc, target, tags) }.getOrDefault(false)
    }

    private suspend fun move(
        doc: DocumentEntity,
        target: String,
        tags: List<String>,
    ): Boolean {
        fs.move(doc.relPath, target)

        val updated =
            doc.copy(
                relPath = target,
                fileName = target.substringAfterLast('/'),
            )
        documentDao.upsert(updated)
        updateFts(updated, tags)
        return true
    }

    private suspend fun updateFts(
        doc: DocumentEntity,
        tags: List<String>,
    ) = ftsWriteGate.withGate {
        // Read-then-replace must hold the shared gate so a concurrent metadata fold or OCR
        // completion for the same doc cannot clobber the FTS content column (see FtsWriteGate).
        val content = ftsDao.contentForDoc(doc.docId)
        val fields = db.documentFieldDao().fieldsForDocs(listOf(doc.docId)).associate { it.fieldKey to it.fieldValue }
        ftsDao.replaceDocument(FtsRows.row(doc.docId, doc.fileName, tags, fields, content))
    }
}
