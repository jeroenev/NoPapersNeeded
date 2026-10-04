package dev.npn.core.app

import dev.npn.core.fs.ExternalSource
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
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.session.importPdfBytes(ByteArray(0), "empty.pdf") shouldBe
                    ImportOutcome.Failed(ImportFailure.EMPTY_FILE)
                rig.fs
                    .listAllIndexable()
                    .filter { !it.isDirectory }
                    .shouldBeEmpty()
            } finally {
                rig.close()
            }
        }

    @Test
    fun imagesThatAreNotImagesAreRefusedAndLogged() =
        runTest {
            val log = RecordingLog()
            val rig = AppSessionRig.create(backgroundScope, log = log)
            try {
                rig.session.importImagesAsPdf(listOf("not an image".encodeToByteArray()), "scan.pdf") shouldBe
                    ImportOutcome.Failed(ImportFailure.UNREADABLE_IMAGES)
                log.entries shouldHaveSize 1
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
