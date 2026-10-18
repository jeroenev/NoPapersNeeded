package dev.npn.core.app

import dev.npn.core.db.searchDocIds
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.libraryPdfs
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.JournalSourceRead
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.StorageV2Reader
import dev.npn.core.journal.journalObjects
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.SystemProperty
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.util.RecordingLog
import dev.npn.core.xmp.BakeRefusedException
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertIs

class BakeInPlaceTest {
    @Test
    fun aBakedDocumentKeepsItsMetadataOnTheNewHash() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val before = rig.docId("a.pdf")
            rig.session.meta.applyEdits(
                listOf(before),
                addTags = listOf("filed"),
                fields = mapOf(FieldKeys.TITLE to "Water bill", FieldKeys.CORRESPONDENT to "Aquafin"),
            )

            rig.session.content.bakeInPlace(before) shouldBe BakeOutcome.Baked

            val after = rig.docId("a.pdf")
            after shouldNotBe before
            rig.db
                .documentDao()
                .getByDocId(after)
                ?.relPath shouldBe "a.pdf"
            rig.db
                .documentFieldDao()
                .valuesOf(after) shouldBe
                mapOf(FieldKeys.TITLE to "Water bill", FieldKeys.CORRESPONDENT to "Aquafin")
            rig.db
                .tagDao()
                .tagsForDocument(after) shouldContainExactly listOf("filed")
            rig.db
                .documentDao()
                .getByDocId(before)
                ?.deleted shouldBe true
        }

    /**
     * An edit to a baked document projects it with what the relink copied onto it, as a rescan does:
     * marking it reviewed keeps its title, correspondent and tag in the index and in search.
     */
    @Test
    fun anEditToABakedDocumentKeepsWhatItWasGiven() =
        appSessionTest { rig ->
            val (before, after) = bakeFiled(rig)

            rig.session.meta.setReviewed(listOf(after), true)

            fieldsOf(rig, after) shouldBe FILED
            rig.db
                .tagDao()
                .tagsForDocument(after) shouldContainExactly listOf("filed")
            rig.db.ftsDao().searchDocIds("water") shouldContain after
            // Projected with it, the original stays superseded, as a rescan leaves it.
            val superseded = rig.db.documentDao().getByDocId(before)
            superseded?.deleted shouldBe true
            rig.session.rescan()
            rig.db.documentDao().getByDocId(before) shouldBe superseded
        }

    /** The storage-path rule after an edit to a baked document files it by what the relink copied. */
    @Test
    fun anEditToABakedDocumentFilesItByWhatItWasGiven() =
        appSessionTest(storagePath = StoragePathRule("{correspondent}/{title}.pdf", enabled = true)) { rig ->
            val filed = "Aquafin/Water bill.pdf"
            val (_, after) = bakeFiled(rig)
            rig.docId(filed) shouldBe after

            rig.session.meta.addTag(listOf(after), "x")

            rig.db
                .documentDao()
                .getByDocId(after)
                ?.relPath shouldBe filed
            rig.docId(filed) shouldBe after
        }

    /** What a baked document was given is its own: a clear clears it and a removal removes it, for good. */
    @Test
    fun whatABakedDocumentWasGivenCanBeCleared() =
        appSessionTest { rig ->
            val (_, after) = bakeFiled(rig)

            rig.session.meta.setField(after, FieldKeys.TITLE, null)
            rig.session.meta.removeTag(listOf(after), "filed")

            val cleared = mapOf(FieldKeys.CORRESPONDENT to "Aquafin")
            fieldsOf(rig, after) shouldBe cleared
            rig.db
                .tagDao()
                .tagsForDocument(after)
                .shouldBeEmpty()
            rig.session.rescan()
            fieldsOf(rig, after) shouldBe cleared
            rig.db
                .tagDao()
                .tagsForDocument(after)
                .shouldBeEmpty()
        }

    /**
     * The relink's transaction holds the copies: the baked document's fields, tags, review mark and
     * storage path are events of its own, published with the relink, and its history shows them.
     */
    @Test
    fun theBakesRelinkCopiesTheMetadataInItsOwnTransaction() =
        appSessionTest(storagePath = StoragePathRule("{correspondent}/{title}.pdf", enabled = true)) { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val before = rig.docId("a.pdf")
            rig.session.meta.applyEdits(listOf(before), addTags = listOf("filed"), fields = FILED)
            rig.session.meta.setReviewed(listOf(before), true)
            val filed = "Aquafin/Water bill.pdf"
            val objects =
                rig.fs
                    .journalObjects()
                    .map { it.relPath }
                    .toSet()

            rig.session.content.bakeInPlace(before) shouldBe BakeOutcome.Baked

            val after = rig.docId(filed)
            val written = rig.fs.journalObjects().filterNot { it.relPath in objects }
            val relinkObject = written.single { meta -> eventsOf(rig, meta.relPath).any { it.op is JournalOp.Relink } }
            eventsOf(rig, relinkObject.relPath).map { it.doc to it.op }.toSet() shouldBe
                setOf(
                    before to JournalOp.Relink(after),
                    after to JournalOp.FieldSet(FieldKeys.CORRESPONDENT, "Aquafin"),
                    after to JournalOp.FieldSet(FieldKeys.TITLE, "Water bill"),
                    after to JournalOp.TagAdd("filed"),
                    after to JournalOp.PropSet(SystemProperty.REVIEWED, "true"),
                    after to JournalOp.PropSet(SystemProperty.STORAGE_PATH, filed),
                )
            rig.db
                .documentDao()
                .getByDocId(after)
                ?.reviewed shouldBe true
            // Its history is its own events: the copies, not the original's edits before the bake.
            rig.session.documents
                .history(after)
                .map { it.entry }
                .toSet() shouldBe
                setOf(
                    HistoryEntry.FieldSet(FieldKeys.CORRESPONDENT, "Aquafin"),
                    HistoryEntry.FieldSet(FieldKeys.TITLE, "Water bill"),
                    HistoryEntry.TagAdded("filed"),
                    HistoryEntry.Reviewed(true),
                    HistoryEntry.StoragePathSet(filed),
                )
        }

    /**
     * The accepted change from inheritance: an edit of the original that a device which had not seen
     * the bake makes after it no longer reaches the baked document.
     */
    @Test
    fun aLaterEditOfTheOriginalDoesNotReachTheBakedDocument() =
        appSessionTest { rig ->
            val (before, after) = bakeFiled(rig)
            rig.clock.advance(1_000)
            rig.writer("other-device").commit(
                listOf(
                    PendingOp.fieldSet(before, FieldKeys.TITLE, "Sewer bill"),
                    PendingOp.tagAdd(before, "late"),
                ),
            )

            rig.session.rescan()

            fieldsOf(rig, after) shouldBe FILED
            rig.db
                .tagDao()
                .tagsForDocument(after) shouldContainExactly listOf("filed")
        }

    /** A document baked twice shows the title it was given between the bakes, not the first one. */
    @Test
    fun aDocumentBakedTwiceShowsItsLatestTitle() =
        appSessionTest { rig ->
            val (_, middle) = bakeFiled(rig)
            rig.session.meta.setField(middle, FieldKeys.TITLE, "Sewer bill")

            rig.session.content.bakeInPlace(middle) shouldBe BakeOutcome.Baked

            val last = rig.docId("a.pdf")
            val latest = FILED + (FieldKeys.TITLE to "Sewer bill")
            fieldsOf(rig, last) shouldBe latest
            rig.session.rescan()
            fieldsOf(rig, last) shouldBe latest
        }

    /**
     * A baked document's original bytes are an earlier version of it, which no journal event makes
     * live again: importing them adds no file and journals nothing, and finds the baked document.
     */
    @Test
    fun theOriginalBytesOfABakedDocumentAreADuplicateOfIt() =
        appSessionTest { rig ->
            val original = PdfFixtures.embeddedTextPdf("alpha")
            val (before, after) = baked(rig, original)
            val superseded = rig.db.documentDao().getByDocId(before)
            val objects = rig.journalObjectCount()

            rig.session.importPdfBytes(original, "a.pdf") shouldBe ImportOutcome.Duplicate(after, "a.pdf")

            rig.fs.libraryPdfs() shouldContainExactly listOf("a.pdf")
            rig.journalObjectCount() shouldBe objects
            rig.session.rescan().added shouldBe 0
            rig.db.documentDao().getByDocId(before) shouldBe superseded
            rig.fs.libraryPdfs() shouldContainExactly listOf("a.pdf")
        }

    /** With the baked document in the trash, the library shows nothing those bytes stand for: the import is refused. */
    @Test
    fun theOriginalBytesOfABakedDocumentInTheTrashAreRefused() =
        appSessionTest { rig ->
            val original = PdfFixtures.embeddedTextPdf("alpha")
            val (before, after) = baked(rig, original)
            rig.session.docLifecycle.trashDoc(after)
            val superseded = rig.db.documentDao().getByDocId(before)
            val objects = rig.journalObjectCount()

            rig.session.importPdfBytes(original, "a.pdf") shouldBe
                ImportOutcome.Failed(ImportFailure.SUPERSEDED)

            rig.fs.libraryPdfs().shouldBeEmpty()
            rig.journalObjectCount() shouldBe objects
            rig.session.rescan().added shouldBe 0
            rig.db.documentDao().getByDocId(before) shouldBe superseded
            rig.db
                .documentDao()
                .getByDocId(after)
                ?.trashed shouldBe true
            rig.fs.libraryPdfs().shouldBeEmpty()
        }

    /**
     * A file in the library holding a baked document's original bytes gets no row: no rescan counts
     * it as added, offers it for a relink, or rewrites the row of the document it was.
     */
    @Test
    fun aFileWithTheOriginalBytesOfABakedDocumentGetsNoRow() =
        appSessionTest { rig ->
            val original = PdfFixtures.embeddedTextPdf("alpha")
            val (before, _) = baked(rig, original)
            val superseded = rig.db.documentDao().getByDocId(before)
            rig.writePdf("copy.pdf", original)

            repeat(2) {
                val report = rig.session.rescan()
                report.added shouldBe 0
                report.relinkCandidates.shouldBeEmpty()
            }

            rig.db.documentDao().getByDocId(before) shouldBe superseded
        }

    @Test
    fun aDocumentThatIsGoneIsNotBaked() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.session.docLifecycle.trashDoc(id)

            rig.session.content.bakeInPlace(id) shouldBe BakeOutcome.Failed(BakeFailure.DOCUMENT_UNAVAILABLE)
        }

    /** The file at a document's path was replaced and the index has not looked yet: that file is not baked. */
    @Test
    fun aDocumentWhosePathHoldsAnotherFileIsNotBaked() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            val replacement = PdfFixtures.embeddedTextPdf("replacement")
            rig.fs.delete("a.pdf")
            rig.writePdf("a.pdf", replacement)

            rig.session.content.bakeInPlace(id) shouldBe BakeOutcome.Failed(BakeFailure.DOCUMENT_UNAVAILABLE)

            rig.fs.readBytes("a.pdf") shouldBe replacement
        }

    /**
     * Another document's bytes replace the file while the bake waits for the writer lock (a sync
     * tool): the bake reads them, finds they are not the document's and refuses, with nothing
     * written or relinked.
     */
    @Test
    fun aFileReplacedBeforeTheBakeReadsItIsNotBaked() = replacedDuringABake(Replacing.Moment.BEFORE_READ)

    /**
     * Another document's bytes replace the file while the bake rewrites its copy: the bake leaves
     * them where they are and removes its own rewrite, rather than deleting them to put it in place.
     */
    @Test
    fun aFileReplacedWhileItIsBakedIsLeftAlone() = replacedDuringABake(Replacing.Moment.AFTER_REWRITE)

    private fun replacedDuringABake(moment: Replacing.Moment) =
        runTest {
            val other = PdfFixtures.embeddedTextPdf("another document, written over the one being baked")
            val replacing = Replacing("a.pdf", other, moment)
            AppSessionRig.create(backgroundScope, files = replacing::over).use { rig ->
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                rig.session.work.drainOcr()
                val id = rig.docId("a.pdf")
                val objects = rig.journalObjectCount()
                replacing.armed = true

                rig.session.content.bakeInPlace(id) shouldBe BakeOutcome.Failed(BakeFailure.DOCUMENT_UNAVAILABLE)

                rig.fs.readBytes("a.pdf") shouldBe other
                rig.journalObjectCount() shouldBe objects
                rig.exists(NpnPaths.Temp.bakePath("a.pdf")) shouldBe false
            }
        }

    /**
     * Two bakes of one document at once, as the side panel and the full screen can each start one: the
     * second, after a change to the title, starts while the first holds its verified rewrite. They run
     * one after the other, so the second finds the document superseded, and the file at its path is
     * the one the relink names, with the document's metadata.
     */
    @Test
    fun twoBakesOfADocumentAtOnceRunOneAfterTheOther() =
        runTest {
            val readBacks = PausedReadBacks("a.pdf")
            val rig = AppSessionRig.create(backgroundScope, files = readBacks::over)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                rig.session.meta.setField(id, FieldKeys.TITLE, "Water bill")

                val first = async { rig.session.content.bakeInPlace(id) }
                readBacks.reached.receive()
                rig.session.meta.setField(id, FieldKeys.TITLE, "Sewer bill")
                val second = async { rig.session.content.bakeInPlace(id) }
                val overlapped = readBacks.nextWithin(QUIET_MILLIS)
                readBacks.release(0)
                val firstOutcome = first.await()
                readBacks.release(1)
                val secondOutcome = second.await()

                // The second waited for the first, rather than rewrite the same temporary file meanwhile.
                overlapped shouldBe false
                listOf(firstOutcome, secondOutcome).count { it == BakeOutcome.Baked } shouldBe 1
                rig.session.rescan()
                val baked = rig.docId("a.pdf")
                fieldsOf(rig, baked)[FieldKeys.TITLE] shouldBe "Sewer bill"
                rig.db
                    .documentDao()
                    .getByDocId(id)
                    ?.deleted shouldBe true
                rig.fs.libraryPdfs() shouldContainExactly listOf("a.pdf")
            } finally {
                readBacks.releaseAll()
                rig.close()
            }
        }

    @Test
    fun aFileThatCannotCarryMetadataIsRefusedAndLeftAlone() =
        runTest {
            val log = RecordingLog()
            AppSessionRig.create(backgroundScope, log = log).use { rig ->
                val bytes = "not really a pdf".encodeToByteArray()
                rig.writePdf("broken.pdf", bytes)
                rig.session.rescan()
                val id = rig.docId("broken.pdf")

                rig.session.content.bakeInPlace(id) shouldBe BakeOutcome.Failed(BakeFailure.UNSUPPORTED_PDF)

                rig.docId("broken.pdf") shouldBe id
                log.entries.any { it.error is BakeRefusedException } shouldBe true
            }
        }

    /**
     * Give the document at `a.pdf` the [FILED] fields and the tag `filed`, then bake it where they
     * filed it; returns its id before the bake and after.
     */
    private suspend fun bakeFiled(rig: AppSessionRig): Pair<String, String> {
        rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
        rig.session.rescan()
        val before = rig.docId("a.pdf")
        rig.session.meta.applyEdits(listOf(before), addTags = listOf("filed"), fields = FILED)
        val path = checkNotNull(rig.db.documentDao().getByDocId(before)).relPath
        rig.session.content.bakeInPlace(before) shouldBe BakeOutcome.Baked
        return before to rig.docId(path)
    }

    private suspend fun eventsOf(
        rig: AppSessionRig,
        relPath: String,
    ): List<JournalEvent> =
        assertIs<JournalSourceRead.Accepted>(StorageV2Reader.read(relPath, rig.fs.readBytes(relPath)))
            .events
            .map { it.event }

    private suspend fun fieldsOf(
        rig: AppSessionRig,
        docId: String,
    ) = rig.db
        .documentFieldDao()
        .valuesOf(docId)

    /** Bake the document [original] makes at `a.pdf`; returns its id before the bake and after. */
    private suspend fun baked(
        rig: AppSessionRig,
        original: ByteArray,
    ): Pair<String, String> {
        rig.writePdf("a.pdf", original)
        rig.session.rescan()
        val before = rig.docId("a.pdf")
        rig.session.content.bakeInPlace(before) shouldBe BakeOutcome.Baked
        return before to rig.docId("a.pdf")
    }

    /**
     * A library filesystem over which, once [armed], [bytes] replace the file at [path] once, at
     * [moment] of a bake: as another app or a sync tool would write over it.
     */
    private class Replacing(
        private val path: String,
        private val bytes: ByteArray,
        private val moment: Moment,
    ) {
        enum class Moment {
            /** Right before the file at [path] is read. */
            BEFORE_READ,

            /** Right after the bake wrote its rewritten copy beside it. */
            AFTER_REWRITE,
        }

        @Volatile var armed = false

        fun over(fs: LibraryFileSystem): LibraryFileSystem =
            object : LibraryFileSystem by fs {
                override suspend fun readBytes(relPath: String): ByteArray {
                    if (moment == Moment.BEFORE_READ && relPath == path) replace(fs)
                    return fs.readBytes(relPath)
                }

                override suspend fun writeBytesOnce(
                    relPath: String,
                    bytes: ByteArray,
                ) {
                    fs.writeBytesOnce(relPath, bytes)
                    if (moment == Moment.AFTER_REWRITE && relPath == NpnPaths.Temp.bakePath(path)) replace(fs)
                }
            }

        private suspend fun replace(fs: LibraryFileSystem) {
            if (!armed) return
            armed = false
            fs.delete(path)
            fs.writeBytesOnce(path, bytes)
        }
    }

    /**
     * A library filesystem over which each bake's read-back of its rewrite of [path] (the n-th) says so
     * on [reached], then waits until the n-th is [release]d: a bake held right after it verified its
     * temporary file, before it swaps it in. [nextWithin] waits on the wall clock.
     */
    private class PausedReadBacks(
        private val path: String,
    ) {
        val reached = Channel<Unit>(Channel.UNLIMITED)
        private val gates = List(2) { CompletableDeferred<Unit>() }
        private val reads = AtomicInteger()

        /** Whether another read-back is [reached] within [millis] of real time, not the test's virtual time. */
        suspend fun nextWithin(millis: Long): Boolean =
            withContext(Dispatchers.Default) { withTimeoutOrNull(millis) { reached.receive() } != null }

        fun release(n: Int) = gates[n].complete(Unit)

        fun releaseAll() = gates.forEach { it.complete(Unit) }

        fun over(fs: LibraryFileSystem): LibraryFileSystem =
            object : LibraryFileSystem by fs {
                override suspend fun readBytes(relPath: String): ByteArray {
                    val bytes = fs.readBytes(relPath)
                    if (relPath == NpnPaths.Temp.bakePath(path)) {
                        val n = reads.getAndIncrement()
                        reached.send(Unit)
                        gates.getOrNull(n)?.await()
                    }
                    return bytes
                }
            }
    }

    private companion object {
        val FILED = mapOf(FieldKeys.TITLE to "Water bill", FieldKeys.CORRESPONDENT to "Aquafin")

        /** How long a bake that does not wait gets to reach its read-back. */
        const val QUIET_MILLIS = 1_000L
    }
}
