@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
import dev.npn.app.LocaleRestoringTest
import dev.npn.app.ui.closing
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.themedScene
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The Chat | Bulk edit switch in every language the app ships, on a narrow phone and on a desktop
 * window, with either mode selected: each label stays on one line inside its own segment, and the
 * two segments are the same size. Showing each language sets the JVM's default locale, which
 * [LocaleRestoringTest] puts back.
 */
class AssistantModeSwitchTest : LocaleRestoringTest() {
    @Test
    fun everyLabelFitsItsSegmentInEveryLanguage() =
        onUiThread {
            for (locale in LOCALES) {
                for (width in WIDTHS) {
                    for (selected in AssistantMode.entries) {
                        var labelSize = TextUnit.Unspecified
                        themedScene(width, HEIGHT, appLocale = locale) {
                            labelSize = MaterialTheme.typography.labelLarge.fontSize
                            AssistantModeSwitch(selected, onSelect = {})
                        }.closing {
                            render()
                            withClue("$locale at ${width}dp, $selected selected") {
                                // Only a screen too narrow for the labels shrinks them.
                                checkSegments(fullSize = labelSize.takeIf { width >= WIDE })
                            }
                        }
                    }
                }
            }
        }

    private fun ImageComposeScene.checkSegments(fullSize: TextUnit?) {
        val all = semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
        val segments = all.filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
        segments shouldHaveSize AssistantMode.entries.size
        // The same height, and the same width but for the pixel an odd row width leaves over.
        segments.map { it.boundsInRoot.height }.distinct() shouldHaveSize 1
        val widths = segments.map { it.boundsInRoot.width }
        (widths.max() - widths.min() <= 1f) shouldBe true
        for (segment in segments) {
            val label = descendants(segment).single { it.config.getOrNull(SemanticsProperties.Text) != null }
            val layout = label.textLayout()
            withClue(label.config[SemanticsProperties.Text].joinToString()) {
                layout.lineCount shouldBe 1
                layout.hasVisualOverflow shouldBe false
                segment.boundsInRoot.contains(label.boundsInRoot) shouldBe true
                if (fullSize != null) layout.layoutInput.style.fontSize shouldBe fullSize
            }
        }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    private fun Rect.contains(other: Rect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    private companion object {
        val LOCALES = listOf("en", "de", "es", "fr", "nl", "pt")

        /** A small phone, a common phone, and a desktop window (at density 1, pixels are dp). */
        val WIDTHS = listOf(320, 360, 1000)
        const val HEIGHT = 120

        /** From this width on, every language's labels fit at their own size. */
        const val WIDE = 600
    }
}
