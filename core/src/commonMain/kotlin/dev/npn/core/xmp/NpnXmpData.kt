package dev.npn.core.xmp

import dev.npn.core.model.FieldKeys

/**
 * The npn-owned metadata that gets baked into a PDF's XMP packet (PLAN section 2, "XMP
 * baking").
 *
 * Mapping to XMP properties (see [XmpSerializer]):
 * - [documentId]     -> `npn:documentId`   (sha256 of the file at bake time; enables relink)
 * - [tags]           -> `dc:subject`       (Dublin Core keywords, an rdf:Bag)
 * - [reviewed]       -> `npn:reviewed`     ("true"/"false")
 * - [fields]         -> the four interop keys get their own properties so other tools can read
 *                       them — `title` -> `dc:title` (an rdf:Alt with one x-default entry),
 *                       `correspondent` -> `npn:correspondent`, `docType` -> `npn:documentType`,
 *                       `docDate` -> `npn:documentDate` (ISO `yyyy-MM-dd`) — and every other key
 *                       goes into the `npn:fields` bag of key/value pairs. A key is written in
 *                       exactly one place, never both.
 *
 * All fields are optional; a `null` (or empty [tags]/[fields]) simply omits the corresponding
 * property from the packet.
 */
data class NpnXmpData(
    val documentId: String? = null,
    val tags: List<String> = emptyList(),
    val reviewed: String? = null,
    /** Every field value by registry key, well-known and user-defined alike. */
    val fields: Map<String, String> = emptyMap(),
) {
    val title: String? get() = fields[FieldKeys.TITLE]
    val correspondent: String? get() = fields[FieldKeys.CORRESPONDENT]
    val docType: String? get() = fields[FieldKeys.DOC_TYPE]
    val docDate: String? get() = fields[FieldKeys.DOC_DATE]

    /** This data with one field set (or, with a null [value], removed). */
    fun withField(
        key: String,
        value: String?,
    ): NpnXmpData = copy(fields = if (value == null) fields - key else fields + (key to value))

    companion object {
        val EMPTY = NpnXmpData()

        /** The keys [XmpSerializer] writes as dedicated properties instead of `npn:fields` entries. */
        val DEDICATED_KEYS: Set<String> =
            setOf(FieldKeys.TITLE, FieldKeys.CORRESPONDENT, FieldKeys.DOC_TYPE, FieldKeys.DOC_DATE)
    }
}
