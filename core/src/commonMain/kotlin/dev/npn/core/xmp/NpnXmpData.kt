package dev.npn.core.xmp

/**
 * The npn-owned metadata that gets baked into a PDF's XMP packet (PLAN section 2, "XMP
 * baking").
 *
 * Mapping to XMP properties (see [XmpSerializer]):
 * - [documentId]     -> `npn:documentId`   (sha256 of the file at bake time; enables relink)
 * - [tags]           -> `dc:subject`       (Dublin Core keywords, an rdf:Bag)
 * - [title]          -> `dc:title`         (an rdf:Alt with a single x-default entry)
 * - [correspondent]  -> `npn:correspondent`
 * - [docType]        -> `npn:documentType`
 * - [docDate]        -> `npn:documentDate`  (ISO local date, `yyyy-MM-dd`)
 * - [reviewed]       -> `npn:reviewed`       ("true"/"false")
 *
 * All fields are optional; a `null` (or empty [tags]) simply omits the corresponding property
 * from the packet.
 */
data class NpnXmpData(
    val documentId: String? = null,
    val tags: List<String> = emptyList(),
    val title: String? = null,
    val correspondent: String? = null,
    val docType: String? = null,
    val docDate: String? = null,
    val reviewed: String? = null,
) {
    companion object {
        val EMPTY = NpnXmpData()
    }
}
