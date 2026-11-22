package dev.npn.app

import dev.npn.app.ui.palette.PALETTE_GROUP_LIMIT
import dev.npn.app.ui.palette.SCORE_FUZZY
import dev.npn.app.ui.palette.SCORE_PREFIX
import dev.npn.app.ui.palette.SCORE_SUBSTRING
import dev.npn.app.ui.palette.SCORE_WORD
import dev.npn.app.ui.palette.paletteMatchScore
import dev.npn.app.ui.palette.rankPalette
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PaletteRankingTest {
    private fun rank(
        query: String,
        items: List<String>,
        recents: List<String> = emptyList(),
        includeUnmatched: Boolean = false,
        limit: Int = PALETTE_GROUP_LIMIT,
    ): List<String> =
        rankPalette(
            query = query,
            items = items,
            recents = recents,
            includeUnmatched = includeUnmatched,
            limit = limit,
            key = { it },
            text = { it },
        )

    @Test
    fun prefixBeatsWordWhichBeatsSubstringWhichBeatsFuzzy() {
        rank("ivc", listOf("tax invoice", "myivc", "invoice copy", "an ivc tag", "ivc invoice"))
            .shouldContainExactly(listOf("ivc invoice", "an ivc tag", "myivc", "invoice copy"))
    }

    @Test
    fun theScoreNamesTheKindOfMatch() {
        paletteMatchScore("inv", "invoices") shouldBe SCORE_PREFIX
        paletteMatchScore("inv", "tax invoice") shouldBe SCORE_WORD
        paletteMatchScore("inv", "myinvoice") shouldBe SCORE_SUBSTRING
        paletteMatchScore("ivc", "invoices") shouldBe SCORE_FUZZY
        paletteMatchScore("zzz", "invoices") shouldBe 0
    }

    @Test
    fun everyWordOfAMultiWordQueryMustMatch() {
        rank("tax 2024", listOf("Tax 2024", "invoice 2024 tax", "tax only")) shouldContainExactly
            listOf("Tax 2024", "invoice 2024 tax")
    }

    @Test
    fun nonMatchesAreDroppedUnlessTheSourceAlreadyMatchedThem() {
        rank("zzz", listOf("invoices", "tax")) shouldContainExactly emptyList()
        rank("zzz", listOf("invoices", "tax"), includeUnmatched = true) shouldContainExactly
            listOf("invoices", "tax")
    }

    @Test
    fun aRecentChoiceLeadsAnEmptyQuery() {
        rank("", listOf("a", "b", "c"), recents = listOf("b")) shouldContainExactly
            listOf("b", "a", "c")
    }

    @Test
    fun recencyOnlyBreaksATieBetweenEquallyGoodMatches() {
        rank("tax", listOf("tax invoice", "vat tax", "tax 2024"), recents = listOf("vat tax", "tax 2024"))
            .shouldContainExactly(listOf("tax 2024", "tax invoice", "vat tax"))
    }

    @Test
    fun aGroupIsBounded() {
        rank("tag", (1..20).map { "tag$it" }).size shouldBe PALETTE_GROUP_LIMIT
    }
}
