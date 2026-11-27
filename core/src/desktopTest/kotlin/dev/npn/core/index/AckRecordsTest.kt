package dev.npn.core.index

import dev.npn.core.app.CausalPurge
import dev.npn.core.app.KeepGate
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.PendingOp
import dev.npn.core.util.NpnJson
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * A device's acknowledgment records: what it vouches for, when it writes one, and how far another's
 * chain is applied.
 */
class AckRecordsTest {
    private val day = AckRecords.MIN_INTERVAL_MILLIS

    /** The first record vouches for the noted objects that were published, and forgets them all. */
    @Test
    fun aRecordVouchesForWhatThisDevicePublishedAndForgetsWhatNeverWas() =
        indexTest { rig ->
            val authored = AuthoredObjects(rig.deviceDb.keyValueDao())
            val published = rig.writer().commit(listOf(PendingOp.tagAdd(DOC, "a"))).contentHash
            authored.add(published)
            authored.add("f".repeat(64))

            val record = acks(rig, authored).publish().shouldNotBeNull()

            record.n shouldBe 1L
            record.prev.shouldBeNull()
            record.authored shouldBe listOf(published)
            authored.pending().shouldBeEmpty()
            rig.fs.exists(AckRecords.path(SELF, 1)) shouldBe true
        }

    /**
     * After the first, a record comes a day after the last when something changed, and a week after it
     * whatever changed; each links to the one before by its hash.
     */
    @Test
    fun recordsComeAtMostDailyAndAtLeastWeeklyEachLinkedToTheLast() =
        indexTest { rig ->
            val authored = AuthoredObjects(rig.deviceDb.keyValueDao())
            val acks = acks(rig, authored)
            acks.publish().shouldNotBeNull()
            authored.add(rig.writer().commit(listOf(PendingOp.tagAdd(DOC, "b"))).contentHash)

            acks.publish().shouldBeNull()
            rig.clock.now += day
            val second = acks.publish().shouldNotBeNull()
            second.n shouldBe 2L
            second.prev shouldBe sha256Bytes(rig.fs.readBytes(AckRecords.path(SELF, 1)))

            rig.clock.now += day
            acks.publish().shouldBeNull()
            rig.clock.now += 6 * day
            acks.publish().shouldNotBeNull().n shouldBe 3L
        }

    /**
     * A record of its own that cannot be read just now holds the next one back: written under the
     * number after it, never under its name.
     */
    @Test
    fun anUnreadableRecordOfItsOwnHoldsTheNextBack() =
        indexTest { rig ->
            rig.fs.writeBytesOnce(AckRecords.path(SELF, 1), "not a record".encodeToByteArray())

            acks(rig, AuthoredObjects(rig.deviceDb.keyValueDao())).publish().shouldBeNull()
            rig.fs.readBytes(AckRecords.path(SELF, 1)).decodeToString() shouldBe "not a record"
        }

    /**
     * Another device's chain counts as far as its records link and every object they vouch for is
     * applied here; a record that does not link to the one before ends it.
     */
    @Test
    fun anotherDeviceIsCoveredAsFarAsItsLinkedChainIsApplied() =
        indexTest { rig ->
            val applied = rig.writer().commit(listOf(PendingOp.tagAdd(DOC, "c"))).contentHash
            rig.rescan()
            val notYet = rig.writer().commit(listOf(PendingOp.tagAdd(DOC, "d"))).contentHash
            val first = record(rig, OTHER, 1, prev = null, authored = listOf(applied), at = 5_000)
            record(rig, OTHER, 2, prev = first, authored = listOf(notYet), at = 6_000)

            val acks = acks(rig, AuthoredObjects(rig.deviceDb.keyValueDao()))
            acks.devices().single().covered shouldBe Coverage(1, 5_000)

            rig.rescan()
            acks.devices().single().covered shouldBe Coverage(2, 6_000)

            // A third that names another record before it ends the chain there.
            record(rig, OTHER, 3, prev = "0".repeat(64), authored = emptyList(), at = 7_000)
            acks.devices().single().covered shouldBe Coverage(2, 6_000)
            acks.devices().single().lastHeardMillis shouldBe 7_000
        }

