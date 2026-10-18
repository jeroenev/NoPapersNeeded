package dev.npn.core.app

import dev.npn.core.model.ReviewMode
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** Which documents each review mode's queue holds, as the library's review preset filters them. */
class ReviewQueueFilterTest {
    @Test
    fun untaggedOnlyQueuesTheUntaggedDocuments() {
        ReviewMode.UNTAGGED_ONLY.reviewQueueFilter() shouldBe LibraryFilter(untaggedOnly = true)
    }

    @Test
    fun theInboxWorkflowQueuesWhatIsUnreviewedOrUntagged() {
        ReviewMode.INBOX_WORKFLOW.reviewQueueFilter() shouldBe LibraryFilter(unreviewedOrUntagged = true)
    }
}
