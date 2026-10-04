@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.library

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.npn.app.ui.common.LocalAppLocale
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.app.FieldRange
import dev.npn.core.app.LibraryFilter
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldType
import io.kotest.matchers.string.shouldContain
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** The chips under the search bar read a field's value the way the filter sheet shows it. */
class ActiveFiltersTest {
    private var previousLocale: Locale? = null

    @BeforeTest
    fun rememberLocale() {
        previousLocale = Locale.getDefault()
    }

    @AfterTest
    fun restoreLocale() {
        previousLocale?.let { Locale.setDefault(it) }
    }

    @Test
    fun aDecimalBoundReadsInTheAppLocale() =
        onUiThread {
            val amount =
                FieldDef("amount", "Bedrag", FieldType.DECIMAL, showByDefault = true, aiEnabled = false, position = 5)
            val filter = LibraryFilter(fieldRanges = mapOf("amount" to FieldRange(from = "12.5")))
            val scene =
                ImageComposeScene(WIDTH, HEIGHT) {
                    CompositionLocalProvider(LocalAppLocale provides "nl") {
                        NpnTheme(darkTheme = false) {
                            Surface { ActiveFilters(filter = filter, fields = listOf(amount), onFilterChange = {}) }
                        }
                    }
                }
            try {
                scene.render()
                scene.texts().single { "Bedrag" in it } shouldContain "12,5"
            } finally {
                scene.close()
            }
        }

    private fun ImageComposeScene.texts(): List<String> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return semanticsOwners
            .flatMap { descendants(it.rootSemanticsNode) }
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
    }

    private fun onUiThread(block: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { runCatching(block).onFailure { failure = it } }
        failure?.let { throw it }
    }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 200
    }
}
