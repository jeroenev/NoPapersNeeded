package dev.npn.core.mail

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
