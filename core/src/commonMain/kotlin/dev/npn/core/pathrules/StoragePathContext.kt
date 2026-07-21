package dev.npn.core.pathrules

/**
 * Metadata inputs available to a [StoragePathTemplate].
 *
 * [originalFileName] is used as a fallback for the `title` placeholder when no title is set,
 * for example during import before metadata has been edited.
 */
data class StoragePathContext(
    val title: String?,
    val docDate: String?,
    val docType: String?,
    val correspondent: String?,
    val tags: List<String>,
    val originalFileName: String?,
)
