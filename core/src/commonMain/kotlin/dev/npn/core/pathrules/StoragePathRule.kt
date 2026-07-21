package dev.npn.core.pathrules

/**
 * A user-defined rule that derives a document's library-relative path from its metadata.
 *
 * @param template Filename/folder template using `{variable}` or `{variable:format}` placeholders.
 * @param enabled Whether newly imported or edited documents should be moved/renamed automatically.
 */
data class StoragePathRule(
    val template: String,
    val enabled: Boolean,
)
