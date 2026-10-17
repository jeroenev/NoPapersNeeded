package dev.npn.core.pathrules

import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType

/**
 * Metadata inputs available to a [StoragePathTemplate].
 *
 * [fields] is the document's effective field map by registry key. [fieldTypes] is the library's
 * registry by key; it decides which fields accept a `{field:key:pattern}` date format. It has no
 * default on purpose: the seed types alone refuse a date pattern on every user-defined date field.
 * [originalFileName] is used as a fallback for the `title` placeholder when no title is set,
 * for example during import before metadata has been edited.
 */
data class StoragePathContext(
    val fields: Map<String, String>,
    val tags: List<String>,
    val originalFileName: String?,
    val fieldTypes: Map<String, FieldType>,
) {
    val title: String? get() = fields[FieldKeys.TITLE]

    companion object {
        /**
         * A fictional document carrying every seeded field, for validating a template against the
         * library's [fieldTypes] or previewing it. The texts are placeholders unless a preview
         * passes its own (translated) ones.
         */
        fun sample(
            fieldTypes: Map<String, FieldType>,
            title: String = "sample-title",
            docType: String = "sample-type",
            correspondent: String = "sample-correspondent",
            tags: List<String> = listOf("sample-tag"),
        ): StoragePathContext =
            StoragePathContext(
                fields =
                    mapOf(
                        FieldKeys.TITLE to title,
                        FieldKeys.DOC_DATE to SAMPLE_DATE,
                        FieldKeys.DOC_TYPE to docType,
                        FieldKeys.CORRESPONDENT to correspondent,
                    ),
                tags = tags,
                originalFileName = "scan.pdf",
                fieldTypes = fieldTypes,
            )

        private const val SAMPLE_DATE = "2026-07-20"
    }
}
