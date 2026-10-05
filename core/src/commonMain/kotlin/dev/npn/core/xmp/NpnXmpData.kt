package dev.npn.core.xmp

import dev.npn.core.model.FieldKeys

/**
 * The npn-owned metadata that gets baked into a PDF's XMP packet.
 *
 * Mapping to XMP properties ([XmpProperties], written by [XmpSerializer]):
 * - [documentId] -> `npn:documentId` (sha256 of the file at bake time; enables relink)
 * - [tags]       -> `dc:subject` (Dublin Core keywords, an rdf:Bag)
 * - [reviewed]   -> `npn:reviewed` ("true"/"false")
 * - [fields]     -> the keys in [XmpProperties.DEDICATED] get their own properties so other tools
 *                   can read them; every other key goes into the `npn:fields` bag of key/value
 *                   pairs. A key is written in exactly one place, never both.
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

    /** This data with one field set (or, with a null [value], removed). */
    fun withField(
        key: String,
        value: String?,
    ): NpnXmpData = copy(fields = if (value == null) fields - key else fields + (key to value))

    companion object {
        val EMPTY = NpnXmpData()
    }
}

/** The XMP properties npn writes, named once for [XmpSerializer] and [XmpParser]. */
internal object XmpProperties {
    const val DOCUMENT_ID = "npn:documentId"
    const val SUBJECT = "dc:subject"
    const val REVIEWED = "npn:reviewed"
    const val FIELDS = "npn:fields"
    const val FIELD_KEY = "npn:fieldKey"
    const val FIELD_VALUE = "npn:fieldValue"

    /** The title's property: an rdf:Alt with one x-default entry. */
    const val TITLE = "dc:title"

    /**
     * The fields written as their own property instead of into [FIELDS], by key, in the order they
     * are written: the title as [TITLE], the others as simple `npn:` properties (the date as ISO
     * `yyyy-MM-dd`).
     */
    val DEDICATED: Map<String, String> =
        linkedMapOf(
            FieldKeys.TITLE to TITLE,
            FieldKeys.CORRESPONDENT to "npn:correspondent",
            FieldKeys.DOC_TYPE to "npn:documentType",
            FieldKeys.DOC_DATE to "npn:documentDate",
        )
}
