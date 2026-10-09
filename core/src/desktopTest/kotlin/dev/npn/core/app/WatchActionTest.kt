package dev.npn.core.app

import dev.npn.core.fs.FsEvent
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.OcrSidecarName
import dev.npn.core.journal.JournalPaths
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** What a change the library watcher reports calls for, decided without a session. */
class WatchActionTest {
    private val hash = "ab".repeat(32)

    private fun action(relPath: String?) = watchAction(FsEvent(relPath), DEVICE)

    @Test
    fun aPublishedJournalObjectIsIngestedOnItsOwn() {
        val path = JournalPaths.objectPath(hash)

        action(path) shouldBe WatchAction.IngestObject(path)
    }

    @Test
    fun journalChangesTheSingleObjectPathCannotTakeRefoldTheJournal() {
        action(JournalPaths.objectPath(hash, snapshot = true)) shouldBe WatchAction.ReconcileJournal
        action("${NpnPaths.JOURNAL_DIR}/other-device/0001.jsonl") shouldBe WatchAction.ReconcileJournal
        action("${NpnPaths.JOURNAL_DIR}/objects-v9-ab/$hash.jsonl") shouldBe WatchAction.ReconcileJournal
        action("${NpnPaths.JOURNAL_DIR}/something-new") shouldBe WatchAction.ReconcileJournal
        action(NpnPaths.JOURNAL_DIR) shouldBe WatchAction.ReconcileJournal
    }

    @Test
    fun aWriteInProgressOrOurOwnEchoIsIgnored() {
        action(JournalPaths.stagingPath(hash, "installation", "nonce")) shouldBe WatchAction.Ignore
        action(OcrSidecarName(hash, OcrSidecarName.writerKey(DEVICE, "mlkit")).path) shouldBe WatchAction.Ignore
    }

    @Test
    fun anotherDevicesSidecarReselectsOcr() {
        action(OcrSidecarName(hash, OcrSidecarName.writerKey("other-device", "mlkit")).path) shouldBe
            WatchAction.ReconcileOcr
    }

    @Test
    fun everythingElseRescans() {
        action("invoices/a.pdf") shouldBe WatchAction.Rescan
        action(".npn/devices/other-device/installation.json") shouldBe WatchAction.Rescan
        // The watcher lost track of what changed.
        action(null) shouldBe WatchAction.Rescan
    }

    /** A name the library cannot address is ignored, as the rescan ignores it, never thrown on. */
    @Test
    fun anUnsafePathIsIgnored() {
        action("A: Taxes/x.pdf") shouldBe WatchAction.Ignore
        action("C:x.pdf") shouldBe WatchAction.Ignore
        action("../x.pdf") shouldBe WatchAction.Ignore
        action("a\\b.pdf") shouldBe WatchAction.Ignore
        action("${NpnPaths.JOURNAL_DIR}/objects-v2-ab/x\\$hash.jsonl") shouldBe WatchAction.Ignore
    }

    @Test
    fun aBurstOfOneKindSettlesIntoOnePassAndAMixedOneIntoARescan() {
        WatchAction.ReconcileJournal.merge(WatchAction.ReconcileJournal) shouldBe WatchAction.ReconcileJournal
        WatchAction.ReconcileJournal.merge(WatchAction.ReconcileOcr) shouldBe WatchAction.Rescan
        WatchAction.ReconcileOcr.merge(WatchAction.Rescan) shouldBe WatchAction.Rescan
    }

    @Test
    fun snapshotsSettleIntoOneReconcileWhileObjectsPassStraightThrough() =
        runTest {
            val obj = WatchAction.IngestObject(JournalPaths.objectPath(hash))
            val out =
                flow {
                    repeat(5) {
                        emit(WatchAction.ReconcileJournal)
                        delay(10)
                    }
                    emit(obj)
                }.settled(quietMillis = QUIET, maxWaitMillis = 10_000, timeSource = testScheduler.timeSource).toList()

            out shouldContainExactly listOf(obj, WatchAction.ReconcileJournal)
        }

    private companion object {
        const val DEVICE = "this-device"
        const val QUIET = 100L
    }
}
