package dev.npn.core.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The text an HTML-only email's PDF shows: what a reader sees, laid out in lines. */
class HtmlTextTest {
    @Test
    fun blocksBecomeLinesAndWhatIsNeverShownIsDropped() {
        val html =
            """
            <html><head><title>Receipt</title><style>p { color: red }</style></head>
            <body><!-- tracking --><script>alert(1)</script>
            <h1>Your   receipt</h1>
            <p>Thank you for your order.<br>It ships
            tomorrow.</p>
            <div>Total: <b>12,50&nbsp;&euro;</b></div>
            </body></html>
            """.trimIndent()

        assertEquals(
            "Your receipt\n\nThank you for your order.\nIt ships tomorrow.\n\nTotal: 12,50 €",
            HtmlText.toPlainText(html),
        )
    }

    @Test
    fun tableCellsAreSpacedAndListItemsBulleted() {
        val html =
            "<table><tr><td>Item</td><td>Price</td></tr><tr><td>Paper</td><td>3</td></tr></table>" +
                "<ul><li>One</li><li>Two</li></ul>"

        assertEquals("Item Price\nPaper 3\n\n• One\n• Two", HtmlText.toPlainText(html))
    }

    @Test
    fun entitiesAreDecodedAndUnknownOnesKept() {
        assertEquals(
            "Café \"Zürich\" & co — 5 < 6 ’ &bogus; A",
            HtmlText.toPlainText(
                "Caf&eacute; &quot;Z&uuml;rich&quot; &amp; co &mdash; 5 &lt; 6 &#x2019; &bogus; &#65;",
            ),
        )
        assertEquals("ÿ ×", HtmlText.toPlainText("&yuml; &times;"))
    }

    @Test
    fun anUnclosedCommentOrHiddenBlockDropsTheRestAndAStrayBracketIsText() {
        assertEquals("Before", HtmlText.toPlainText("<p>Before</p><!-- never closed <p>After</p>"))
        assertEquals("Before", HtmlText.toPlainText("<p>Before</p><style>p { color: red }<p>After</p>"))
        assertEquals("5 < 6 and 7 > 3", HtmlText.toPlainText("5 < 6 and 7 > 3"))
        assertEquals("Encoded <p> stays text", HtmlText.toPlainText("Encoded &lt;p&gt; stays text"))
    }

    /** A head that is never closed, as many mailers send, still leaves the body's text (it was lost). */
    @Test
    fun aHeadWithoutItsEndTagKeepsTheBody() {
        assertEquals(
            "Your invoice is attached.\n\nTotal: 12,50 €",
            HtmlText.toPlainText(
                "<html><head><meta charset=\"utf-8\"><title>Invoice</title>" +
                    "<body><p>Your invoice is attached.</p><p>Total: 12,50 &euro;</p></body></html>",
            ),
        )
        assertEquals("Hello", HtmlText.toPlainText("<head><style>p { color: red }</style><p>Hello"))
    }

    /**
     * Markup a sender controls is read in time proportional to its length: an unclosed comment, a
     * run of unclosed tags or of spacing entities must not cost each character a scan of the rest.
     */
    @Test
    fun pathologicalMarkupIsReadInLinearTime() {
        val size = 120_000
        val inputs =
            mapOf(
                "unclosed comments" to "<!--x".repeat(size / 5),
                "unclosed tags" to "<p".repeat(size / 2),
                "unclosed styles" to "<style>".repeat(size / 7),
                "stray brackets" to "<".repeat(size),
                "spacing entities" to "&nbsp; ".repeat(size / 7),
                "spacing between blocks" to "<div> &nbsp; </div>".repeat(size / 19),
                "unclosed blocks" to "<div>x".repeat(size / 6),
                "unclosed formatting" to "<b><i>x".repeat(size / 7),
                "unclosed table cells" to "<table><tr><td>".repeat(size / 15),
            )
        for ((name, html) in inputs) {
            val started = System.nanoTime()
            HtmlText.toPlainText(html)
            val seconds = (System.nanoTime() - started) / 1e9
            assertTrue(seconds < 2, "$name took $seconds s")
        }
    }
}
