package dev.npn.core.app

import dev.npn.core.app.aiedit.ReviewChip
import dev.npn.core.app.aiedit.ReviewDoc
import dev.npn.core.app.aiedit.ReviewField
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** The pre-select policy, as the review types state it (CONTEXT.md, "Review"). */
class ReviewPolicyTest {
    private val doc =
        ReviewDoc(
            docId = "a",
            title = null,
            fileName = "a.pdf",
            snippet = null,
            chips =
                listOf(
                    ReviewChip("known", isNew = false, selected = false),
                    ReviewChip("invented", isNew = true, selected = true),
                ),
            removals = listOf(ReviewChip("stale", isNew = false, selected = true)),
            fields =
                listOf(
                    ReviewField("title", "Title", "Invoice", current = null, selected = false),
                    ReviewField("amount", "Amount", "12", current = "10", selected = true),
                ),
        )

    @Test
    fun theRecommendedSelectionTakesKnownTagsAndFillInsOnly() {
        val recommended = doc.recommended()

        recommended.chips.map { it.tag to it.selected } shouldBe listOf("known" to true, "invented" to false)
        recommended.removals.map { it.selected } shouldBe listOf(false)
        recommended.fields.map { it.key to it.selected } shouldBe listOf("title" to true, "amount" to false)
    }

    @Test
    fun inventedTagsRemovalsAndOverwritesNeedAttention() {
        doc.needsAttention shouldBe 3
        doc.fields.map { it.recommended } shouldBe listOf(true, false)
    }
}
