package dev.npn.core.mail

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeTraversor

/**
 * The readable text of an HTML email body, for the email PDF of a message that has no plain-text
 * part. Not a renderer: blocks become lines, table cells are spaced apart, list items get a
 * bullet, links keep only their text, and what a reader never sees (head, styles, scripts,
 * comments) is dropped. Entities are decoded; whitespace is collapsed as a browser would.
 *
 * The markup is the sender's, and is parsed as a browser would (jsoup's HTML5 tree builder): a
 * missing `</head>` or `</body>` is no reason to lose the text, and a `<` that starts no tag is
 * text. Parsing and walking the tree take time in proportion to the markup's length; a comment,
 * style or script that is never closed runs to the end, as in a browser.
 */
internal object HtmlText {
    /** Elements whose content a reader never sees. */
    private val INVISIBLE = setOf("head", "style", "script", "title", "template", "noscript")

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
    private val SPACES = Regex("""[ \t\u000B\f\u00A0]+""")
    private val BLANK_LINES = Regex("""\n{3,}""")

    /** Placeholders while the tree is walked: a line break, a paragraph break, and a `<br>`. */
    private const val SOFT = '\u0001'
    private const val HARD = '\u0002'
    private const val BREAK = '\u0003'
    private const val NBSP = '\u00A0'

    fun toPlainText(html: String): String =
        breaks(marked(html))
            .lineSequence()
            .map { line -> line.replace(SPACES, " ").trim() }
            .joinToString("\n")
            .replace(BLANK_LINES, "\n\n")
            .trim()

    /**
     * The body's text with the placeholders where blocks start and end, what is never shown left
     * out, and every source line break or other control character a space (only tags break lines).
     */
    private fun marked(html: String): String {
        val body = Jsoup.parse(html).body()
        val out = StringBuilder(html.length)
        NodeTraversor.filter(
            object : NodeFilter {
                override fun head(
                    node: Node,
                    depth: Int,
                ): NodeFilter.FilterResult {
                    when (node) {
                        is TextNode -> {
                            node.wholeText.forEach { out.append(if (it < ' ') ' ' else it) }
                        }

                        is Element -> {
                            val name = node.normalName()
                            if (name in INVISIBLE) return NodeFilter.FilterResult.SKIP_ENTIRELY
                            when (name) {
                                "br" -> out.append(BREAK)
                                "li" -> out.append(SOFT).append("• ")
                                in PARAGRAPH -> out.append(HARD)
                                in LINE -> out.append(SOFT)
                            }
                        }
                    }
                    return NodeFilter.FilterResult.CONTINUE
                }

                override fun tail(
                    node: Node,
                    depth: Int,
                ): NodeFilter.FilterResult {
                    if (node is Element) {
                        when (node.normalName()) {
                            "td", "th" -> out.append(' ')
                            in PARAGRAPH -> out.append(HARD)
                            in LINE -> out.append(SOFT)
                        }
                    }
                    return NodeFilter.FilterResult.CONTINUE
                }
            },
            body,
        )
        return out.toString()
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
}
