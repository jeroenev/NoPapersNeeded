package dev.npn.core.sync

import dev.npn.core.index.IndexRig
import dev.npn.core.index.MutableClock
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * The service around the engine: the library's own settings, the single-flight gate, the persisted
 * last run, the schedule and the debounced "soon" pass. A real [SyncEngine] over an empty library
 * and an empty directory remote keeps a pass cheap; the transport is scripted so it can stall, fail
 * or report itself absent. The service's own clock is a hand-cranked [MutableClock]; `delay`s run on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncServiceTest {
    /** A directory-backed transport whose listing can be gated, failed and counted. */
    private class ScriptedTransport(
        private val store: DirectoryRemoteStore,
    ) : SyncTransport {
        var availability: TransportAvailability = TransportAvailability.Available("test")
        var availabilityCalls = 0
        var opens = 0

        /** When set, every `list()` waits here first, holding the pass in its LISTING phase. */
        var listGate: CompletableDeferred<Unit>? = null

        /** When set, `list()` throws it, failing the pass as a broken transport would. */
        var listFailure: Throwable? = null

        /** What [failureOf] reads any failure as. */
        var reason: SyncFailure = SyncFailure.UNKNOWN

        override fun failureOf(failure: Throwable): SyncFailure = reason

        /** Number of listings started so far, i.e. of passes that reached the engine. */
        val listCalls = MutableStateFlow(0)

        override suspend fun availability(): TransportAvailability {
            availabilityCalls++
            return availability
        }

        override fun open(remote: String): RemoteStore {
            opens++
            return GatedStore()
        }

        private inner class GatedStore : RemoteStore by store {
            override suspend fun list(): List<RemoteEntry> {
                listCalls.value += 1
                listGate?.await()
                listFailure?.let { throw it }
                return store.list()
            }
        }
    }

    private object NoHooks : SyncHooks {
        override suspend fun journalReady(): Boolean = true

        override suspend fun journalView(docIds: Collection<String>): Map<String, JournalDocView> = emptyMap()

        override suspend fun metadataArrived() = Unit

        override suspend fun documentsChanged() = Unit

        override suspend fun trashRetentionDays(): Int = 30

        override suspend fun trashTimes(): Map<String, Long?> = emptyMap()

        override suspend fun <T> serialized(block: suspend (SyncJournal) -> T): T =
            block(
                object : SyncJournal {
                    override suspend fun isDeleted(docId: String): Boolean = false

                    override suspend fun isTrashed(docId: String): Boolean = false

                    override suspend fun isCurrent(docId: String): Boolean = true

                    override suspend fun successorOf(docId: String): String = docId
                },
            )
    }

    private class Harness(
        val rig: IndexRig,
        private val remoteRoot: Path,
        private val stagingRoot: Path,
    ) {
        val transport = ScriptedTransport(DirectoryRemoteStore(remoteRoot))
        val clock = MutableClock(1_000_000L)
        private val engine =
            SyncEngine(rig.fs, rig.db, rig.deviceDb, DirectoryStaging(stagingRoot), "install-test", NoHooks, clock)

        fun service(
            checkIntervalMillis: Long = NEVER,
            soonDelayMillis: Long = SOON_DELAY,
        ) = SyncService(
            transport = transport,
            engine = engine,
            kv = rig.deviceDb.keyValueDao(),
            clock = clock,
            checkIntervalMillis = checkIntervalMillis,
            soonDelayMillis = soonDelayMillis,
        )

        fun close() {
            rig.close()
            remoteRoot.toFile().deleteRecursively()
            stagingRoot.toFile().deleteRecursively()
        }

        companion object {
            fun create() =
                Harness(
                    IndexRig.create(),
                    Files.createTempDirectory("npn-sync-service-remote"),
                    Files.createTempDirectory("npn-sync-service-staging"),
                )
        }
    }

    private fun withHarness(block: suspend TestScope.(Harness) -> Unit) =
        runTest {
            val harness = Harness.create()
            try {
                block(harness)
            } finally {
                harness.close()
            }
        }

    private suspend fun SyncService.current(): SyncStatus = status.first()

    /** A service whose library already names the test remote, with [enabled] and a 5-minute interval. */
    private suspend fun Harness.configured(
        enabled: Boolean = false,
        checkIntervalMillis: Long = NEVER,
    ): SyncService =
        service(checkIntervalMillis).also { service ->
            service.configure { CloudSyncConfig(enabled = enabled, remote = "test:library", intervalMinutes = 5) }
        }

    @Test
    fun aMissingRemoteOrTransportNeverReachesTheEngine() =
        withHarness { h ->
            val service = h.service()

            service.syncNow() shouldBe SyncOutcome.NotConfigured
            service.configure { it.copy(remote = "   ") }
            service.syncNow() shouldBe SyncOutcome.NotConfigured
            h.transport.availabilityCalls shouldBe 0

            service.configure { it.copy(remote = "test:library") }
            val absent = TransportAvailability.NotInstalled("/nowhere/rclone")
            h.transport.availability = absent
            service.syncNow() shouldBe SyncOutcome.Unavailable(absent)
            h.transport.availabilityCalls shouldBe 1

            h.transport.opens shouldBe 0
            h.transport.listCalls.value shouldBe 0
            service.current().running.shouldBeFalse()
            service.current().lastRun.shouldBeNull()
        }

    @Test
    fun theSettingsAreTheLibrarysOwnAndNormalised() =
        withHarness { h ->
            val service = h.service()
            service.config.first() shouldBe CloudSyncConfig.DISABLED

            service.configure { CloudSyncConfig(enabled = true, remote = "  drive-1:Lib  ", intervalMinutes = 1) }
            service.config.first() shouldBe
                CloudSyncConfig(
                    enabled = true,
                    remote = "drive-1:Lib",
                    intervalMinutes = CloudSyncConfig.MIN_INTERVAL_MINUTES,
                )

            // Kept in the library's device.db: a relaunch (a new service over it) reads them back.
            h
                .service()
                .config
                .first()
                .remote shouldBe "drive-1:Lib"

            // Clearing the remote turns automatic sync off with it: nothing would be left to sync to.
            service.configure { it.copy(remote = " ") }
            service.config.first() shouldBe
                CloudSyncConfig(enabled = false, remote = null, intervalMinutes = CloudSyncConfig.MIN_INTERVAL_MINUTES)
        }

    @Test
    fun anotherRemoteForgetsTheLastPassOfTheOldOne() =
        withHarness { h ->
            val service = h.configured()
            service.syncNow().shouldBeInstanceOf<SyncOutcome.Completed>()
            service.current().lastRun.shouldNotBeNull()

            service.configure { it.copy(intervalMinutes = 30) }
            service.current().lastRun.shouldNotBeNull()

            service.configure { it.copy(remote = "test:other") }
            service.current().lastRun.shouldBeNull()
            h.rig.deviceDb
                .keyValueDao()
                .get(SyncManifest.KEY_LAST_RUN)
                .shouldBeNull()
        }

    @Test
    fun aSecondPassWhileOneRunsIsRefused() =
        withHarness { h ->
            val service = h.configured()
            val gate = CompletableDeferred<Unit>()
            h.transport.listGate = gate

            val first = async { service.syncNow() }
            service.status
                .first { it.running }
                .running
                .shouldBeTrue()

            service.syncNow() shouldBe SyncOutcome.AlreadyRunning
            service.current().running.shouldBeTrue()

            gate.complete(Unit)
            first.await().shouldBeInstanceOf<SyncOutcome.Completed>()
            service.current().running.shouldBeFalse()
            h.transport.opens shouldBe 1
        }

    @Test
    fun aFailedListingIsReportedAndPersistedForTheNextService() =
        withHarness { h ->
            val service = h.configured()
            h.transport.listFailure = IOException("listing exploded")

            service.syncNow() shouldBe SyncOutcome.Failed(SyncFailure.UNKNOWN, "listing exploded")
            val status = service.current()
            status.running.shouldBeFalse()
            status.progress.shouldBeNull()
            val lastRun = status.lastRun.shouldNotBeNull()
            lastRun.failure shouldBe SyncFailure.UNKNOWN
            lastRun.failureDetail shouldBe "listing exploded"

            val stored =
                h.rig.deviceDb
                    .keyValueDao()
                    .get(SyncManifest.KEY_LAST_RUN)
                    .shouldNotBeNull()
            val persisted = lenient.decodeFromString(SyncRunSummary.serializer(), stored)
            persisted.failure shouldBe SyncFailure.UNKNOWN
            persisted.ok.shouldBeFalse()

            // A fresh service (a relaunch) shows the stored summary, and the failed pass counts as
            // the last run: nothing is due yet.
            val relaunched = h.service()
            relaunched.configure { it.copy(enabled = true) }
            relaunched.syncScheduled() shouldBe SyncOutcome.NotConfigured
            relaunched
                .current()
                .lastRun
                .shouldNotBeNull()
                .failureDetail shouldBe "listing exploded"
            h.transport.listCalls.value shouldBe 1
        }

    @Test
    fun aFailureIsNamedByTheTransportsReading() =
        withHarness { h ->
            val service = h.configured()
            h.transport.listFailure = IOException("couldn't fetch token")
            h.transport.reason = SyncFailure.SIGN_IN_EXPIRED

            service.syncNow() shouldBe SyncOutcome.Failed(SyncFailure.SIGN_IN_EXPIRED, "couldn't fetch token")
        }

    @Test
    fun theScheduleRunsOnlyWhenEnabledAndDue() =
        withHarness { h ->
            val service = h.configured(enabled = false)
            service.syncScheduled() shouldBe SyncOutcome.NotConfigured
            h.transport.listCalls.value shouldBe 0

            service.configure { it.copy(enabled = true, intervalMinutes = 5) }
            service.syncScheduled().shouldBeInstanceOf<SyncOutcome.Completed>()
            h.transport.listCalls.value shouldBe 1

            h.clock.advance(5 * MILLIS_PER_MINUTE - 1)
            service.syncScheduled() shouldBe SyncOutcome.NotConfigured
            h.transport.listCalls.value shouldBe 1

            h.clock.advance(1)
            service.syncScheduled().shouldBeInstanceOf<SyncOutcome.Completed>()
            h.transport.listCalls.value shouldBe 2
        }

    @Test
    fun aBurstOfChangesCollapsesIntoOnePassAfterTheDelay() =
        withHarness { h ->
            val service = h.configured(enabled = true)
            service.start(backgroundScope)
            runCurrent()

            repeat(3) { service.requestSoon() }
            runCurrent()
            h.transport.listCalls.value shouldBe 0

            advanceTimeBy(SOON_DELAY - 1)
            runCurrent()
            h.transport.listCalls.value shouldBe 0

            advanceTimeBy(1)
            service.status.first { it.lastRun != null }
            advanceUntilIdle()
            h.transport.listCalls.value shouldBe 1
            h.transport.opens shouldBe 1
            service.close()
        }

    @Test
    fun aChangeRightAfterAPassStillGetsItsPass() =
        withHarness { h ->
            val service = h.configured(enabled = true)
            service.start(backgroundScope)
            runCurrent()

            service.syncNow().shouldBeInstanceOf<SyncOutcome.Completed>()
            h.transport.listCalls.value shouldBe 1

            // An edit the moment a pass ended is not lost to the interval: it waits the usual delay.
            service.requestSoon()
            advanceTimeBy(SOON_DELAY)
            h.transport.listCalls.first { it >= 2 }
            service.status.first { !it.running }
            advanceUntilIdle()
            h.transport.listCalls.value shouldBe 2
            service.close()
        }

    @Test
    fun aChangeDuringAPassOwesExactlyOneFollowUp() =
        withHarness { h ->
            val service = h.configured(enabled = true)
            service.start(backgroundScope)
            runCurrent()

            val gate = CompletableDeferred<Unit>()
            h.transport.listGate = gate
            val running = async { service.syncNow() }
            service.status.first { it.running }
            // The pass has reached its listing, where the gate parks it, before the edits arrive.
            h.transport.listCalls.first { it >= 1 }

            // Two edits while the pass is in flight: kept as one, and not refused.
            service.requestSoon()
            service.requestSoon()
            runCurrent()
            h.transport.listCalls.value shouldBe 1

            h.transport.listGate = null
            gate.complete(Unit)
            running.await().shouldBeInstanceOf<SyncOutcome.Completed>()

            // The follow-up is one pass, after the usual delay.
            advanceTimeBy(SOON_DELAY * 2)
            h.transport.listCalls.first { it >= 2 }
            service.status.first { !it.running }
            advanceUntilIdle()
            h.transport.listCalls.value shouldBe 2
            h.transport.opens shouldBe 2
            service.close()
        }

    @Test
    fun aSoonPassDoesNotRunWhileAutomaticSyncIsOff() =
        withHarness { h ->
            val service = h.configured(enabled = false)
            service.start(backgroundScope)
            runCurrent()

            service.requestSoon()
            advanceTimeBy(SOON_DELAY * 2)
            advanceUntilIdle()
            h.transport.listCalls.value shouldBe 0
            h.transport.availabilityCalls shouldBe 0
            service.close()
        }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val SOON_DELAY = 1_000L

        /** `delay(Long.MAX_VALUE)` never schedules, so the due-check tick stays out of these tests. */
        const val NEVER = Long.MAX_VALUE

        val lenient = Json { ignoreUnknownKeys = true }
    }
}
