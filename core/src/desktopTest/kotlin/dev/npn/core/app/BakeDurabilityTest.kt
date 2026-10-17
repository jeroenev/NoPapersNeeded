package dev.npn.core.app

import dev.npn.core.db.isLive
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.VerifiedPublicationFailedException
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.model.FieldKeys
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test

class BakeDurabilityTest {
    @Test
    fun aBakeWhoseRelinkCannotBePublishedKeepsTheOriginalDocument() =
        runTest {
            val fault = RelinkFault(RelinkFault.Stage.PUBLICATION)
            AppSessionRig.create(backgroundScope, files = fault::over).use { rig ->
                val original = filedDocument(rig)
                val before = rig.fs.readBytes("doc.pdf")
                val count = rig.journalObjectCount()
                fault.armed = true

                rig.session.content.bakeInPlace(original) shouldBe BakeOutcome.Failed(BakeFailure.NOT_SAVED)

                rig.fs.readBytes("doc.pdf") shouldBe before
                rig.journalObjectCount() shouldBe count
                rig.session.rescan()
                rig.db.tagDao().tagsForDocument(original) shouldContainExactly listOf("filed")
                rig.db
                    .documentDao()
                    .getByDocId(original)
                    ?.deleted shouldBe false
            }
        }

    @Test
    fun aPublishedBakeKeepsItsBytesAndMetadataWhenProjectionOrRescanFails() =
        runTest {
            for (stage in listOf(RelinkFault.Stage.PROJECTION, RelinkFault.Stage.RESCAN)) {
                val fault = RelinkFault(stage)
                AppSessionRig.create(backgroundScope, files = fault::over).use { rig ->
                    val original = filedDocument(rig)
                    val count = rig.journalObjectCount()
                    fault.armed = true

                    rig.session.content.bakeInPlace(original) shouldBe BakeOutcome.Baked

                    val baked = rig.docId("doc.pdf")
                    baked shouldNotBe original
                    rig.journalObjectCount() shouldBe count + 1
                    // A new device's empty index reconstructs the relink from the durable folder.
                    val (reopened, index) = rig.secondSession(backgroundScope)
                    reopened.rescan()
                    index.tagDao().tagsForDocument(baked) shouldContainExactly listOf("filed")
                    index.documentDao().getByDocId(baked)?.title shouldBe "Water bill"
                    index.documentDao().getByDocId(original)?.isLive shouldNotBe true
                }
            }
        }

    private suspend fun filedDocument(rig: AppSessionRig): String {
        rig.writePdf("doc.pdf", PdfFixtures.embeddedTextPdf("water"))
        rig.session.rescan()
        val original = rig.docId("doc.pdf")
        rig.session.meta.applyEdits(
            listOf(original),
            addTags = listOf("filed"),
            fields = mapOf(FieldKeys.TITLE to "Water bill"),
        )
        return original
    }

    private class RelinkFault(
        private val stage: Stage,
    ) {
        enum class Stage { PUBLICATION, PROJECTION, RESCAN }

        var armed = false
        private var published = false

        fun over(fs: LibraryFileSystem): LibraryFileSystem =
            object : LibraryFileSystem by fs {
                override suspend fun publishVerifiedObjectOnce(
                    stagingPath: String,
                    canonicalPath: String,
                    expectedHash: String,
                ) {
                    val transaction = JournalPaths.classify(canonicalPath) is JournalPath.ObjectV2
                    if (armed && transaction && stage == Stage.PUBLICATION) {
                        armed = false
                        throw VerifiedPublicationFailedException("journal disk full")
                    }
                    fs.publishVerifiedObjectOnce(stagingPath, canonicalPath, expectedHash)
                    if (armed && transaction) published = true
                }

                override suspend fun stat(relPath: String): FileMeta? {
                    val projectionFault = armed && published && stage == Stage.PROJECTION
                    if (projectionFault && JournalPaths.classify(relPath) is JournalPath.ObjectV2) {
                        armed = false
                        throw IOException("projection stat failed")
                    }
                    return fs.stat(relPath)
                }

                override suspend fun listAllIndexable(): List<FileMeta> {
                    if (armed && published && stage == Stage.RESCAN) {
                        armed = false
                        throw IOException("library listing failed after publication")
                    }
                    return fs.listAllIndexable()
                }
            }
    }
}
