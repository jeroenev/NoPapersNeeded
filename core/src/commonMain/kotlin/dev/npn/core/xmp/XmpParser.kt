package dev.npn.core.xmp

/**
 * Tolerant, hand-rolled XMP reader. No XML library is used (none is available in commonMain and
 * we want identical behaviour on every platform).
 *
 * Guarantees:
 * - Round-trip: any packet produced by [XmpSerializer] parses back to an equal [NpnXmpData].
 * - Best-effort on foreign packets: `dc:subject`, `dc:title` and `npn:*` are extracted when
 *   present in the element form this parser understands.
 * - Never throws. Malformed or unrecognized input yields an empty/partial [NpnXmpData].
 *
 * Limitations (documented, acceptable for v1 interop reads):
 * - `dc:subject`/`dc:title` are read from their standard element forms (`rdf:Bag`/`rdf:Alt`).
 *   Attribute-shorthand RDF for these container properties is not decoded.
 * - `npn:*` simple properties are read from either element form (`<npn:x>v</npn:x>`) or
 *   attribute shorthand (`npn:x="v"` on any tag), whichever appears first.
 * - Namespace prefixes are assumed to be the conventional `dc`/`npn`/`rdf`; packets that remap
 *   these prefixes are not handled.
 */
object XmpParser {
    fun parse(xml: String?): NpnXmpData {
        if (xml.isNullOrEmpty()) return NpnXmpData.EMPTY
        return try {
            NpnXmpData(
                documentId = readNpn(xml, "documentId"),
                tags = readBag(xml, "dc:subject"),
                title = readAlt(xml, "dc:title"),
                correspondent = readNpn(xml, "correspondent"),
                docType = readNpn(xml, "documentType"),
                docDate = readNpn(xml, "documentDate"),
                reviewed = readNpn(xml, "reviewed"),
                fields = readFields(xml),
            )
        } catch (_: Throwable) {
            // Never propagate parse failures; a partial/empty result is the contract.
            NpnXmpData.EMPTY
        }
    }

    /** Read an `npn:<local>` value from element form first, then attribute shorthand. */
    private fun readNpn(
        xml: String,
        local: String,
    ): String? {
        val q = "npn:$local"
        elementText(xml, q)?.let { return it }
        return attributeValue(xml, q)
    }

    /**
     * Inner content of the first non-self-closing `<qName ...>...</qName>` element, or null when
     * the element is absent, self-closing, or unterminated. Shared preamble
     * for every element reader (leaf text, rdf:Bag/Alt containers, npn:fields) so the open-tag +
     * self-closing + matching-close-tag logic lives in exactly one place.
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

    /** Extract an attribute value `qName="..."` (or single-quoted) from anywhere in the doc. */
    private fun attributeValue(
        xml: String,
        qName: String,
    ): String? {
        var searchFrom = 0
        while (true) {
            val idx = xml.indexOf(qName, searchFrom)
            if (idx < 0) return null
            var i = idx + qName.length
            // Skip whitespace, expect '='.
            while (i < xml.length && xml[i].isWhitespace()) i++
            if (i >= xml.length || xml[i] != '=') {
                searchFrom = idx + qName.length
                continue
            }
            i++
            while (i < xml.length && xml[i].isWhitespace()) i++
            if (i >= xml.length) return null
            val quote = xml[i]
            if (quote != '"' && quote != '\'') {
                searchFrom = i
                continue
            }
            val end = xml.indexOf(quote, i + 1)
            if (end < 0) return null
            return unescape(xml.substring(i + 1, end))
        }
    }

    /** Read an rdf:Bag/rdf:Seq of rdf:li under [containerQName] into an ordered list. */
    private fun readBag(
        xml: String,
        containerQName: String,
    ): List<String> {
        val inner = elementInner(xml, containerQName) ?: return emptyList()
        return readListItems(inner)
    }

    /**
     * Read the `npn:fields` container: an rdf:Bag whose rdf:li each hold an `npn:fieldKey` and
     * `npn:fieldValue`. Later duplicate keys win. Missing/empty container yields an empty map.
     */
    private fun readFields(xml: String): Map<String, String> {
        val inner = elementInner(xml, "npn:fields") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        var cursor = 0
        while (true) {
            val li = findOpenTag(inner, "rdf:li", cursor) ?: break
            if (li.selfClosing) {
                cursor = li.endIndex
                continue
            }
            val liClose = inner.indexOf("</rdf:li", li.endIndex)
            if (liClose < 0) break
            val liInner = inner.substring(li.endIndex, liClose)
            val key = elementText(liInner, "npn:fieldKey")
            val value = elementText(liInner, "npn:fieldValue")
            if (key != null && value != null) out[key] = value
            cursor = liClose + "</rdf:li".length
        }
        return out
    }

    /** Read an rdf:Alt under [containerQName]; return the first (x-default) li text. */
    private fun readAlt(
        xml: String,
        containerQName: String,
    ): String? {
        val inner = elementInner(xml, containerQName) ?: return null
        return readListItems(inner).firstOrNull()
    }

    /** Collect the text of every `<rdf:li ...>text</rdf:li>` in [fragment], in order. */
    private fun readListItems(fragment: String): List<String> {
        val out = ArrayList<String>()
        var cursor = 0
        while (true) {
            val open = findOpenTag(fragment, "rdf:li", cursor) ?: break
            if (open.selfClosing) {
                cursor = open.endIndex
                continue
            }
            val close = fragment.indexOf("</rdf:li", open.endIndex)
            if (close < 0) break
            val raw = fragment.substring(open.endIndex, close)
            if (!raw.contains('<')) {
                out.add(unescape(raw))
            }
            cursor = close + "</rdf:li".length
        }
        return out
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
            val ch = value[i]
            if (ch != '&') {
                sb.append(ch)
                i++
                continue
            }
            val semi = value.indexOf(';', i + 1)
            if (semi < 0) {
                sb.append(ch)
                i++
                continue
            }
            val entity = value.substring(i + 1, semi)
            val decoded =
                when (entity) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    else -> decodeNumeric(entity)
                }
            if (decoded != null) {
                sb.append(decoded)
                i = semi + 1
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
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
