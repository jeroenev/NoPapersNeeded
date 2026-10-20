package dev.npn.core.app

import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.PathExternalIo
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.index.MetadataWriteBlocked
import dev.npn.core.index.WriteBlock
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.journalObjects
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The session's [LibrarySession.writeBlock]: what refuses writes, as a caller can watch it rather
 * than learn it from a refused write.
 */
class WriteBlockFlowTest {
    private val dirs = LibraryDirs()
    private val library = dirs.library
    private val data = dirs.data

    @AfterTest
    fun teardown() = dirs.close()

    @Test
    fun aLibraryThatFoldsWholeTakesWrites() =
        runTest {
            Files.write(library.resolve("a.pdf"), PdfFixtures.embeddedTextPdf("alpha"))
            openSession(backgroundScope, library, data).use { session, _ ->
                session.writeBlock.value shouldBe null
                session.rescan()
                session.writeBlock.value shouldBe null
            }
        }

    /** An open that finds an applied object gone opens read-only, and says so until it is back. */
    @Test
    fun aReadOnlyOpenShowsTheBlockUntilAPassReadsTheJournalWhole() =
        runTest {
            Files.write(library.resolve("a.pdf"), PdfFixtures.embeddedTextPdf("alpha"))
            val fs = JvmLibraryFileSystem(library, PathExternalIo)
            val doc = fs.sha256("a.pdf")
            var lost = ""
            openSession(backgroundScope, library, data).use { session, _ ->
                session.rescan()
                val before = fs.journalObjects().map { it.relPath }.toSet()
                session.meta.addTag(listOf(doc), "invoice")
                lost = fs.journalObjects().map { it.relPath }.single { it !in before }
            }
            val aside = data.toPath().resolve(NpnPaths.fileName(lost))
            Files.move(library.resolve(lost), aside)

            openSession(backgroundScope, library, data).use { session, _ ->
                session.writeBlock.value shouldBe WriteBlock.JOURNAL_DAMAGED

                Files.move(aside, library.resolve(lost))
                session.rescan()

                session.writeBlock.value shouldBe null
            }
        }

    /** A pass that fails on a running session blocks writes, and the next one that succeeds lifts it. */
    @Test
    fun aFailedPassShowsTheBlockAndASucceedingOneLiftsIt() =
        runTest {
            Files.write(library.resolve("a.pdf"), PdfFixtures.embeddedTextPdf("alpha"))
            val fs = JvmLibraryFileSystem(library, PathExternalIo)
            val doc = fs.sha256("a.pdf")
            openSession(backgroundScope, library, data).use { session, _ ->
                session.rescan()
                // An object cut short, as a sync tool still writing it in place would leave it.
                val partial = "{".encodeToByteArray()
                val path = library.resolve(JournalPaths.objectPath(sha256Bytes(partial)))
                Files.createDirectories(path.parent)
                Files.write(path, partial)

                assertFailsWith<MetadataWriteBlocked> { session.rescan() }
                session.writeBlock.value shouldBe WriteBlock.JOURNAL_DAMAGED
                assertFailsWith<MetadataWriteBlocked> { session.meta.addTag(listOf(doc), "paid") }

                Files.delete(path)
                session.rescan()

                session.writeBlock.value shouldBe null
                session.meta.addTag(listOf(doc), "paid")
            }
        }
}
