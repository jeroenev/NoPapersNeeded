package dev.npn.core.index

import dev.npn.core.db.FtsDocumentEntity
import dev.npn.core.db.NpnDatabase

/**
 * The one place an FTS row is assembled. The fold, the OCR pipeline and the storage-path
 * applicator all rewrite a document's FTS row; each used to list the columns itself, so a new
 * searchable column had to be added three times or search silently lost it on one path. Now they
 * all call [row], and only the extracted-text [FtsDocumentEntity.content] differs per caller.
 */
object FtsRows {
    fun row(
        docId: String,
        fileName: String?,
        tags: Collection<String>,
        fields: Map<String, String>,
        content: String?,
    ): FtsDocumentEntity =
        FtsDocumentEntity(
            docId = docId,
            fileName = fileName,
            content = content,
            tags =
                tags
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .sorted()
                    .joinToString(
                        " ",
                    ).ifEmpty { null },
            fields =
                fields.values
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(
                        "\n",
                    ).ifEmpty { null },
        )

    /**
     * Build [docId]'s row from the current projection (doc row, tags, fields) with the given
     * [content]. Null when the document is not in the index. Callers hold the FTS write gate.
     */
    suspend fun fromProjection(
        db: NpnDatabase,
        docId: String,
        content: String?,
    ): FtsDocumentEntity? {
        val doc = db.documentDao().getByDocId(docId) ?: return null
        val tags = db.tagDao().tagsForDocument(docId)
        val fields = db.documentFieldDao().fieldsForDocs(listOf(docId)).associate { it.fieldKey to it.fieldValue }
        return row(docId, doc.fileName, tags, fields, content)
    }
}
