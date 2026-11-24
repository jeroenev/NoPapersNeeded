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
                    SyncAction.UploadTrash("trash/t.pdf", docId = "b"),
                    SyncAction.DeleteRemote(null, "old.pdf", RemoteEntry("old.pdf", 1, 1)),
                ),
            pushMetadata = listOf(SyncAction.UploadObject(".npn/journal/objects-v2-ab/object.jsonl")),
            conflicts = emptyList(),
        )

    @Test
    fun thePlanCountsEverythingItWouldTransfer() {
        // A move or delete is not a transfer of its own; a trash marker is.
        plan.plannedUploads shouldBe 3
        plan.plannedDownloads shouldBe 2
    }

    @Test
    fun whatThePassFinishedIsNotPending() {
        val builder = SyncReportBuilder(startedAt = 1_000)
        builder.uploadedDocuments = 1
        // One journal object and one trash marker, the plan's only other uploads.
        builder.uploadedObjects = 2
        builder.downloadedDocuments = 1
        builder.downloadedTrash = 1

        val report = builder.build(nowMillis = 2_000, plan = this.plan, planCheckedAt = 1_500)

        report.pendingUploads shouldBe 0
        report.pendingDownloads shouldBe 0
        report.pendingCheckedAt shouldBe 1_500
    }

    @Test
    fun whatThePassCouldNotFinishStaysPendingForTheNextOne() {
        val builder = SyncReportBuilder(startedAt = 1_000)
        builder.error("b.pdf", IllegalStateException("remote closed"))

        val report = builder.build(nowMillis = 2_000, plan = plan)

        report.pendingUploads shouldBe 3
        report.pendingDownloads shouldBe 2
    }

    @Test
    fun trashUploadsDoNotMakeABlockedDocumentsPendingCountDisappear() {
        // Metadata and a trash marker succeeded, but the document upload failed.
        val builder = SyncReportBuilder(startedAt = 1_000)
        builder.uploadedObjects = 2
        builder.error("b.pdf", IllegalStateException("remote closed"))

        builder.build(nowMillis = 2_000, plan = plan).pendingUploads shouldBe 1
    }

    @Test
    fun aPassThatNeverPlannedSaysNothingAboutPendingWork() {
        val report = SyncReportBuilder(startedAt = 1_000).build(nowMillis = 2_000)

        report.pendingUploads shouldBe 0
        report.pendingDownloads shouldBe 0
        report.pendingCheckedAt shouldBe null
    }
}
