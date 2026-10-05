package dev.npn.app

import dev.npn.app.ui.common.HighlightSegment
import dev.npn.app.ui.common.activeSearchToken
import dev.npn.app.ui.common.completeActiveSearchToken
import dev.npn.app.ui.common.parseFtsSnippet
import dev.npn.app.ui.common.parseQueryHighlight
import dev.npn.app.ui.common.queryTerms
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class SearchHighlightTest {
    @Test
    fun parsesFtsMarkersWithoutTreatingLiteralBracketsAsMarkers() {
        parseFtsSnippet("literal [text] \u0001match\u0002") shouldContainExactly
            listOf(
                HighlightSegment("literal [text] ", false),
                HighlightSegment("match", true),
            )
    }

    @Test
    fun unmatchedMarkerIsPlainText() {
        parseFtsSnippet("before \u0001unfinished") shouldContainExactly
            listOf(
                HighlightSegment("before ", false),
                HighlightSegment("\u0001unfinished", false),
            )
    }

    @Test
    fun queryHighlightIsCaseInsensitiveAndSupportsQuotedTerms() {
        queryTerms("invoice \"New York\" invoice") shouldContainExactly listOf("invoice", "New York")
        parseQueryHighlight("NEW YORK invoice", "\"new york\" INVOICE") shouldContainExactly
            listOf(
                HighlightSegment("NEW YORK", true),
                HighlightSegment(" ", false),
                HighlightSegment("invoice", true),
            )
    }

    @Test
    fun activeTokenCompletionPreservesEarlierTermsAndQuotes() {
        activeSearchToken("annual inv") shouldBe "inv"
        completeActiveSearchToken("annual inv", "invoice") shouldBe "annual invoice"
        completeActiveSearchToken("annual \"new", "New York") shouldBe "annual \"New York\""
        activeSearchToken("annual \"new y") shouldBe "new y"
        completeActiveSearchToken("annual \"new y", "New York") shouldBe "annual \"New York\""
    }
}