    /**
     * The purge counts a trash only once every active device acknowledged past its retention; a
     * retired device, or one silent for 90 days, holds nothing back.
     */
    @Test
    fun aTrashCountsOnceEveryActiveDeviceAcknowledgedPastItsRetention() =
        indexTest { rig ->
            val settings = HashMap<String, String>()
            val acks = acks(rig, AuthoredObjects(rig.deviceDb.keyValueDao()))
            val causal = CausalPurge(acks, settings::get, rig.clock)
            val retention = 10 * day
            val times = mapOf("trashed" to 1_000L, "shown" to null)
            rig.clock.now = 1_000L + 20 * day

            // No device writes records: the retention alone decides.
            causal.acknowledgedUntil().filter(times, retention) shouldBe times

            record(rig, OTHER, 1, prev = null, authored = emptyList(), at = 1_000L + 5 * day)
            causal.acknowledgedUntil().filter(times, retention) shouldBe mapOf("trashed" to null, "shown" to null)

            record(
                rig,
                OTHER,
                2,
                prev = sha256Bytes(rig.fs.readBytes(AckRecords.path(OTHER, 1))),
                at =
                    1_000L + 11 * day,
            )
            causal.acknowledgedUntil().filter(times, retention) shouldBe times

            // A second device that has acknowledged nothing yet holds every trash back, until retired.
            record(rig, THIRD, 2, prev = "0".repeat(64), at = 1_000L + 19 * day)
            causal.acknowledgedUntil().filter(times, retention) shouldBe mapOf("trashed" to null, "shown" to null)
            settings[CausalPurge.retiredKey(THIRD)] = "1"
            causal.acknowledgedUntil().filter(times, retention) shouldBe times

            // Or once silent for longer than 90 days.
            settings.clear()
            rig.clock.now = 1_000L + 19 * day + CausalPurge.SILENT_AFTER_MILLIS + 1
            causal.acknowledgedUntil().filter(times, retention) shouldBe times
        }

    /**
     * The keep store is written only once every device that may open the library reads it: one known
     * by its installation marker alone (a build from before records), or whose latest record does not
     * list it, holds it shut until it is updated, retired, or silent for 90 days.
     */
    @Test
    fun theKeepStoreWaitsForEveryDeviceToReadIt() =
        indexTest { rig ->
            val settings = HashMap<String, String>()
            val gate = KeepGate(acks(rig, AuthoredObjects(rig.deviceDb.keyValueDao())), settings::get, rig.clock)
            rig.clock.now = 1_000L
            gate.refresh().shouldBeTrue()

            rig.fs.writeBytesOnce("${NpnPaths.DEVICES_DIR}/$SELF/test-installation.json", "{}".encodeToByteArray())
            rig.fs.writeBytesOnce("${NpnPaths.DEVICES_DIR}/$OTHER/other.json", "{}".encodeToByteArray())
            gate.refresh().shouldBeFalse()
            gate.waiting.value.waiting shouldBe setOf(OTHER)

            val first = record(rig, OTHER, 1, prev = null, at = 1_000L, caps = listOf(AckRecords.ACKS_V1))
            gate.refresh().shouldBeFalse()
            record(rig, OTHER, 2, prev = first, at = 2_000L, caps = AckRecords.CAPS)
            gate.refresh().shouldBeTrue()

            rig.fs.writeBytesOnce("${NpnPaths.DEVICES_DIR}/$THIRD/third.json", "{}".encodeToByteArray())
            gate.refresh().shouldBeFalse()
            settings[CausalPurge.retiredKey(THIRD)] = "1"
            gate.refresh().shouldBeTrue()

            // A device whose records say it reads no keep store holds nothing back once silent.
            record(rig, FOURTH, 1, prev = null, at = 2_000L, caps = listOf(AckRecords.ACKS_V1))
            gate.refresh().shouldBeFalse()
            rig.clock.now = 2_001L + CausalPurge.SILENT_AFTER_MILLIS
            gate.refresh().shouldBeTrue()
        }

    private fun acks(
        rig: IndexRig,
        authored: AuthoredObjects,
    ) = AckRecords(rig.fs, rig.db, authored, rig.clock, SELF, "test-installation")

    /** Write [device]'s record [n] as that device would; returns the hash of its bytes. */
    private suspend fun record(
        rig: IndexRig,
        device: String,
        n: Long,
        prev: String?,
        authored: List<String> = emptyList(),
        at: Long,
        caps: List<String> = emptyList(),
    ): String {
        val bytes =
            NpnJson.Persisted
                .encodeToString(
                    AckRecord.serializer(),
                    AckRecord(device = device, n = n, prev = prev, atMillis = at, authored = authored, caps = caps),
                ).encodeToByteArray()
        rig.fs.writeBytesOnce(AckRecords.path(device, n), bytes)
        return sha256Bytes(bytes)
    }

    private companion object {
        const val SELF = "11111111-1111-4111-8111-111111111111"
        const val OTHER = "22222222-2222-4222-8222-222222222222"
        const val THIRD = "33333333-3333-4333-8333-333333333333"
        const val FOURTH = "44444444-4444-4444-8444-444444444444"
        val DOC = "a".repeat(64)
    }
}
