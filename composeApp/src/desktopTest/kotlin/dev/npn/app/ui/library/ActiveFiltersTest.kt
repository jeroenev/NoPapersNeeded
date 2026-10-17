@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.library

import androidx.compose.ui.ImageComposeScene
import dev.npn.app.LocaleRestoringTest
import dev.npn.app.ui.closing
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.texts
import dev.npn.app.ui.themedScene
import dev.npn.core.app.FieldRange
import dev.npn.core.app.LibraryFilter
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldType
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

/** The chips under the search bar read a field's value the way the filter sheet shows it. */
class ActiveFiltersTest : LocaleRestoringTest() {
    @Test
    fun aDecimalBoundReadsInTheAppLocale() =
        onUiThread {
            val amount =
                FieldDef("amount", "Bedrag", FieldType.DECIMAL, showByDefault = true, aiEnabled = false, position = 5)
            val filter = LibraryFilter(fieldRanges = mapOf("amount" to FieldRange(from = "12.5")))
            themedScene(WIDTH, HEIGHT, appLocale = "nl") {
                ActiveFilters(filter = filter, fields = listOf(amount), onFilterChange = {})
            }.closing {
                render()
                texts().single { "Bedrag" in it } shouldContain "12,5"
            }
        }

    private fun ImageComposeScene.texts(): List<String> = nodes().flatMap { it.texts() }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 200
    }
}
