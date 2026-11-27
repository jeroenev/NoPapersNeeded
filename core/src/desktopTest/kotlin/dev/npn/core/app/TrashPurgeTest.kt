package dev.npn.core.app

import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.fs.trashEntries
import dev.npn.core.index.MutableClock
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test

/**
 * The trash auto-purge deletes an entry once the retention has passed since its document was trashed,
 * or deleted for good, and keeps an entry it cannot tie to such a document.
 */
class TrashPurgeTest {
    @Test
    fun autoPurgeCountsRetentionFromTheTrashNotFromTheFile() =
        appSessionTest { rig ->
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

            rig.session.trashPurge.purge(olderThanDays = 30)

            val left =
                rig.fs
                    .listAllUnder(NpnPaths.TRASH_DIR)
                    .filter { !it.isDirectory }
                    .map { it.name }
            left.map { NpnPaths.trashHashPrefix(it) } shouldBe
                listOf(today.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH))
            rig.session.docLifecycle.restoreDoc(today)
            rig.exists("today.pdf").shouldBeTrue()
        }

    @Test
    fun aDeviceWithNoRowForATrashedDocumentKeepsItUntilItsRetentionEnds() =
        appSessionTest { rig ->
            val now = 400 * DAY
            rig.clock.now = now
            rig.writePdf("old.pdf", PdfFixtures.embeddedTextPdf("old"))
            Files.setLastModifiedTime(rig.libRoot.resolve("old.pdf"), FileTime.fromMillis(now - 90 * DAY))
            rig.session.rescan()
            rig.session.docLifecycle.trashDoc(rig.docId("old.pdf"))

            // A device that joins now indexes only the PDFs on disk: the trashed one has no row.
            val (second, _) = rig.secondSession(backgroundScope, clock = MutableClock(now + 1))
            second.trashPurge.purge(olderThanDays = 30)

            rig.fs
                .listAllUnder(NpnPaths.TRASH_DIR)
                .filter { !it.isDirectory } shouldHaveSize 1
        }

    /**
     * A crash between a trash's file move and its journal event leaves the PDF in the trash with no
     * record of the trash. A device with no row for it (it joined since, or rebuilt its index) knows
     * nothing of a document that was never edited, as an import journals nothing: nothing says the
     * entry was trashed long ago, and the purge keeps its bytes, however old their mtime.
     */
    @Test
    fun aDeviceWithNoRowKeepsTheFileOfATrashTheJournalNeverRecorded() =
        appSessionTest { rig ->
            val now = 400 * DAY
            rig.clock.now = now
            rig.writePdf("old.pdf", PdfFixtures.embeddedTextPdf("stranded"))
            Files.setLastModifiedTime(rig.libRoot.resolve("old.pdf"), FileTime.fromMillis(now - 90 * DAY))
            rig.session.rescan()
            val id = rig.docId("old.pdf")
            // As an older build's entry would name it: aged, yet nothing here records the trash.
            val entry = NpnPaths.trashPath("old.pdf", id, now - 90 * DAY)
            rig.fs.move("old.pdf", entry)

            val (second, _) = rig.secondSession(backgroundScope, clock = MutableClock(now + 1))
            second.rescan()
            second.trashPurge.purge(olderThanDays = 30)

            rig.fs.sha256(entry) shouldBe id
        }

    /**
     * A sync tool delivers another device's trash move of a document this device never knew before
     * that trash's journal object: an entry with an old mtime that neither a row nor the journal ties
     * to a trashed document. The purge keeps it.
     */
    @Test
    fun aTrashEntryThatArrivedBeforeItsTrashIsKept() =
        appSessionTest { rig ->
            val now = 400 * DAY
            rig.clock.now = now
            val bytes = PdfFixtures.embeddedTextPdf("trashed on another device")
            val entry = NpnPaths.trashPath("elsewhere.pdf", sha256Bytes(bytes), now - 90 * DAY)
            rig.writePdf(entry, bytes)
            Files.setLastModifiedTime(rig.libRoot.resolve(entry), FileTime.fromMillis(now - 90 * DAY))
            rig.session.rescan()

            rig.session.trashPurge.purge(olderThanDays = 30)

            rig.exists(entry).shouldBeTrue()
        }

    /**
     * A delete for good whose file the provider refused to remove leaves it in the trash, its document
     * deleted. The purge counts the retention from the delete, and then removes the file.
     */
    @Test
    fun aFileADeleteForGoodLeftGoesWhenItsRetentionEnds() =
        runTest {
            val faults = FileSystemFaults().apply { refuseDeletesUnder = "${NpnPaths.TRASH_DIR}/" }
            AppSessionRig.create(backgroundScope, files = faults::over).use { rig ->
                val now = 400 * DAY
                rig.clock.now = now - 10 * DAY
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("deleted for good, its file left"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                rig.session.docLifecycle.trashDoc(id)
                rig.clock.now = now
                rig.session.docLifecycle.deleteDocPermanently(id) shouldBe PermanentDeleteOutcome.FILE_LEFT
                faults.refuseDeletesUnder = null

                rig.clock.now = now + 25 * DAY
                rig.session.trashPurge.purge(olderThanDays = 30)
                rig.fs.trashEntries() shouldHaveSize 1

                rig.clock.now = now + 31 * DAY
                rig.session.trashPurge.purge(olderThanDays = 30)
                rig.fs.trashEntries().shouldBeEmpty()
            }
        }

    /**
     * A document trashed a month ago is restored and trashed again after the purge listed the trash
     * and before it deleted anything: the new trash takes the entry's name back, with a trash time of
     * now, and the purge keeps it.
     */
    @Test
    fun anEntryTrashedAgainWhileThePurgeRunsIsKept() =
        runTest {
            var afterListing: (suspend () -> Unit)? = null
            AppSessionRig
                .create(backgroundScope, files = { fs ->
                    object : LibraryFileSystem by fs {
                        override suspend fun listAllUnder(relPath: String): List<FileMeta> {
                            val listed = fs.listAllUnder(relPath)
                            if (relPath == NpnPaths.TRASH_DIR) afterListing?.also { afterListing = null }?.invoke()
                            return listed
                        }
                    }
                })
                .use { rig ->
                    val now = 400 * DAY
                    rig.clock.now = now
                    rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("trashed, restored and trashed again"))
                    rig.session.rescan()
                    val id = rig.docId("a.pdf")
                    rig.session.docLifecycle.trashDoc(id)
                    val entry =
                        rig.fs
                            .trashEntries()
                            .single()
                            .relPath
                    rig.clock.now = now + 31 * DAY
                    afterListing = {
                        rig.session.docLifecycle.restoreDoc(id)
                        rig.session.docLifecycle.trashDoc(id)
                    }

                    rig.session.trashPurge.purge(olderThanDays = 30)

                    afterListing shouldBe null
                    // The re-trash is a new episode with its own entry name; the purge left it holding
                    // the document's bytes.
                    val kept = rig.fs.trashEntries().map { it.relPath }
                    kept shouldHaveSize 1
                    kept.single() shouldNotBe entry
                    rig.fs.sha256(kept.single()) shouldBe id
                }
        }

    /**
     * The keep store's copies go by the trash's rule: a trashed document's once its trash is past the
     * retention, a restored one's once its restore is, while its file holds its bytes at its path.
     */
    @Test
    fun theKeepStoresCopiesGoByTheTrashsRetention() =
        appSessionTest { rig ->
            val now = 400 * DAY
            rig.clock.now = now - 60 * DAY
            rig.writePdf("trashed.pdf", PdfFixtures.embeddedTextPdf("trashed"))
            rig.writePdf("restored.pdf", PdfFixtures.embeddedTextPdf("restored"))
            rig.session.rescan()
            val trashed = rig.docId("trashed.pdf")
            val restored = rig.docId("restored.pdf")
            rig.session.keepGate
                .refresh()
                .shouldBeTrue()
            rig.session.docLifecycle.trashDoc(trashed)
            rig.session.docLifecycle.trashDoc(restored)
            rig.session.docLifecycle.restoreDoc(restored)
            rig.session.rescan()

            suspend fun kept() =
                rig.fs.listAllUnder(NpnPaths.KEEP_DIR).mapNotNull { NpnPaths.Keep.parse(it.relPath)?.first }
            kept().toSet() shouldBe setOf(trashed, restored)

            rig.clock.now = now - 45 * DAY
            rig.session.trashPurge.purge(olderThanDays = 30)
            kept() shouldHaveSize 2

            rig.clock.now = now
            rig.session.trashPurge.purge(olderThanDays = 30)
            kept().shouldBeEmpty()
            rig.exists("restored.pdf").shouldBeTrue()
        }

    private companion object {
        const val DAY = 24L * 60L * 60L * 1000L
    }
}
