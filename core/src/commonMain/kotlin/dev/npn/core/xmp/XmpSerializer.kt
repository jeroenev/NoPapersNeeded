package dev.npn.core.xmp

/**
 * Serializes [NpnXmpData] into a full, self-contained XMP packet as RDF/XML.
 *
 * The output is deterministic (stable property order, fixed indentation) so it can be asserted
 * against golden fixtures, and [XmpParser] reads back what bake needs from it. It is intentionally
 * hand-written rather than produced by xmpbox: pdfbox-android ships no usable xmpbox, so PDFBox on
 * each platform only moves the raw metadata bytes.
 *
 * Namespaces emitted:
 * - `dc`  -> Dublin Core (`http://purl.org/dc/elements/1.1/`): `dc:subject`, `dc:title`
 * - `npn` -> our own (`https://npn.dev/xmp/1.0/`): simple properties
 *
 * Everything is XML-escaped. The packet is wrapped in the standard `<?xpacket ...?>`
 * processing instructions so external tools (exiftool, Zotero) recognize it.
 */
internal object XmpSerializer {
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
        sb
            .append(" xmlns:npn=\"")
            .append(NPN_NAMESPACE)
            .append("\">")
            .append('\n')

        // dc:title as an rdf:Alt with a single x-default entry.
        val title = data.title
        if (title != null) {
            sb.append("      <${XmpProperties.TITLE}>").append('\n')
            sb.append("        <rdf:Alt>").append('\n')
            sb
                .append("          <rdf:li xml:lang=\"x-default\">")
                .append(escape(title))
                .append("</rdf:li>")
                .append('\n')
            sb.append("        </rdf:Alt>").append('\n')
            sb.append("      </${XmpProperties.TITLE}>").append('\n')
        }

        // dc:subject as an rdf:Bag of rdf:li (Dublin Core keywords).
        if (data.tags.isNotEmpty()) {
            sb.append("      <${XmpProperties.SUBJECT}>").append('\n')
            sb.append("        <rdf:Bag>").append('\n')
            for (tag in data.tags) {
                sb
                    .append("          <rdf:li>")
                    .append(escape(tag))
                    .append("</rdf:li>")
                    .append('\n')
            }
            sb.append("        </rdf:Bag>").append('\n')
            sb.append("      </${XmpProperties.SUBJECT}>").append('\n')
        }

        // npn: simple properties in a stable order.
        appendSimple(sb, XmpProperties.DOCUMENT_ID, data.documentId)
        for ((key, property) in XmpProperties.DEDICATED) {
            if (property != XmpProperties.TITLE) appendSimple(sb, property, data.fields[key])
        }
        appendSimple(sb, XmpProperties.REVIEWED, data.reviewed)

        // npn:fields as an rdf:Bag of key/value structs (sorted for determinism). The keys that
        // already went out as dedicated properties above are skipped, so no value is written twice.
        // Wrapping keys in a value element avoids emitting user text as XML names.
        val bagged = data.fields.filterKeys { it !in XmpProperties.DEDICATED }
        if (bagged.isNotEmpty()) {
            sb.append("      <${XmpProperties.FIELDS}>").append('\n')
            sb.append("        <rdf:Bag>").append('\n')
            for (key in bagged.keys.sorted()) {
                val value = bagged.getValue(key)
                sb.append("          <rdf:li rdf:parseType=\"Resource\">").append('\n')
                sb
                    .append("            <${XmpProperties.FIELD_KEY}>")
                    .append(escape(key))
                    .append("</${XmpProperties.FIELD_KEY}>")
                    .append('\n')
                sb
                    .append("            <${XmpProperties.FIELD_VALUE}>")
                    .append(escape(value))
                    .append("</${XmpProperties.FIELD_VALUE}>")
                    .append('\n')
                sb.append("          </rdf:li>").append('\n')
            }
            sb.append("        </rdf:Bag>").append('\n')
            sb.append("      </${XmpProperties.FIELDS}>").append('\n')
        }

        sb.append("    </rdf:Description>").append('\n')
        sb.append("  </rdf:RDF>").append('\n')
        sb.append("</x:xmpmeta>").append('\n')
        sb.append(PACKET_END)
        return sb.toString()
    }

    private fun appendSimple(
        sb: StringBuilder,
        qName: String,
        value: String?,
    ) {
        if (value == null) return
        sb
            .append("      <")
            .append(qName)
            .append('>')
            .append(escape(value))
            .append("</")
            .append(qName)
            .append('>')
            .append('\n')
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
