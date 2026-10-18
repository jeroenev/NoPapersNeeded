package dev.npn.core.index

import dev.npn.core.db.FtsDocumentEntity
import dev.npn.core.db.NpnDatabase

/**
 * The one place an FTS row is assembled. The fold writes a row's metadata columns and the OCR
 * pipeline its extracted text, and either may create the row; both build it here, so a new
 * searchable column cannot be indexed on one path and silently missed on the other.
 */
object FtsRows {
    /**
     * [docId]'s row with the tags and field values [db] holds for it now, for a path that writes
     * one column and may create the row: the fold's own path builds from what it projects instead.
     */
    suspend fun indexed(
        db: NpnDatabase,
        docId: String,
        fileName: String?,
        content: String?,
    ): FtsDocumentEntity =
        row(
            docId = docId,
            fileName = fileName,
            tags = db.tagDao().tagsForDocument(docId),
            fields = db.documentFieldDao().valuesOf(docId),
            content = content,
        )

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
}
