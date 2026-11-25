package dev.npn.core.mail

/**
 * The readable text of an HTML email body, for the email PDF of a message that has no plain-text
 * part. Not a renderer: blocks become lines, table cells are spaced apart, list items get a
 * bullet, links keep only their text, and what a reader never sees (head, styles, scripts,
 * comments) is dropped. Entities are decoded; whitespace is collapsed as a browser would.
 *
 * The markup is the sender's, so it is read in one pass, in time proportional to its length: no
 * backtracking pattern, and no search that each of a run of unclosed tags would repeat. A comment,
 * tag or hidden block that is never closed ends the text there; a `<` that starts no tag is text.
 */
internal object HtmlText {
    /** Elements whose content a reader never sees. */
    private val INVISIBLE = setOf("head", "style", "script", "title", "template")

    /** Tags that set a paragraph apart: a blank line around them. */
    private val PARAGRAPH =
        setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "table", "ul", "ol", "blockquote", "pre", "hr")

    /** Tags that only start a new line. */
    private val LINE =
        setOf(
            "div",
            "tr",
            "thead",
            "tbody",
            "tfoot",
            "section",
            "article",
            "header",
            "footer",
            "center",
            "address",
            "dl",
            "dt",
            "dd",
        )
    private val ENTITY_NAME = Regex("""#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31}""")

    /** The longest entity [ENTITY_NAME] accepts, with its `;`: how far a `&` looks ahead. */
    private const val MAX_ENTITY_CHARS = 33
    private val SPACES = Regex("""[ \t\u000B\f ]+""")
    private val BLANK_LINES = Regex("""\n{3,}""")

    /** Placeholders while tags are stripped: a line break, a paragraph break, and a `<br>`. */
    private const val SOFT = '\u0001'
    private const val HARD = '\u0002'
    private const val BREAK = '\u0003'
    private const val NBSP = ' '

    fun toPlainText(html: String): String =
        breaks(Marker(html).marked())
            .lineSequence()
            .map { line -> line.replace(SPACES, " ").trim() }
            .joinToString("\n")
            .replace(BLANK_LINES, "\n\n")
            .trim()

    /**
     * The text with tags replaced by the placeholders, hidden content dropped, entities decoded,
     * and every source line break or other control character a space (only tags break lines).
     */
    private class Marker(
        private val html: String,
    ) {
        private val out = StringBuilder(html.length)
        private var index = 0

        fun marked(): String {
            while (index < html.length) {
                val char = html[index]
                when {
                    html.startsWith("<!--", index) -> comment()
                    char == '<' && startsTag(index + 1) -> tag()
                    char == '&' -> entity()
                    else -> text(char)
                }
            }
            return out.toString()
        }

        private fun text(char: Char) {
            out.append(if (char < ' ') ' ' else char)
            index++
        }

        private fun startsTag(at: Int): Boolean {
            val next = html.getOrNull(at) ?: return false
            return next.isLetter() || next == '/' || next == '!' || next == '?'
        }

        private fun comment() {
            val end = html.indexOf("-->", index + "<!--".length)
            out.append(' ')
            index = if (end < 0) html.length else end + "-->".length
        }

        private fun tag() {
            val close = html.indexOf('>', index + 1)
            if (close < 0) {
                index = html.length
                return
            }
            val closing = html[index + 1] == '/'
            val nameStart = if (closing) index + 2 else index + 1
            var nameEnd = nameStart
            while (nameEnd < close && html[nameEnd].isLetterOrDigit()) nameEnd++
            val name = html.substring(nameStart, nameEnd).lowercase()
            index = close + 1
            when {
                !closing && name in INVISIBLE -> skipPast("</$name")
                closing && (name == "td" || name == "th") -> out.append(' ')
                !closing && name == "br" -> out.append(BREAK)
                !closing && name == "li" -> out.append(SOFT).append("• ")
                name in PARAGRAPH -> out.append(HARD)
                name in LINE -> out.append(SOFT)
            }
        }

        /** Drop everything through the tag that starts with [end]; never closed, the rest of the text. */
        private fun skipPast(end: String) {
            out.append(' ')
            val at = html.indexOf(end, index, ignoreCase = true)
            val close = if (at < 0) -1 else html.indexOf('>', at)
            index = if (close < 0) html.length else close + 1
        }

        private fun entity() {
            var end = index + 1
            while (end < html.length && end - index <= MAX_ENTITY_CHARS && html[end] != ';') end++
            val decoded = if (end < html.length && html[end] == ';') decode(html.substring(index + 1, end)) else null
            if (decoded == null) {
                out.append('&')
                index++
            } else {
                out.append(decoded)
                index = end + 1
            }
        }
    }

    /**
     * Each run of block edges and the blanks around them made one break (a closing div and an
     * opening one make one, not one each), and every `<br>` a line break.
     */
    private fun breaks(marked: String): String {
        val out = StringBuilder(marked.length)
        var index = 0
        while (index < marked.length) {
            val char = marked[index]
            if (!isBlankOrEdge(char)) {
                out.append(if (char == BREAK) '\n' else char)
                index++
                continue
            }
            var end = index
            var hard = false
            var soft = false
            while (end < marked.length && isBlankOrEdge(marked[end])) {
                hard = hard || marked[end] == HARD
                soft = soft || marked[end] == SOFT
                end++
            }
            when {
                hard -> out.append("\n\n")
                soft -> out.append('\n')
                else -> out.append(marked, index, end)
            }
            index = end
        }
        return out.toString()
    }

    private fun isBlankOrEdge(char: Char): Boolean = char == ' ' || char == NBSP || char == SOFT || char == HARD

    /** The text of the entity [name] (what stands between `&` and `;`), or null for one it does not know. */
    private fun decode(name: String): String? {
        if (!ENTITY_NAME.matches(name)) return null
        val codePoint =
            when {
                name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)
                name.startsWith("#") -> name.drop(1).toIntOrNull()
                else -> NAMED[name] ?: NAMED[name.lowercase()]
            }
        return when {
            codePoint == null || codePoint !in 1..Character.MAX_CODE_POINT || codePoint in 0xD800..0xDFFF -> null

            // A control character is a space, as in the source; it must never pass for a placeholder.
            codePoint < ' '.code -> " "

            else -> String(Character.toChars(codePoint))
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
