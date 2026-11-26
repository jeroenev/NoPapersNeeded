package dev.npn.core.app

import dev.npn.core.db.TempIndexDb
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalEventVersions
import dev.npn.core.journal.JournalOp
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Which history rows this installation wrote. Its actors are recorded from the first write this
 * build made on; what came before that is nobody's to claim.
 */
class LocalJournalActorsTest {
    private val temp = TempIndexDb("npn-actors-test")

    @AfterTest
    fun teardown() = temp.close()

    @Test
    fun aChangeFromBeforeTheFirstRecordedWriteHasNoKnownOrigin() =
        runTest {
            val actors = LocalJournalActors(temp.deviceDb.keyValueDao())
            actors.record(listOf(event("mine", Hlc(1_000, 0, "mine"))))

            actors.origins(
                listOf(
                    // This device's own edit from before the upgrade, or another device's: unknown.
                    "old" to Hlc(500, 0, "old"),
                    "mine" to Hlc(1_000, 0, "mine"),
                    "theirs" to Hlc(2_000, 0, "theirs"),
                ),
            ) shouldContainExactly
                listOf(HistoryOrigin.UNKNOWN, HistoryOrigin.THIS_DEVICE, HistoryOrigin.ANOTHER_DEVICE)
        }

    @Test
    fun nothingRecordedYetClaimsNoOrigin() =
        runTest {
            val actors = LocalJournalActors(temp.deviceDb.keyValueDao())

            actors.origins(listOf("theirs" to Hlc(2_000, 0, "theirs"))) shouldContainExactly
                listOf(HistoryOrigin.UNKNOWN)
        }

    /** The seeded field definitions sit beneath every edit; they do not date this device's first write. */
    @Test
    fun aSeedingWriteDoesNotSetTheStart() =
        runTest {
            val actors = LocalJournalActors(temp.deviceDb.keyValueDao())
            actors.record(listOf(event("seed", Hlc(0, 0, "seed"))))
            actors.record(listOf(event("mine", Hlc(1_000, 0, "mine"))))

            actors.origins(listOf("old" to Hlc(500, 0, "old"))) shouldContainExactly listOf(HistoryOrigin.UNKNOWN)
        }

    /**
     * One row per transaction would grow without end: past [keep] actors the oldest go, and what
     * they wrote reads as of unknown origin, never as another device's.
     */
    @Test
    fun theOldestActorsArePrunedAndTheirChangesReadAsUnknown() =
        runTest {
            val actors = LocalJournalActors(temp.deviceDb.keyValueDao(), keep = 2, pruneEvery = 1)
            for (i in 1..4) actors.record(listOf(event("a$i", Hlc(i * 1_000L, 0, "a$i"))))

            actors.origins(
                listOf(
                    "a1" to Hlc(1_000, 0, "a1"),
                    "a2" to Hlc(2_000, 0, "a2"),
                    "a3" to Hlc(3_000, 0, "a3"),
                    "a4" to Hlc(4_000, 0, "a4"),
                    "theirs" to Hlc(3_500, 0, "theirs"),
                ),
            ) shouldContainExactly
                listOf(
                    HistoryOrigin.UNKNOWN,
                    HistoryOrigin.UNKNOWN,
                    HistoryOrigin.THIS_DEVICE,
                    HistoryOrigin.THIS_DEVICE,
                    HistoryOrigin.ANOTHER_DEVICE,
                )
        }

    private fun event(
        actor: String,
        hlc: Hlc,
    ) = JournalEvent(JournalEventVersions.CANONICAL, 1, hlc, actor, "a".repeat(64), JournalOp.TagAdd("t"))
}
