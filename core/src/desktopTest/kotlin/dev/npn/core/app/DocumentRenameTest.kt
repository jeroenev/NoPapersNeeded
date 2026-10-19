package dev.npn.core.app

import dev.npn.core.index.requireByDocId
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import kotlin.test.Test

/** An explicit rename or move keeps the document's identity and refuses a name it cannot take. */
class DocumentRenameTest {
    @Test
    fun renameFileUpdatesPathSameDocId() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.fs.sha256("a.pdf")

            rig.session.docLifecycle.renameFile(id, "renamed.pdf") shouldBe RelocationOutcome.MOVED
            rig.exists("renamed.pdf").shouldBeTrue()
            rig.exists("a.pdf").shouldBeFalse()
            rig.db
                .documentDao()
                .requireByDocId(id)
                .relPath shouldBe "renamed.pdf"

            // Survives rescan with the same docId.
            rig.session.rescan()
            rig.db
                .documentDao()
                .requireByDocId(id)
                .relPath shouldBe "renamed.pdf"
        }

    /**
     * A name the user types is an answer, never an exception: a taken name says so, a name without
     * its `.pdf` gets one, and a path the library could not index is refused, with nothing written.
     */
    @Test
    fun renameOntoATakenOrUnusableNameIsATypedOutcome() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("beta"))
            rig.session.rescan()
            val id = rig.fs.sha256("a.pdf")
            val lifecycle = rig.session.docLifecycle

            lifecycle.renameFile(id, "b.pdf") shouldBe RelocationOutcome.TAKEN
            lifecycle.renameFile(id, "a (conflicted copy)") shouldBe RelocationOutcome.INVALID_NAME
            lifecycle.moveFile(id, "../outside") shouldBe RelocationOutcome.INVALID_NAME
            rig.db
                .documentDao()
                .requireByDocId(id)
                .relPath shouldBe "a.pdf"
            rig.fs.sha256("b.pdf") shouldNotBe id

            lifecycle.renameFile(id, "c") shouldBe RelocationOutcome.MOVED
            rig.exists("c.pdf").shouldBeTrue()
            rig.db
                .documentDao()
                .requireByDocId(id)
                .relPath shouldBe "c.pdf"
        }

    /**
     * A name that is the document's own file under another name (a hard link here; a case-only
     * rename where the filesystem ignores case) is not taken: the rename leaves it alone and journals
     * nothing, as the fold could not tell that move from one already made.
     */
    @Test
    fun renameOntoTheDocumentsOwnFileUnderAnotherNameIsUnchanged() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.fs.sha256("a.pdf")
            Files.createLink(rig.libRoot.resolve("b.pdf"), rig.libRoot.resolve("a.pdf"))
            val objects = rig.journalObjectCount()

            rig.session.docLifecycle.renameFile(id, "b.pdf") shouldBe RelocationOutcome.UNCHANGED

            rig.journalObjectCount() shouldBe objects
            rig.db
                .documentDao()
                .requireByDocId(id)
                .relPath shouldBe "a.pdf"
        }

    @Test
    fun moveFileUpdatesPathSameDocId() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.fs.sha256("a.pdf")

            rig.session.docLifecycle.moveFile(id, "archive") shouldBe RelocationOutcome.MOVED
            rig.exists("archive/a.pdf").shouldBeTrue()
            rig.db
                .documentDao()
                .requireByDocId(id)
                .relPath shouldBe "archive/a.pdf"

            rig.session.rescan()
            rig.db
                .documentDao()
                .requireByDocId(id)
                .relPath shouldBe "archive/a.pdf"
        }
}
