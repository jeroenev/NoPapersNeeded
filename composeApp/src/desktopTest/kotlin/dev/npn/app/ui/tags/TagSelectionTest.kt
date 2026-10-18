package dev.npn.app.ui.tags

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.FakeLibrarySession
import dev.npn.app.ui.closing
import dev.npn.app.ui.has
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.text
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.app.TagInfo
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** The tags screen's selection holds only tags that still exist, as tags vanish under it on a sync. */
class TagSelectionTest : EnglishLocaleTest() {
    @Test
    fun aSelectionForgetsTheTagsThatVanish() =
        onUiThread {
            val session = tagSession()
            hostedScene(WIDTH, HEIGHT) { NpnTheme(darkTheme = false) { TagManagementScreen(session) } }.closing {
                render()
                longClick { it.text() == "tax" }
                longClick { it.text() == "invoice" }
                has { it.text() == "2 selected" } shouldBe true

                session.tags.tags.value = listOf(TagInfo("invoice", null, null, 2))
                render()
                render()
                has { it.text() == "1 selected" } shouldBe true

                session.tags.tags.value = emptyList()
                render()
                render()
                has { it.text()?.endsWith("selected") == true } shouldBe false

                // A tag of the old name coming back is not selected again.
                session.tags.tags.value = listOf(TagInfo("tax", null, null, 1))
                render()
                render()
                has { it.text()?.endsWith("selected") == true } shouldBe false
            }
        }

    private fun tagSession() =
        FakeLibrarySession().apply {
            tags.tags.value = listOf(TagInfo("invoice", null, null, 2), TagInfo("tax", null, null, 1))
        }

    /** Long-click the nearest long-clickable node at or above the first node [match] finds. */
    private fun ImageComposeScene.longClick(match: (SemanticsNode) -> Boolean) {
        generateSequence(nodes().first(match)) { it.parent }
            .first { it.config.getOrNull(SemanticsActions.OnLongClick) != null }
            .config[SemanticsActions.OnLongClick]
            .action
            ?.invoke()
        render()
    }

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 900
    }
}
