package dev.npn.core.app

import dev.npn.core.fs.ExternalSource
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.util.RecordingLog
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test

/** What an import that cannot add its file says, instead of throwing. */
class ImportOutcomeTest {
    @Test
    fun anEmptyFileIsRefusedAsEmpty() =
        appSessionTest { rig ->
            rig.session.importPdfBytes(ByteArray(0), "empty.pdf") shouldBe
                ImportOutcome.Failed(ImportFailure.EMPTY_FILE)
            rig.fs
                .listAllIndexable()
                .filter { !it.isDirectory }
                .shouldBeEmpty()
        }

    @Test
    fun imagesThatAreNotImagesAreRefusedAndLogged() =
        runTest {
            val log = RecordingLog()
            AppSessionRig.create(backgroundScope, log = log).use { rig ->
                rig.session.importImagesAsPdf(listOf("not an image".encodeToByteArray()), "scan.pdf") shouldBe
                    ImportOutcome.Failed(ImportFailure.UNREADABLE_IMAGES)
                log.entries shouldHaveSize 1
            }
        }

    /** A batch tallies each file's outcome: a document, one already there, or one that failed. */
    @Test
    fun importAllTalliesEachFile() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            val dir = Files.createTempDirectory("npn-import")
            val pdf = dir.resolve("a.pdf").also { Files.write(it, PdfFixtures.embeddedTextPdf("alpha")) }
            val copy = dir.resolve("copy.pdf").also { Files.write(it, Files.readAllBytes(pdf)) }
            val gone = dir.resolve("gone.pdf")
            try {
                val tally =
                    rig.session.importAll(
                        listOf(
                            ExternalSource(pdf) to "a.pdf",
                            ExternalSource(copy) to "copy.pdf",
                            ExternalSource(gone) to "gone.pdf",
                        ),
                    )

                tally shouldBe ImportTally(imported = 1, duplicates = 1, failed = 1)
                tally.total shouldBe 3
            } finally {
                rig.close()
            }
        }

    @Test
    fun aSourceThatCannotBeReadIsACopyFailure() =
        runTest {
            val log = RecordingLog()
            val rig = AppSessionRig.create(backgroundScope, log = log)
            val gone = Files.createTempDirectory("npn-import").resolve("gone.pdf")
            try {
                rig.session.importPdf(ExternalSource(gone), "gone.pdf") shouldBe
                    ImportOutcome.Failed(ImportFailure.COPY_FAILED)
                log.entries shouldHaveSize 1
            } finally {
                rig.close()
            }
        }
}
