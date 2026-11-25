package dev.npn.core.mail

/**
 * The readable text of an HTML email body, for the email PDF of a message that has no plain-text
 * part. Not a renderer: blocks become lines, table cells are spaced apart, list items get a
 * bullet, links keep only their text, and what a reader never sees (head, styles, scripts,
 * comments) is dropped. Entities are decoded; whitespace is collapsed as a browser would.
 */
internal object HtmlText {
    private val INVISIBLE =
        Regex(
            """<!--.*?-->|<(head|style|script|title|template)\b[^>]*>.*?</\1\s*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    private val LINE_BREAK = Regex("""<br\b[^>]*>""", RegexOption.IGNORE_CASE)

    /** Tags that set a paragraph apart: a blank line around them. */
    private val PARAGRAPH =
        Regex("""</?(p|h[1-6]|table|ul|ol|blockquote|pre|hr)\b[^>]*>""", RegexOption.IGNORE_CASE)

    /** Tags that only start a new line. */
    private val LINE =
        Regex(
            """</?(div|tr|thead|tbody|tfoot|section|article|header|footer|center|address|dl|dt|dd)\b[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
    private val LIST_ITEM = Regex("""<li\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val CELL_END = Regex("""</t[dh]\s*>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]*>""")
    private val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});""")

    /** A run of block edges and the blanks between them, which becomes one break. */
    private val EDGES = Regex("""[\s\u00A0$SOFT$HARD]*[$SOFT$HARD][\s\u00A0$SOFT$HARD]*""")
    private val SPACES = Regex("""[ \t\u000B\f\u00A0]+""")
    private val BLANK_LINES = Regex("""\n{3,}""")

    fun toPlainText(html: String): String {
        val marked =
            html
                .replace(INVISIBLE, " ")
                // Source line breaks are spaces in HTML; only tags break lines.
                .replace(Regex("""\s+"""), " ")
                .replace(LINE_BREAK, BREAK.toString())
                .replace(LIST_ITEM, "$SOFT• ")
                .replace(PARAGRAPH, HARD.toString())
                .replace(LINE, SOFT.toString())
                .replace(CELL_END, " ")
                .replace(TAG, "")
        // Adjacent block edges (a closing div and an opening one) make one break, not one each.
        val broken =
            decodeEntities(marked)
                .replace(EDGES) { run -> if (HARD in run.value) "\n\n" else "\n" }
                .replace(BREAK, '\n')
        return broken
            .lineSequence()
            .map { line -> line.replace(SPACES, " ").trim() }
            .joinToString("\n")
            .replace(BLANK_LINES, "\n\n")
            .trim()
    }

    /** Placeholders while tags are stripped: a line break, a paragraph break, and a `<br>`. */
    private const val SOFT = '\u0001'
    private const val HARD = '\u0002'
    private const val BREAK = '\u0003'

    private fun decodeEntities(text: String): String =
        ENTITY.replace(text) { match ->
            val name = match.groupValues[1]
            val codePoint =
                when {
                    name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)
                    name.startsWith("#") -> name.drop(1).toIntOrNull()
                    else -> NAMED[name] ?: NAMED[name.lowercase()]
                }
            if (codePoint == null || codePoint !in 1..Character.MAX_CODE_POINT || codePoint in 0xD800..0xDFFF) {
                match.value
            } else {
                String(Character.toChars(codePoint))
            }
        }

    /** The named entities mail uses: markup, typography, and the Latin-1 letters and signs. */
    private val NAMED: Map<String, Int> =
        buildMap {
            putAll(
                mapOf(
                    "amp" to '&'.code,
                    "lt" to '<'.code,
                    "gt" to '>'.code,
                    "quot" to '"'.code,
                    "apos" to '\''.code,
                    "nbsp" to 0xA0,
                    "ensp" to 0x2002,
                    "emsp" to 0x2003,
                    "thinsp" to 0x2009,
                    "zwnj" to 0x200C,
                    "zwj" to 0x200D,
                    "ndash" to 0x2013,
                    "mdash" to 0x2014,
                    "lsquo" to 0x2018,
                    "rsquo" to 0x2019,
                    "sbquo" to 0x201A,
                    "ldquo" to 0x201C,
                    "rdquo" to 0x201D,
                    "bdquo" to 0x201E,
                    "bull" to 0x2022,
                    "hellip" to 0x2026,
                    "prime" to 0x2032,
                    "lsaquo" to 0x2039,
                    "rsaquo" to 0x203A,
                    "euro" to 0x20AC,
                    "trade" to 0x2122,
                    "larr" to 0x2190,
                    "rarr" to 0x2192,
                    "minus" to 0x2212,
                    "times" to 0xD7,
                    "divide" to 0xF7,
                    "oelig" to 0x153,
                    "OElig" to 0x152,
                    "scaron" to 0x161,
                    "Scaron" to 0x160,
                    "dagger" to 0x2020,
                    "permil" to 0x2030,
                ),
            )
            // &iexcl; through &yuml;: the Latin-1 supplement, in code point order.
            LATIN1_NAMES.split(' ').forEachIndexed { index, name -> put(name, 0xA1 + index) }
        }

    private const val LATIN1_NAMES =
        "iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 " +
            "acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde " +
            "Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute " +
            "Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute acirc atilde " +
            "auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve oacute " +
            "ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml"
}
