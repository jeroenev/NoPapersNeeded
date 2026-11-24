package dev.npn.app

import dev.npn.app.ui.whatsnew.WhatsNewBlock
import dev.npn.app.ui.whatsnew.WhatsNewMarkdown
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import kotlin.test.Test
import kotlin.test.assertTrue

/** The bundled notes must be where the dialog looks, and parse to something it can show. */
class WhatsNewResourceTest {
    @Test
    fun theBundledNotesAreReadableAndParse() =
        runTest {
            val markdown = Res.readBytes("files/whats-new.md").decodeToString()
            val blocks = WhatsNewMarkdown.parse(markdown)

            assertTrue(blocks.any { it is WhatsNewBlock.Title })
            assertTrue(blocks.filterIsInstance<WhatsNewBlock.Bullet>().isNotEmpty())
        }
}
