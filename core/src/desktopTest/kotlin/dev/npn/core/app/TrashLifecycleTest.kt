package dev.npn.core.app

import dev.npn.core.fs.NpnPaths
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertFailsWith

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

    @Test
    fun trashingWhileWritesAreBlockedLeavesThePdfWhereItWas() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                blockMetadataWrites(rig)

                assertFailsWith<IllegalStateException> { rig.session.docLifecycle.trashDoc(id) }

                rig.exists("a.pdf").shouldBeTrue()
                rig.db
                    .documentDao()
                    .getByDocId(id)
                    ?.trashed shouldBe false
            } finally {
                rig.close()
            }
        }

    @Test
    fun deletingWhileWritesAreBlockedKeepsTheFile() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                blockMetadataWrites(rig)

                assertFailsWith<IllegalStateException> { rig.session.docLifecycle.deleteDocPermanently(id) }

                rig.exists("a.pdf").shouldBeTrue()
                rig.db
                    .documentDao()
                    .getByDocId(id)
                    ?.deleted shouldBe false
            } finally {
                rig.close()
            }
        }

    /** A journal store from a newer build makes the rescan refuse, which blocks metadata writes. */
    private suspend fun blockMetadataWrites(rig: AppSessionRig) {
        Files.createDirectories(rig.libRoot.resolve("${NpnPaths.JOURNAL_DIR}/objects-v9-00"))
        assertFailsWith<IllegalStateException> { rig.session.rescan() }
    }

    @Test
    fun autoPurgeCountsRetentionFromTheTrashNotFromTheFile() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val now = 400 * DAY
                rig.clock.now = now - 60 * DAY
                rig.writePdf("today.pdf", PdfFixtures.embeddedTextPdf("today"))
                rig.writePdf("long-ago.pdf", PdfFixtures.embeddedTextPdf("long ago"))
                // Both PDFs were imported long ago; a move into trash keeps that mtime.
                for (name in listOf("today.pdf", "long-ago.pdf")) {
                    Files.setLastModifiedTime(rig.libRoot.resolve(name), FileTime.fromMillis(now - 90 * DAY))
                }
                rig.session.rescan()
                val today = rig.docId("today.pdf")
                val longAgo = rig.docId("long-ago.pdf")

                rig.session.docLifecycle.trashDoc(longAgo)
                rig.clock.now = now
                rig.session.docLifecycle.trashDoc(today)

                rig.session.purgeTrash(olderThanDays = 30)

                val left =
                    rig.fs
                        .listAllUnder(NpnPaths.TRASH_DIR)
                        .filter { !it.isDirectory }
                        .map { it.name }
                left shouldBe listOf("today__${today.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH)}.pdf")
                rig.session.docLifecycle.restoreDoc(today)
                rig.exists("today.pdf").shouldBeTrue()
            } finally {
                rig.close()
            }
        }

    private companion object {
        const val DAY = 24L * 60L * 60L * 1000L
    }
}
