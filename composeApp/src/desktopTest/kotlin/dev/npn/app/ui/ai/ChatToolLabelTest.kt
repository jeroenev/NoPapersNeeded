@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.ai

import dev.npn.app.EnglishLocaleTest
import dev.npn.app.ui.closing
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.themedScene
import dev.npn.core.ai.ChatTools
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ChatToolLabelTest : EnglishLocaleTest() {
    @Test
    fun newToolLabelsResolveTheirResources() =
        onUiThread {
            val labels = mutableListOf<String>()
            themedScene(600, 200) {
                labels.clear()
                labels += toolLabel(ChatTools.SEARCH_DOCUMENTS, emptyMap())
                labels += toolLabel(ChatTools.GET_DOCUMENT_HISTORY, mapOf(ChatTools.ARG_DOC_ID to "12345678"))
                labels += toolLabel(ChatTools.LIBRARY_STATS, emptyMap())
            }.closing { render() }
            labels shouldBe
                listOf("Searched documents by filters", "Read history of 12345678", "Read library statistics")
        }
}
