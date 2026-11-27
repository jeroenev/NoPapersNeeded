package dev.npn.core.app

import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.FileStat
import dev.npn.core.journal.PendingOp
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * What a crash cut short of this device's operations is settled before the next rescan, by what the
 * journal says ([dev.npn.core.index.PendingIntents]).
 */
class PendingIntentsTest {
    /** A trash into the keep store whose event stands, cut short before its file left, is finished. */
    @Test
    fun aTrashWhoseEventStoodIsFinished() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("trashed, then the crash"))
            rig.session.rescan()
            val doc = rig.docId("a.pdf")
            val stat = rig.fs.stat("a.pdf").shouldNotBeNull()
            val intents = rig.session.intents
            val id = intents.trashKept(doc, "a.pdf", stat, rig.clock.now, "npn-recovery-1.pdf")
            rig.session.coordinator.commitAndApply(
                listOf(PendingOp.docTrash(doc, "a.pdf", FileStat(stat.size, stat.mtimeMillis))),
            )
            intents.release(id)

            rig.session.rescan()

            rig.exists("a.pdf").shouldBeFalse()
            rig.fs
                .listAllUnder(NpnPaths.KEEP_DIR)
                .mapNotNull { NpnPaths.Keep.parse(it.relPath)?.first } shouldBe listOf(doc)
            intents.all().shouldBeEmpty()
        }

    /** One whose event never stood leaves the file where it is. */
    @Test
    fun aTrashWhoseEventNeverStoodLeavesTheFile() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("never trashed"))
            rig.session.rescan()
            val doc = rig.docId("a.pdf")
            val intents = rig.session.intents
            val stat = rig.fs.stat("a.pdf").shouldNotBeNull()
            intents.release(intents.trashKept(doc, "a.pdf", stat, rig.clock.now, "npn-recovery-1.pdf"))

            rig.session.rescan()

            rig.exists("a.pdf").shouldBeTrue()
            intents.all().shouldBeEmpty()
        }

    /** A bake cut short with its original aside and no relink gets the original back at its path. */
    @Test
    fun aBakeWithoutItsRelinkGetsItsOriginalBack() =
        appSessionTest { rig ->
            rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("baked, then the crash"))
            rig.session.rescan()
            val doc = rig.docId("b.pdf")
            val intents = rig.session.intents
            val id = intents.bakeAside(doc, "b.pdf", "npn-recovery-2.pdf", "f".repeat(64))
            rig.fs.move("b.pdf", "npn-recovery-2.pdf")
            intents.release(id)

            rig.session.rescan()

            rig.docId("b.pdf") shouldBe doc
            rig.exists("npn-recovery-2.pdf").shouldBeFalse()
            intents.all().shouldBeEmpty()
        }
}
