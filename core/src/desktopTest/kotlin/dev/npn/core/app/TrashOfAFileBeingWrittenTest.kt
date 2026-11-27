package dev.npn.core.app

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.fs.trashEntries
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Another process writing over a document's file in place while it is trashed: the trash checked the
 * file, then moved what is there by the time it moves, which is no longer the document's. That file
 * goes back; when the writer has meanwhile made a file at the path again, it goes beside it under a
 * recovery name, and never stays in the trash under the document's name, where no restore or purge
 * ever takes it.
 */
class TrashOfAFileBeingWrittenTest {
    @Test
    fun aForeignFileTheTrashTookGoesBesideItsPathWhenThatIsTakenAgain() {
        var rig: AppSessionRig? = null
        var interfere = false
        val writing = PdfFixtures.embeddedTextPdf("half written")
        val written = PdfFixtures.embeddedTextPdf("written in place")
        appSessionTest(
            files = { fs ->
                object : LibraryFileSystem by fs {
                    override suspend fun move(
                        fromRelPath: String,
                        toRelPath: String,
                    ) {
                        val now = interfere && fromRelPath == PATH && toRelPath.startsWith(NpnPaths.TRASH_DIR + "/")
                        if (now) {
                            interfere = false
                            rig?.writePdf(PATH, writing)
                        }
                        fs.move(fromRelPath, toRelPath)
                        if (now) rig?.writePdf(PATH, written)
                    }
                }
            },
        ) { session ->
            rig = session
            session.writePdf(PATH, PdfFixtures.embeddedTextPdf("alpha"))
            session.session.rescan()
            val id = session.docId(PATH)

            interfere = true
            session.session.docLifecycle.trashDoc(id)

            session.fs.trashEntries().shouldBeEmpty()
            session.fs.sha256(PATH) shouldBe sha256Bytes(written)
            val aside = session.fs.listAllIndexable().filter { NpnPaths.Temp.isRecovery(it.relPath) }
            aside shouldHaveSize 1
            session.fs.sha256(aside.single().relPath) shouldBe sha256Bytes(writing)
        }
    }

    private companion object {
        const val PATH = "a.pdf"
    }
}
