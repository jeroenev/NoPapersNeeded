package dev.npn.core.xmp

/**
 * Serializes [NpnXmpData] into a full, self-contained XMP packet as RDF/XML.
 *
 * The output is deterministic (stable property order, fixed indentation) so it can be asserted
 * against golden fixtures and round-tripped by [XmpParser]. It is intentionally hand-written
 * rather than produced by xmpbox: pdfbox-android ships no usable xmpbox, and per PLAN section 2
 * PDFBox on each platform is only used to move the raw metadata bytes.
 *
 * Namespaces emitted:
 * - `dc`  -> Dublin Core (`http://purl.org/dc/elements/1.1/`): `dc:subject`, `dc:title`
 * - `npn` -> our own (`https://npn.dev/xmp/1.0/`): simple properties
 *
 * Everything is XML-escaped. The packet is wrapped in the standard `<?xpacket ...?>`
 * processing instructions so external tools (exiftool, Zotero) recognize it.
 */
object XmpSerializer {

    const val NPN_NAMESPACE = "https://npn.dev/xmp/1.0/"
    const val DC_NAMESPACE = "http://purl.org/dc/elements/1.1/"

    private const val PACKET_BEGIN =
        "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
    private const val PACKET_END = "<?xpacket end=\"w\"?>"

    fun serialize(data: NpnXmpData): String {
        val sb = StringBuilder()
        sb.append(PACKET_BEGIN).append('\n')
        sb.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">").append('\n')
        sb.append("  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">").append('\n')
        sb.append("    <rdf:Description rdf:about=\"\"")
        sb.append(" xmlns:dc=\"").append(DC_NAMESPACE).append('"')
        sb.append(" xmlns:npn=\"").append(NPN_NAMESPACE).append("\">").append('\n')

        // dc:title as an rdf:Alt with a single x-default entry.
        if (data.title != null) {
            sb.append("      <dc:title>").append('\n')
            sb.append("        <rdf:Alt>").append('\n')
            sb.append("          <rdf:li xml:lang=\"x-default\">")
                .append(escape(data.title))
                .append("</rdf:li>").append('\n')
            sb.append("        </rdf:Alt>").append('\n')
            sb.append("      </dc:title>").append('\n')
        }

        // dc:subject as an rdf:Bag of rdf:li (Dublin Core keywords).
        if (data.tags.isNotEmpty()) {
            sb.append("      <dc:subject>").append('\n')
            sb.append("        <rdf:Bag>").append('\n')
            for (tag in data.tags) {
                sb.append("          <rdf:li>").append(escape(tag)).append("</rdf:li>").append('\n')
            }
            sb.append("        </rdf:Bag>").append('\n')
            sb.append("      </dc:subject>").append('\n')
        }

        // npn: simple properties in a stable order.
        appendSimple(sb, "npn:documentId", data.documentId)
        appendSimple(sb, "npn:correspondent", data.correspondent)
        appendSimple(sb, "npn:documentType", data.docType)
        appendSimple(sb, "npn:documentDate", data.docDate)
        appendSimple(sb, "npn:reviewed", data.reviewed)

        // npn:fields as an rdf:Bag of key/value structs (arbitrary user keys, sorted for
        // determinism). Wrapping keys in a value element avoids emitting user text as XML names.
        if (data.fields.isNotEmpty()) {
            sb.append("      <npn:fields>").append('\n')
            sb.append("        <rdf:Bag>").append('\n')
            for (key in data.fields.keys.sorted()) {
                val value = data.fields.getValue(key)
                sb.append("          <rdf:li rdf:parseType=\"Resource\">").append('\n')
                sb.append("            <npn:fieldKey>").append(escape(key)).append("</npn:fieldKey>").append('\n')
                sb.append("            <npn:fieldValue>").append(escape(value)).append("</npn:fieldValue>").append('\n')
                sb.append("          </rdf:li>").append('\n')
            }
            sb.append("        </rdf:Bag>").append('\n')
            sb.append("      </npn:fields>").append('\n')
        }

        sb.append("    </rdf:Description>").append('\n')
        sb.append("  </rdf:RDF>").append('\n')
        sb.append("</x:xmpmeta>").append('\n')
        sb.append(PACKET_END)
        return sb.toString()
    }

    private fun appendSimple(sb: StringBuilder, qName: String, value: String?) {
        if (value == null) return
        sb.append("      <").append(qName).append('>')
            .append(escape(value))
            .append("</").append(qName).append('>').append('\n')
    }

    /** XML text/attribute escaping for the five predefined entities. */
    internal fun escape(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
