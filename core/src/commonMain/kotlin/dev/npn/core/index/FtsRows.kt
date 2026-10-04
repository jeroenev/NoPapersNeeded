package dev.npn.core.index

import dev.npn.core.db.FtsDocumentEntity

/**
 * The one place an FTS row is assembled. The fold writes a row's metadata columns and the OCR
 * pipeline its extracted text, and either may create the row; both build it here, so a new
 * searchable column cannot be indexed on one path and silently missed on the other.
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
}
