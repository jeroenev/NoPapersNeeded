package dev.npn.core.xmp

import dev.npn.core.model.FieldKeys

/**
 * Tolerant, hand-rolled reader of what bake reads back from an XMP packet: the title, which a bake
 * carries over when npn has none, and the document id and tags that tell a fresh bake verified. No
 * XML library is used (none is available in commonMain and we want identical behaviour on every
 * platform).
 *
 * - Round-trip: a packet from [XmpSerializer] gives back its documentId, tags and title.
 * - Foreign packets: `dc:title` and `dc:subject` are read from their standard element forms
 *   (`rdf:Alt`/`rdf:Bag`); attribute-shorthand RDF is not decoded.
 * - Namespace prefixes are assumed to be the conventional `dc`/`npn`/`rdf`; packets that remap
 *   these prefixes are not handled.
 * - Never throws. Malformed or unrecognized input yields an empty/partial result.
 */
object XmpParser {
    /** The [NpnXmpData.documentId], [NpnXmpData.tags] and [NpnXmpData.title] in [xml]; nothing else. */
    fun parse(xml: String?): NpnXmpData {
        if (xml.isNullOrEmpty()) return NpnXmpData.EMPTY
        return try {
            val title = readList(xml, XmpProperties.TITLE).firstOrNull()
            NpnXmpData(
                documentId = elementText(xml, XmpProperties.DOCUMENT_ID),
                tags = readList(xml, XmpProperties.SUBJECT),
                fields = if (title == null) emptyMap() else mapOf(FieldKeys.TITLE to title),
            )
        } catch (_: Throwable) {
            // Never propagate parse failures; a partial/empty result is the contract.
            NpnXmpData.EMPTY
        }
    }

    /**
     * Inner content of the first non-self-closing `<qName ...>...</qName>` element, or null when
     * the element is absent, self-closing, or unterminated.
     */
    private fun elementInner(
        xml: String,
        qName: String,
    ): String? {
        val open = findOpenTag(xml, qName) ?: return null
        if (open.selfClosing) return null
        val close = xml.indexOf("</$qName", open.endIndex)
        if (close < 0) return null
        return xml.substring(open.endIndex, close)
    }

    /**
     * Extract the text content of the first `<qName ...>text</qName>` occurrence. Returns the
     * unescaped text, or null when the element is absent or self-closing/empty.
     */
    private fun elementText(
        xml: String,
        qName: String,
    ): String? {
        val raw = elementInner(xml, qName) ?: return null
        // Reject nested markup (this is meant for simple leaf properties only).
        if (raw.contains('<')) return null
        return unescape(raw)
    }

    /** The `rdf:li` texts of the rdf:Bag/Seq/Alt under [containerQName], in order. */
    private fun readList(
        xml: String,
        containerQName: String,
    ): List<String> {
        val inner = elementInner(xml, containerQName) ?: return emptyList()
        val out = ArrayList<String>()
        var item = nextListItem(inner, 0)
        while (item != null) {
            item.text?.takeUnless { it.contains('<') }?.let { out.add(unescape(it)) }
            item = nextListItem(inner, item.end)
        }
        return out
    }

    /** One `rdf:li`: its raw text, null when it is self-closing, and the index just past it. */
    private class ListItem(
        val text: String?,
        val end: Int,
    )

    /** The first `rdf:li` in [fragment] at or after [from], or null when there is none or it is never closed. */
    private fun nextListItem(
        fragment: String,
        from: Int,
    ): ListItem? {
        val open = findOpenTag(fragment, "rdf:li", from) ?: return null
        if (open.selfClosing) return ListItem(null, open.endIndex)
        val close = fragment.indexOf("</rdf:li", open.endIndex)
        if (close < 0) return null
        return ListItem(fragment.substring(open.endIndex, close), close + "</rdf:li".length)
    }

    private data class OpenTag(
        val startIndex: Int,
        val endIndex: Int,
        val selfClosing: Boolean,
    )

    /**
     * Find the first `<qName` occurrence at/after [from] that is a real element start (followed
     * by whitespace, '>', or '/'), and return its span. [endIndex] points just past the closing
     * '>' of the open tag.
     */
    private fun findOpenTag(
        xml: String,
        qName: String,
        from: Int = 0,
    ): OpenTag? {
        val needle = "<$qName"
        var searchFrom = from
        while (true) {
            val idx = xml.indexOf(needle, searchFrom)
            if (idx < 0) return null
            val after = idx + needle.length
            val boundary = if (after < xml.length) xml[after] else '>'
            if (boundary == '>' || boundary == '/' || boundary.isWhitespace()) {
                val gt = xml.indexOf('>', after)
                if (gt < 0) return null
                val selfClosing = gt > 0 && xml[gt - 1] == '/'
                return OpenTag(idx, gt + 1, selfClosing)
            }
            // False match like `<dc:subjectFoo`; keep looking.
            searchFrom = after
        }
    }

    /** Reverse of [XmpSerializer.escape], including numeric character references. */
    internal fun unescape(value: String): String {
        if (!value.contains('&')) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val reference = if (value[i] == '&') referenceAt(value, i) else null
            if (reference != null) {
                sb.append(reference.first)
                i = reference.second
            } else {
                sb.append(value[i])
                i++
            }
        }
        return sb.toString()
    }

    /** The character reference that starts at [amp], decoded, and the index past its `;`; null when there is none. */
    private fun referenceAt(
        value: String,
        amp: Int,
    ): Pair<String, Int>? {
        val semi = value.indexOf(';', amp + 1)
        if (semi < 0) return null
        val decoded =
            when (val entity = value.substring(amp + 1, semi)) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> decodeNumeric(entity)
            } ?: return null
        return decoded to semi + 1
    }

    private fun decodeNumeric(entity: String): String? {
        if (entity.isEmpty() || entity[0] != '#') return null
        val code =
            if (entity.length > 1 && (entity[1] == 'x' || entity[1] == 'X')) {
                entity.substring(2).toIntOrNull(16)
            } else {
                entity.substring(1).toIntOrNull(10)
            } ?: return null
        if (code < 0 || code > 0x10FFFF) return null
        return buildString { appendCodePointCompat(code) }
    }

    private fun StringBuilder.appendCodePointCompat(codePoint: Int) {
        if (codePoint <= 0xFFFF) {
            append(codePoint.toChar())
        } else {
            val cp = codePoint - 0x10000
            append((0xD800 + (cp shr 10)).toChar())
            append((0xDC00 + (cp and 0x3FF)).toChar())
        }
    }
}
