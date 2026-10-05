package dev.npn.core.app

import dev.npn.core.index.MutableClock
import dev.npn.core.model.FieldKeys
import dev.npn.core.pdf.PdfBoxEngine
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.util.NpnLog
import dev.npn.core.xmp.PdfBoxXmpIO
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test

/**
 * Regression tests for HLC clock seeding ([dev.npn.core.journal.ContentAddressedJournalWriter.observeHlc]).
 *
 * The clock starts at zero on every session open and used to be driven by the raw wall clock
 * only. Whenever the wall clock was behind HLCs already in the journal (restart after a clock
 * correction, or a peer device with a faster clock), every LWW write — prop/field set AND
 * clear — silently lost to the stale on-disk winner: edits "didn't stick", deletes "came back",
 * re-added values "disappeared again". The coordinator now merges the fold's max HLC into the
 * writer's clock after every refold, so new events always order after everything already seen.
 */
class HlcSeedingTest {
    @Test
    fun editsWinOverPeerDeviceWithFasterClock() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            var db2 = rig.db
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")

                // Device 2's wall clock (50_000) runs far ahead of device 1's (~1_000).
                val (session2, secondDb) = rig.secondSession(backgroundScope)
                db2 = secondDb
                session2.rescan()
                session2.meta.setField(listOf(id), FieldKeys.CORRESPONDENT, "from-b")
                session2.meta.setField(id, "amount", "1")

                // Device 1 folds device 2's segments (seeding its clock), then edits: its writes
                // are causally later and must win LWW despite the slower wall clock.
                rig.session.rescan()
                rig.session.meta.setField(listOf(id), FieldKeys.CORRESPONDENT, "from-a")
                rig.session.meta.setField(id, "amount", null) // delete must stick
                rig.session.meta.setField(id, "amount", "2") // re-add must stick

                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .correspondent shouldBe "from-a"
                rig.session.documents
                    .observeDoc(id)
                    .first()!!
                    .doc.fields shouldBe mapOf(FieldKeys.CORRESPONDENT to "from-a", "amount" to "2")

                // Device 2 syncs back and converges to device 1's newer edits.
                session2.rescan()
                db2.documentDao().getByDocId(id)!!.correspondent shouldBe "from-a"
                session2.close()
            } finally {
                if (db2 !== rig.db) db2.close()
                rig.close()
            }
        }

    @Test
    fun reopenedSessionWithRegressedClockStillWinsLww() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                rig.session.meta.setField(id, FieldKeys.TITLE, "first")
                rig.session.meta.setField(id, "amount", "42")
                rig.session.close()

                // Reopen over the same library + cache with the wall clock regressed far behind
                // the HLCs already in the journal (clock correction between sessions).
                val deps =
                    SessionDeps(
                        fs = rig.fs,
                        db = rig.db,
                        deviceDb = rig.deviceDb,
                        pdfEngine = PdfBoxEngine(),
                        ocrEngine = NoopOcrEngine(),
                        xmpIO = PdfBoxXmpIO(),
                        thumbnailStore = FakeThumbStore(File(rig.dbDir, "thumbs-reopen")),
                        renderParallelism = 1,
                        scope = backgroundScope,
                        settings = SessionSettings(ocrLanguage = { "eng" }, trashRetentionDays = { 30 }),
                        log = NpnLog.None,
                        clock = MutableClock(1L, autoTick = true),
                    )
                val session2 = LibrarySessionImpl.openSession(deps)

                session2.meta.setField(id, FieldKeys.TITLE, "second")
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .title shouldBe "second"

                // Deleting the field written by the previous session must stick...
                session2.meta.setField(id, "amount", null)
                session2.documents
                    .observeDoc(id)
                    .first()!!
                    .doc.fields shouldBe mapOf(FieldKeys.TITLE to "second")
                // ...and so must re-adding it.
                session2.meta.setField(id, "amount", "43")
                session2.documents
                    .observeDoc(id)
                    .first()!!
                    .doc.fields shouldBe mapOf(FieldKeys.TITLE to "second", "amount" to "43")

                session2.close()
            } finally {
                rig.close()
            }
        }
}
