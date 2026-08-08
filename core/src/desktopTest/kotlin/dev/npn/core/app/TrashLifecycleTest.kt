package dev.npn.core.app

import dev.npn.core.fs.NpnPaths
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class TrashLifecycleTest {
    @Test
    fun trashMovesFileAndSetsFlag() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.fs.sha256("a.pdf")

                rig.session.docLifecycle.trashDoc(id)

                rig.exists("a.pdf").shouldBeFalse()
                val doc = rig.db.documentDao().getByDocId(id)!!
                doc.trashed.shouldBeTrue()
                // A file now exists under .npn/trash/.
                val trash =
                    rig.fs.listAll().filter {
                        !it.isDirectory && it.relPath.startsWith("${NpnPaths.TRASH_DIR}/")
                    }
                trash.isNotEmpty().shouldBeTrue()
            } finally {
                rig.close()
            }
        }

    @Test
    fun restoreBringsFileBack() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("docs/a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.fs.sha256("docs/a.pdf")

                rig.session.docLifecycle.trashDoc(id)
                rig.exists("docs/a.pdf").shouldBeFalse()

                rig.session.docLifecycle.restoreDoc(id)
                rig.exists("docs/a.pdf").shouldBeTrue()
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .trashed
                    .shouldBeFalse()
            } finally {
                rig.close()
            }
        }

    @Test
    fun permanentDeleteRemovesFileAndStaysHiddenAfterRescan() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.fs.sha256("a.pdf")

                rig.session.docLifecycle.deleteDocPermanently(id)
                rig.exists("a.pdf").shouldBeFalse()
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .deleted
                    .shouldBeTrue()

                // After a rescan the tombstone keeps it hidden.
                rig.session.rescan()
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .deleted
                    .shouldBeTrue()
            } finally {
                rig.close()
            }
        }

    @Test
    fun renameFileUpdatesPathSameDocId() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.fs.sha256("a.pdf")

                rig.session.docLifecycle.renameFile(id, "renamed.pdf")
                rig.exists("renamed.pdf").shouldBeTrue()
                rig.exists("a.pdf").shouldBeFalse()
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .relPath shouldBe "renamed.pdf"

                // Survives rescan with the same docId.
                rig.session.rescan()
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .relPath shouldBe "renamed.pdf"
            } finally {
                rig.close()
            }
        }

    @Test
    fun moveFileUpdatesPathSameDocId() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.fs.sha256("a.pdf")

                rig.session.docLifecycle.moveFile(id, "archive")
                rig.exists("archive/a.pdf").shouldBeTrue()
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .relPath shouldBe "archive/a.pdf"

                rig.session.rescan()
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .relPath shouldBe "archive/a.pdf"
            } finally {
                rig.close()
            }
        }
}
