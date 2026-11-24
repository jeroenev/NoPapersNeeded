package dev.npn.core.sync

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** The settings screen's reading of the live status: schedule, streak, pending counts. */
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
    fun anUnconfiguredLibraryIsNotHealthyAndHasNoSchedule() {
        val health = SyncHealth.of(CloudSyncConfig.DISABLED, SyncStatus(), nowMillis = finishedAt)

        health.configured shouldBe false
        health.healthy shouldBe false
        health.nextAutomaticAt.shouldBeNull()
        health.lastSuccessAt.shouldBeNull()
        health.pendingAsOf.shouldBeNull()
    }

    @Test
    fun aFinishedPassCarriesItsScheduleStreakAndPendingWork() {
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
                nowMillis = finishedAt + minute,
            )

        health.configured shouldBe true
        health.automatic shouldBe true
        // Pending work is still owed, so the library is not settled even though the pass finished.
        health.healthy shouldBe false
        health.consecutiveFailures shouldBe 0
        health.lastSuccessAt shouldBe finishedAt
        health.nextAutomaticAt shouldBe finishedAt + 15 * minute
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
                nowMillis = finishedAt,
            )

        health.healthy shouldBe false
        health.lastFailure shouldBe SyncFailure.REMOTE_UNREACHABLE
        health.lastFailureDetail shouldBe "no route"
        health.consecutiveFailures shouldBe 4
        health.lastSuccessAt shouldBe lastSuccess
        health.attentionCount shouldBe 1
        health.nextAutomaticAt.shouldBeNull()
    }

    @Test
    fun aSuccessfulSummaryFromAnOlderBuildStillNamesItsSuccess() {
        // The field arrived with this build: an older persisted summary has only finishedAt.
        val summary = SyncRunSummary(startedAt = finishedAt - 1_000, finishedAt = finishedAt, report = report())

        val health =
            SyncHealth.of(
                CloudSyncConfig(remote = "drive:Library"),
                SyncStatus(lastRun = summary),
                nowMillis = finishedAt,
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
                nowMillis = finishedAt,
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

        SyncHealth.of(config, SyncStatus(), nowMillis = finishedAt).healthy shouldBe false
        SyncHealth.of(config, SyncStatus(lastRun = summary), nowMillis = finishedAt).healthy shouldBe true
    }

    @Test
    fun aDueTimeAlreadyPastIsNotShownAsTheNextPass() {
        // A pass that never reached the engine (no engine, unreachable remote) leaves finishedAt
        // where it was, and the card must not promise a time that has long gone.
        val summary = SyncRunSummary(startedAt = finishedAt, finishedAt = finishedAt, lastSuccessAt = finishedAt)
        val config = CloudSyncConfig(enabled = true, remote = "drive:Library", intervalMinutes = 5)

        val health =
            SyncHealth.of(
                config,
                SyncStatus(lastRun = summary),
                nowMillis = finishedAt + 60 * minute,
            )

        health.nextAutomaticAt.shouldBeNull()
    }
}
