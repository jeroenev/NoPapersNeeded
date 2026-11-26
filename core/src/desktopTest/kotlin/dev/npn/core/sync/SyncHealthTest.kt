package dev.npn.core.sync

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** The settings screen's reading of the live status: streak, pending counts, and whether it is in sync. */
class SyncHealthTest {
    private val finishedAt = 1_700_000_000_000L
    private val minute = 60_000L

    private fun report(
        pendingUploads: Int = 0,
        pendingDownloads: Int = 0,
        checkedAt: Long? = null,
        doneUploads: Int = 0,
        doneDownloads: Int = 0,
    ) = SyncReport(
        startedAt = finishedAt - 1_000,
        durationMillis = 1_000,
        uploadedDocuments = doneUploads,
        downloadedDocuments = doneDownloads,
        pendingUploads = pendingUploads,
        pendingDownloads = pendingDownloads,
        pendingCheckedAt = checkedAt,
    )

    @Test
    fun anUnconfiguredLibraryIsNotHealthy() {
        val health = SyncHealth.of(CloudSyncConfig.DISABLED, SyncStatus())

        health.configured shouldBe false
        health.healthy shouldBe false
        health.lastSuccessAt.shouldBeNull()
        health.pendingAsOf.shouldBeNull()
    }

    @Test
    fun aFinishedPassCarriesItsStreakAndPendingWork() {
        val summary =
            SyncRunSummary(
                startedAt = finishedAt - 2_000,
                finishedAt = finishedAt,
                report = report(pendingUploads = 3, pendingDownloads = 1, checkedAt = finishedAt - 1_000),
                lastSuccessAt = finishedAt,
                pendingUploads = 3,
                pendingDownloads = 1,
                pendingCheckedAt = finishedAt - 1_000,
            )
        val config = CloudSyncConfig(enabled = true, remote = "drive:Library", intervalMinutes = 15)

        val health =
            SyncHealth.of(
                config,
                SyncStatus(running = false, lastRun = summary),
            )

        health.configured shouldBe true
        health.automatic shouldBe true
        // Pending work is still owed, so the library is not settled even though the pass finished.
        health.healthy shouldBe false
        health.consecutiveFailures shouldBe 0
        health.lastSuccessAt shouldBe finishedAt
        health.pendingUploads shouldBe 3
        health.pendingDownloads shouldBe 1
        health.pendingAsOf shouldBe finishedAt - 1_000
    }

    @Test
    fun aFailedPassKeepsItsStreakAndTheLastSuccess() {
        val lastSuccess = finishedAt - 10 * minute
        val summary =
            SyncRunSummary(
                startedAt = finishedAt - 1_000,
                finishedAt = finishedAt,
                failure = SyncFailure.REMOTE_UNREACHABLE,
                failureDetail = "no route",
                consecutiveFailures = 4,
                lastSuccessAt = lastSuccess,
            )
        val config = CloudSyncConfig(enabled = false, remote = "drive:Library")

        val health =
            SyncHealth.of(
                config,
                SyncStatus(lastRun = summary),
            )

        health.healthy shouldBe false
        health.lastFailure shouldBe SyncFailure.REMOTE_UNREACHABLE
        health.lastFailureDetail shouldBe "no route"
        health.consecutiveFailures shouldBe 4
        health.lastSuccessAt shouldBe lastSuccess
        health.attentionCount shouldBe 1
    }

    @Test
    fun aSuccessfulSummaryFromAnOlderBuildStillNamesItsSuccess() {
        // The field arrived with this build: an older persisted summary has only finishedAt.
        val summary = SyncRunSummary(startedAt = finishedAt - 1_000, finishedAt = finishedAt, report = report())

        val health =
            SyncHealth.of(
                CloudSyncConfig(remote = "drive:Library"),
                SyncStatus(lastRun = summary),
            )

        health.lastSuccessAt shouldBe finishedAt
        health.consecutiveFailures shouldBe 0
    }

    @Test
    fun aRunningPassKeepsItsProgressAndConflictsAreCounted() {
        val conflict = SyncConflict(docId = "a", remotePath = "a.pdf", kind = ConflictKind.TARGET_OCCUPIED)
        val summary =
            SyncRunSummary(
                startedAt = finishedAt,
                finishedAt = finishedAt,
                report = report().copy(conflicts = listOf(conflict), errors = listOf(SyncError("b.pdf", "nope"))),
            )
        val progress = SyncProgress(SyncPhase.PUSH_DOCUMENTS, done = 2, total = 5)

        val health =
            SyncHealth.of(
                CloudSyncConfig(remote = "drive:Library"),
                SyncStatus(running = true, progress = progress, lastRun = summary),
            )

        health.running shouldBe true
        health.progress shouldBe progress
        health.conflicts shouldBe listOf(conflict)
        health.attentionCount shouldBe 2
        health.healthy shouldBe false
    }

    @Test
    fun aConfiguredLibraryWithNoPendingWorkIsHealthyOnlyOnceAPassFinished() {
        val summary =
            SyncRunSummary(
                startedAt = finishedAt - 1_000,
                finishedAt = finishedAt,
                report = report(pendingUploads = 0, pendingDownloads = 0, checkedAt = finishedAt),
                lastSuccessAt = finishedAt,
                pendingCheckedAt = finishedAt,
            )
        val config = CloudSyncConfig(enabled = true, remote = "drive:Library")

        SyncHealth.of(config, SyncStatus()).healthy shouldBe false
        SyncHealth.of(config, SyncStatus(lastRun = summary)).healthy shouldBe true
    }

    @Test
    fun aPassThatCouldNotPullAnotherDevicesChangesIsNoSuccess() {
        val lastSuccess = finishedAt - 10 * minute
        val stuck = report().copy(errors = listOf(SyncError("journal object", "unreadable")), documentsSynced = false)
        val summary =
            SyncRunSummary(
                startedAt = finishedAt - 1_000,
                finishedAt = finishedAt,
                report = stuck,
                consecutiveFailures = 2,
                lastSuccessAt = lastSuccess,
            )

        val health = SyncHealth.of(CloudSyncConfig(remote = "drive:Library"), SyncStatus(lastRun = summary))

        summary.ok shouldBe true
        summary.synced shouldBe false
        health.healthy shouldBe false
        health.lastSuccessAt shouldBe lastSuccess
        health.consecutiveFailures shouldBe 2
    }
}
