package dev.npn.app.ui.ai

import dev.npn.core.app.ReviewChip
import dev.npn.core.app.ReviewDoc
import dev.npn.core.app.ReviewField
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Bulk selection over a review batch. "Recommended" has to mean exactly what the domain's
 * pre-select policy means, or the header's shortcut quietly disagrees with the rows.
 */
class ReviewSelectionTest {
    private val docs =
        listOf(
            ReviewDoc(
                docId = "a",
                title = "A",
                fileName = "a.pdf",
                snippet = null,
                chips =
                    listOf(
                        ReviewChip("invoice", isNew = false, selected = false),
                        ReviewChip("brandnew", isNew = true, selected = true),
                    ),
                removals = listOf(ReviewChip("utility", isNew = false, selected = true)),
                fields =
                    listOf(
                        ReviewField("title", "Title", "New", current = null, selected = false),
                        ReviewField("correspondent", "From", "Acme", current = "Old", selected = true),
                    ),
            ),
        )

    @Test
    fun recommendedIsExistingTagsAndEmptyFieldsOnly() {
        val doc = docs.selectRecommended().single()

        doc.chips.single { it.tag == "invoice" }.selected shouldBe true
        doc.chips.single { it.tag == "brandnew" }.selected shouldBe false
        doc.removals.single().selected shouldBe false
        doc.fields.single { it.key == "title" }.selected shouldBe true
        doc.fields.single { it.key == "correspondent" }.selected shouldBe false
    }

    @Test
    fun selectAllStillTakesEverythingIncludingTheRiskyOnes() {
        val doc = docs.setAllSelected(true).single()

        (doc.chips + doc.removals).all { it.selected } shouldBe true
        doc.fields.all { it.selected } shouldBe true
    }
}
