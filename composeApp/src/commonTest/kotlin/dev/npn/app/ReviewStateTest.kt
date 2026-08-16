package dev.npn.app

import dev.npn.app.ui.library.effectiveReviewed
import dev.npn.app.ui.library.reviewMarkerIsAuthoritative
import dev.npn.core.app.DocListItem
import dev.npn.core.model.ReviewMode
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Pure tests for the single reviewed-state rule that used to be re-encoded at three call sites: the
 * "stored marker is authoritative only in INBOX_WORKFLOW; otherwise reviewed == has tags" rule.
 */
class ReviewStateTest {
    private fun item(
        reviewed: Boolean,
        tags: List<String>,
    ): DocListItem = DocListItem(doc = doc("d", reviewed = reviewed), tags = tags)

    @Test
    fun markerIsAuthoritativeOnlyInInboxWorkflow() {
        ReviewMode.INBOX_WORKFLOW.reviewMarkerIsAuthoritative shouldBe true
        ReviewMode.UNTAGGED_ONLY.reviewMarkerIsAuthoritative shouldBe false
    }

    @Test
    fun inboxWorkflowUsesTheStoredMarkerIgnoringTags() {
        // Marker wins even when it disagrees with tag presence, in both directions.
        item(reviewed = true, tags = emptyList()).effectiveReviewed(ReviewMode.INBOX_WORKFLOW) shouldBe true
        item(reviewed = false, tags = listOf("finance")).effectiveReviewed(ReviewMode.INBOX_WORKFLOW) shouldBe false
    }

    @Test
    fun untaggedOnlyDerivesReviewedFromTagsIgnoringTheStoredMarker() {
        // The stale stored marker is ignored; reviewed is purely "has any tag".
        item(reviewed = false, tags = listOf("finance")).effectiveReviewed(ReviewMode.UNTAGGED_ONLY) shouldBe true
        item(reviewed = true, tags = emptyList()).effectiveReviewed(ReviewMode.UNTAGGED_ONLY) shouldBe false
    }
}
