package dev.npn.app

import dev.npn.app.ui.whatsnew.WhatsNewBlock
import dev.npn.app.ui.whatsnew.WhatsNewMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals

class WhatsNewMarkdownTest {
    @Test
    fun parsesHeadingsBulletsAndParagraphs() {
        val blocks =
            WhatsNewMarkdown.parse(
                """
                # What's new
                ## Report a problem
                - A **bold** bullet
                * Another bullet
                A paragraph.
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                WhatsNewBlock.Title("What's new"),
                WhatsNewBlock.Section("Report a problem"),
                WhatsNewBlock.Bullet("A bold bullet"),
                WhatsNewBlock.Bullet("Another bullet"),
                WhatsNewBlock.Paragraph("A paragraph."),
            ),
            blocks,
        )
    }

    @Test
    fun blankLinesAreDropped() {
        assertEquals(
            listOf(WhatsNewBlock.Title("Title")),
            WhatsNewMarkdown.parse("\n\n# Title\n\n"),
        )
    }

    @Test
    fun theTitleNamesTheVersionTheNotesAreFor() {
        assertEquals("1.2.3", WhatsNewMarkdown.version("# What's new in 1.2.3\n\n- A bullet"))
        assertEquals(null, WhatsNewMarkdown.version("# What's new\n\n- A bullet"))
        assertEquals(null, WhatsNewMarkdown.version("- No title"))
    }
}
