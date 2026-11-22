package dev.npn.core.sync

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** Pending counts: what the plan found, less what the pass actually finished. */
class SyncPendingCountsTest {
    private val plan =
        SyncPlan(
            local =
                listOf(
                    SyncAction.PublishDocument("a", "a.pdf", "a.pdf"),
                    SyncAction.DownloadTrash(RemoteEntry("t.pdf", 1, 1)),
                    SyncAction.DeleteLocalFile("gone", "gone.pdf"),
                ),
            remote =
                listOf(
                    SyncAction.UploadDocument("b", "b.pdf", "b.pdf"),
                    SyncAction.DeleteRemote(null, "old.pdf", RemoteEntry("old.pdf", 1, 1)),
                ),
            pushMetadata = listOf(SyncAction.UploadObject(".npn/journal/objects-v2-ab/object.jsonl")),
            conflicts = emptyList(),
        )

    @Test
    fun thePlanCountsDocumentsAndMetadataItWouldTransfer() {
        // A move, delete or trash copy is not a document upload or download of its own.
        plan.plannedUploads shouldBe 2
        plan.plannedDownloads shouldBe 1
    }

    @Test
    fun whatThePassFinishedIsNotPending() {
        val builder = SyncReportBuilder(startedAt = 1_000)
        builder.uploadedDocuments = 1
        builder.uploadedObjects = 1

        val report = builder.build(nowMillis = 2_000, plan = this.plan)

        report.pendingUploads shouldBe 0
        report.pendingDownloads shouldBe 1
        report.pendingCheckedAt shouldBe 2_000
    }

    @Test
    fun whatThePassCouldNotFinishStaysPendingForTheNextOne() {
        val builder = SyncReportBuilder(startedAt = 1_000)
        builder.error("b.pdf", IllegalStateException("remote closed"))

        val report = builder.build(nowMillis = 2_000, plan = plan)

        report.pendingUploads shouldBe 2
        report.pendingDownloads shouldBe 1
    }

    @Test
    fun aPassThatNeverPlannedSaysNothingAboutPendingWork() {
        val report = SyncReportBuilder(startedAt = 1_000).build(nowMillis = 2_000)

        report.pendingUploads shouldBe 0
        report.pendingDownloads shouldBe 0
        report.pendingCheckedAt shouldBe null
    }
}
