@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.resourcesIn
import dev.npn.app.ui.click
import dev.npn.app.ui.common.LocalAppLocale
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.texts
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.app.ui.type
import dev.npn.core.app.FieldRange
import dev.npn.core.app.LibraryFilter
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldType
import dev.npn.core.model.ReviewMode
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_apply_filters
import no_papers_needed.composeapp.generated.resources.library_clear_all
import no_papers_needed.composeapp.generated.resources.library_from
import org.jetbrains.compose.resources.getString
import java.util.Locale
import kotlin.test.Test

/**
 * A number range bound typed key by key into the filter panel, the bound coming back from the draft
 * the way the panel feeds it: only an entry that parses is committed, in canonical form.
 */
class FilterNumberBoundTest : EnglishLocaleTest() {
    @Test
    fun aDecimalBoundIsKeptAsTypedAndApplied() {
        val words = Words.of(Locale.ENGLISH)
        onUiThread {
            panel(words) {
                type("1.5")
                shown() shouldBe "1.5"
                apply()
                applied.single().fieldRanges[KEY] shouldBe FieldRange(from = "1.5")
            }
        }
    }

    @Test
    fun aCommaLocaleDecimalBoundIsKeptAsTypedAndApplied() {
        val words = Words.of(Locale.forLanguageTag("nl"))
        onUiThread {
            panel(words, language = "nl") {
                type("1,5")
                shown() shouldBe "1,5"
                apply()
                applied.single().fieldRanges[KEY] shouldBe FieldRange(from = "1.5")
            }
        }
    }

    @Test
    fun anInvalidBoundKeepsItsErrorAndBlocksApply() {
        val words = Words.of(Locale.ENGLISH)
        onUiThread {
            panel(words) {
                type("1.")
                shown() shouldBe "1."
                field().config.getOrNull(SemanticsProperties.Error).shouldNotBeNull()
                scene.nodes().any { "Enter a number" in it.texts() } shouldBe true
                applyButton().config.contains(SemanticsProperties.Disabled) shouldBe true
            }
        }
    }

    @Test
    fun resizingTheBoundsKeepsInvalidTextAndBlocksApply() {
        val words = Words.of(Locale.ENGLISH)
        onUiThread {
            panel(words) {
                type("1.")
                for (width in listOf(300, WIDTH)) {
                    panelWidth = width
                    repeat(3) { scene.render() }
                    shown() shouldBe "1."
                    field().config.getOrNull(SemanticsProperties.Error).shouldNotBeNull()
                    applyButton().config.contains(SemanticsProperties.Disabled) shouldBe true
                }
            }
        }
    }

    @Test
    fun clearAllEmptiesTheBound() {
        val words = Words.of(Locale.ENGLISH)
        onUiThread {
            panel(words) {
                type("1.")
                scene.click { words.clearAll in it.texts() }
                scene.render()
                shown() shouldBe ""
                applyButton().config.contains(SemanticsProperties.Disabled) shouldBe false
            }
        }
    }

    @Test
    fun aFilterChangedElsewhereReplacesTheBound() {
        val words = Words.of(Locale.ENGLISH)
        onUiThread {
            panel(words) {
                type("1.")
                filter = LibraryFilter(fieldRanges = mapOf(KEY to FieldRange(from = "3")))
                scene.render()
                shown() shouldBe "3"
                // The invalid entry is gone with its replacement; Apply is back on, so the change
                // is not stuck behind an entry that is no longer there.
                repeat(2) { scene.render() }
                applyButton().config.contains(SemanticsProperties.Disabled) shouldBe false

                filter = LibraryFilter()
                scene.render()
                shown() shouldBe ""
                repeat(2) { scene.render() }
                applyButton().config.contains(SemanticsProperties.Disabled) shouldBe false
            }
        }
    }

    /**
     * Beside the list on a wide window, the search changes the filter while the panel is open: only
     * the search changes in the panel's draft, and a bound typed and not applied stays.
     */
    @Test
    fun aLiveSearchKeepsAnUnappliedBound() {
        val words = Words.of(Locale.ENGLISH)
        onUiThread {
            panel(words) {
                type("3")
                filter = filter.copy(query = "electricity")
                scene.render()
                shown() shouldBe "3"

                apply()
                applied.single() shouldBe
                    LibraryFilter(query = "electricity", fieldRanges = mapOf(KEY to FieldRange(from = "3")))
            }
        }
    }

    /** The panel's words in one locale, read before the scene sets that locale. */
    private class Words(
        val from: String,
        val apply: String,
        val clearAll: String,
    ) {
        companion object {
            fun of(locale: Locale): Words {
                val resources = resourcesIn(locale)
                return runBlocking {
                    Words(
                        from = getString(resources, Res.string.library_from),
                        apply = getString(resources, Res.string.library_apply_filters),
                        clearAll = getString(resources, Res.string.library_clear_all),
                    )
                }
            }
        }
    }

    private inner class Panel(
        val words: Words,
    ) {
        var filter by mutableStateOf(LibraryFilter())
        var panelWidth by mutableStateOf(WIDTH)
        val applied = mutableListOf<LibraryFilter>()
        lateinit var scene: ImageComposeScene

        fun field(): SemanticsNode =
            scene.nodes().first {
                it.config.getOrNull(SemanticsProperties.EditableText) != null &&
                    words.from in it.texts()
            }

        fun shown(): String? = field().config.getOrNull(SemanticsProperties.EditableText)?.text

        fun applyButton(): SemanticsNode =
            scene.nodes().first { words.apply in it.texts() && it.config.contains(SemanticsActions.OnClick) }

        fun apply() {
            applyButton().config.contains(SemanticsProperties.Disabled) shouldBe false
            applyButton().config[SemanticsActions.OnClick].action?.invoke()
            scene.render()
        }

        /**
         * Type [keys] one at a time into whatever the "from" bound shows, then let the bound's
         * validity effect reach the Apply button.
         */
        fun type(keys: String) {
            field().config[SemanticsActions.RequestFocus].action?.invoke()
            scene.render()
            for (key in keys) {
                field().config[SemanticsActions.InsertTextAtCursor].action?.invoke(AnnotatedString(key.toString()))
                scene.render()
            }
            repeat(2) { scene.render() }
        }
    }

    private fun panel(
        words: Words,
        language: String? = null,
        block: Panel.() -> Unit,
    ) {
        val panel = Panel(words)
        val content: @Composable () -> Unit = {
            NpnTheme(darkTheme = false) {
                FilterPanel(
                    editor = rememberFilterEditorState(panel.filter),
                    tags = emptyList(),
                    fields = FieldFilterOptions(listOf(AMOUNT)),
                    reviewMode = ReviewMode.DEFAULT,
                    onApply = { panel.applied += it },
                    onDismiss = {},
                    fillHeight = false,
                    modifier = Modifier.width(panel.panelWidth.dp),
                )
            }
        }
        panel.scene =
            ImageComposeScene(WIDTH, HEIGHT) {
                // Only when asked: the app-language local sets the JVM's locale as it is provided.
                if (language == null) content() else CompositionLocalProvider(LocalAppLocale provides language, content)
            }
        try {
            panel.scene.render()
            panel.block()
        } finally {
            panel.scene.close()
        }
    }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 900
        const val KEY = "amount"
        val AMOUNT =
            FieldDef(KEY, "Amount", FieldType.DECIMAL, showByDefault = true, aiEnabled = false, position = 5)
    }
}
