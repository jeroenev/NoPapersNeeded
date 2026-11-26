package dev.npn.app.ui.tags

import dev.npn.app.EnglishLocaleTest
import dev.npn.app.FakeLibrarySession
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.contentDescription
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.text
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.app.ui.type
import io.kotest.matchers.collections.shouldContainExactly
import kotlin.test.Test

/** The Tags screen's "new tag": what keeps a tag no document carries is its marker, not a color. */
class TagCreateTest : EnglishLocaleTest() {
    @Test
    fun aNewTagNeedsNoColor() =
        onUiThread {
            val session = FakeLibrarySession()
            hostedScene(WIDTH, HEIGHT) { NpnTheme(darkTheme = false) { TagManagementScreen(session) } }.closing {
                render()
                click { it.contentDescription() == "New tag" }
                render()
                type("receipts")
                click { it.text() == "Create" }
                render()
                render()

                session.tagAdmin.createdTags shouldContainExactly listOf(Triple("receipts", null, null))
            }
        }

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 900
    }
}
