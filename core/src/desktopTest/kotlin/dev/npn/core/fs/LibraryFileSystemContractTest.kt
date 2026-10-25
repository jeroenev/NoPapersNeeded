package dev.npn.core.fs

import dev.npn.core.journal.JournalPaths
import dev.npn.core.sim.InMemoryLibraryFileSystem
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * What every [LibraryFileSystem] promises, run over the real one and the simulation's fake alike:
 * the fake stands in for the real thing in every simulated run, so a difference between them is a
 * simulation that proves nothing. The tests name only behaviour both must share; what one platform
 * adds (hard links, a watcher) has its own tests.
 */
abstract class LibraryFileSystemContract {
    protected abstract val fs: LibraryFileSystem

    private fun bytes(text: String) = text.encodeToByteArray()

    @Test
    fun writeOnceCreatesParentsAndRefusesAnExistingTarget() =
        runTest {
            fs.writeBytesOnce("a/b/doc.pdf", bytes("one"))
            fs.readBytes("a/b/doc.pdf") shouldBe bytes("one")
            assertFailsWith<IllegalStateException> { fs.writeBytesOnce("a/b/doc.pdf", bytes("two")) }
            fs.readBytes("a/b/doc.pdf") shouldBe bytes("one")
        }

    @Test
    fun statAndExistsTellAFileADirectoryAndNothingApart() =
        runTest {
            fs.stat("missing.pdf") shouldBe null
            fs.exists("missing.pdf").shouldBeFalse()
            fs.writeBytesOnce("inbox/doc.pdf", bytes("content"))
            val file = fs.stat("inbox/doc.pdf").shouldNotBeNull()
            file.isDirectory.shouldBeFalse()
            file.size shouldBe "content".length.toLong()
            file.name shouldBe "doc.pdf"
            fs
                .stat("inbox")
                .shouldNotBeNull()
                .isDirectory
                .shouldBeTrue()
            fs.exists("inbox").shouldBeTrue()
            fs.delete("inbox/doc.pdf")
            fs.stat("inbox/doc.pdf") shouldBe null
            // Deleting what is not there is not a failure.
            fs.delete("inbox/doc.pdf")
        }

    @Test
    fun listingsWalkBelowAPathAndTheIndexableWalkSkipsTheMetadataTree() =
        runTest {
            fs.writeBytesOnce("inbox/a.pdf", bytes("a"))
            fs.writeBytesOnce("inbox/deep/b.pdf", bytes("b"))
            fs.writeBytesOnce("${NpnPaths.NPN_DIR}/x.json", bytes("x"))
            fs.listAllUnder("").map { it.relPath } shouldContainExactlyInAnyOrder
                listOf(
                    "inbox",
                    "inbox/a.pdf",
                    "inbox/deep",
                    "inbox/deep/b.pdf",
                    NpnPaths.NPN_DIR,
                    "${NpnPaths.NPN_DIR}/x.json",
                )
            fs.listAllUnder("inbox").map { it.relPath } shouldContainExactlyInAnyOrder
                listOf("inbox/a.pdf", "inbox/deep", "inbox/deep/b.pdf")
            fs.listAllUnder("nowhere") shouldBe emptyList()
            fs.listAllIndexable().map { it.relPath } shouldContainExactlyInAnyOrder
                listOf("inbox", "inbox/a.pdf", "inbox/deep", "inbox/deep/b.pdf")
            fs
                .listAllUnder("")
                .single { it.relPath == "inbox" }
                .isDirectory
                .shouldBeTrue()
        }

    @Test
    fun moveCreatesParentsAndNeverReplacesATarget() =
        runTest {
            fs.writeBytesOnce("inbox/doc.pdf", bytes("doc"))
            fs.writeBytesOnce("inbox/taken.pdf", bytes("taken"))
            fs.move("inbox/doc.pdf", "archive/2024/doc.pdf")
            fs.exists("inbox/doc.pdf").shouldBeFalse()
            fs.readBytes("archive/2024/doc.pdf") shouldBe bytes("doc")
            assertFailsWith<IllegalStateException> { fs.move("archive/2024/doc.pdf", "inbox/taken.pdf") }
            fs.readBytes("inbox/taken.pdf") shouldBe bytes("taken")
            fs.readBytes("archive/2024/doc.pdf") shouldBe bytes("doc")
        }

    /**
     * A claim takes a name within one folder (a recovery name beside the document, and back): it
     * never replaces a target, and, unlike [LibraryFileSystem.move], makes no folder for one.
     */
    @Test
    fun claimingADocumentNeverReplacesATargetAndMakesNoFolder() =
        runTest {
            fs.writeBytesOnce("inbox/doc.pdf", bytes("doc"))
            fs.writeBytesOnce("inbox/taken.pdf", bytes("taken"))
            fs.claimDocument("inbox/doc.pdf", "inbox/recovery.pdf")
            fs.exists("inbox/doc.pdf").shouldBeFalse()
            fs.readBytes("inbox/recovery.pdf") shouldBe bytes("doc")
            assertFailsWith<FileAlreadyExistsException> { fs.claimDocument("inbox/recovery.pdf", "inbox/taken.pdf") }
            fs.readBytes("inbox/taken.pdf") shouldBe bytes("taken")
            assertFailsWith<IOException> { fs.claimDocument("inbox/recovery.pdf", "elsewhere/recovery.pdf") }
            fs.readBytes("inbox/recovery.pdf") shouldBe bytes("doc")
            fs.exists("elsewhere").shouldBeFalse()
        }

    @Test
    fun copyOnceCopiesAndNeverReplacesATarget() =
        runTest {
            fs.writeBytesOnce("inbox/doc.pdf", bytes("doc"))
            fs.copyOnce("inbox/doc.pdf", "${NpnPaths.TMP_DIR}/staging.pdf")
            fs.readBytes("${NpnPaths.TMP_DIR}/staging.pdf") shouldBe bytes("doc")
            fs.readBytes("inbox/doc.pdf") shouldBe bytes("doc")
            assertFailsWith<FileAlreadyExistsException> {
                fs.copyOnce(
                    "inbox/doc.pdf",
                    "${NpnPaths.TMP_DIR}/staging.pdf",
                )
            }
        }

    @Test
    fun publishingAnObjectInstallsVerifiedBytesOnceAndRemovesTheStaging() =
        runTest {
            val bytes = bytes("transaction")
            val hash = sha256Bytes(bytes)
            val canonical = JournalPaths.objectPath(hash)
            val first = JournalPaths.stagingPath(hash, "installation", "s1")
            fs.writeBytesOnce(first, bytes)
            fs.publishVerifiedObjectOnce(first, canonical, hash)
            fs.readBytes(canonical) shouldBe bytes
            fs.exists(first).shouldBeFalse()

            // The same bytes again count as published; the staging goes just the same.
            val second = JournalPaths.stagingPath(hash, "installation", "s2")
            fs.writeBytesOnce(second, bytes)
            fs.publishVerifiedObjectOnce(second, canonical, hash)
            fs.readBytes(canonical) shouldBe bytes
            fs.exists(second).shouldBeFalse()
        }

    @Test
    fun publishingRefusesOtherBytesAtTheCanonicalNameAndAStagingThatDoesNotHash() =
        runTest {
            val bytes = bytes("transaction")
            val hash = sha256Bytes(bytes)
            val canonical = JournalPaths.objectPath(hash)
            fs.writeBytesOnce(canonical, bytes("other"))
            val staging = JournalPaths.stagingPath(hash, "installation", "s1")
            fs.writeBytesOnce(staging, bytes)
            assertFailsWith<IllegalStateException> { fs.publishVerifiedObjectOnce(staging, canonical, hash) }
            fs.readBytes(canonical) shouldBe bytes("other")

            val wrong = JournalPaths.stagingPath(hash, "installation", "s2")
            fs.writeBytesOnce(wrong, bytes("not the transaction"))
            assertFailsWith<IllegalStateException> { fs.publishVerifiedObjectOnce(wrong, canonical, hash) }
            fs.readBytes(canonical) shouldBe bytes("other")
        }

    @Test
    fun sameFileIsTrueOfOnePathAndFalseOfTwoFiles() =
        runTest {
            fs.writeBytesOnce("inbox/a.pdf", bytes("same"))
            fs.writeBytesOnce("inbox/b.pdf", bytes("same"))
            fs.sameFile("inbox/a.pdf", "inbox/a.pdf").shouldBeTrue()
            fs.sameFile("inbox/a.pdf", "inbox/b.pdf").shouldBeFalse()
        }

    @Test
    fun readTailGivesTheLastBytesAndTheWholeOfAShorterFile() =
        runTest {
            fs.writeBytesOnce("inbox/doc.pdf", bytes("0123456789"))
            fs.readTail("inbox/doc.pdf", 4) shouldBe bytes("6789")
            fs.readTail("inbox/doc.pdf", 100) shouldBe bytes("0123456789")
        }

    @Test
    fun sha256HashesTheBytesAndFailsOnWhatIsNotThere() =
        runTest {
            fs.writeBytesOnce("inbox/doc.pdf", bytes("hashed"))
            fs.sha256("inbox/doc.pdf") shouldBe sha256Bytes(bytes("hashed"))
            assertFailsWith<NoSuchFileException> { fs.sha256("inbox/missing.pdf") }
            assertFailsWith<NoSuchFileException> { fs.readBytes("inbox/missing.pdf") }
        }

    @Test
    fun deletingADirectoryRemovesEverythingBelowIt() =
        runTest {
            fs.writeBytesOnce("inbox/a.pdf", bytes("a"))
            fs.writeBytesOnce("inbox/deep/b.pdf", bytes("b"))
            fs.delete("inbox")
            fs.exists("inbox").shouldBeFalse()
            fs.exists("inbox/deep/b.pdf").shouldBeFalse()
            fs.listAllUnder("") shouldBe emptyList()
        }
}

class JvmLibraryFileSystemContractTest : LibraryFileSystemContract() {
    private val root: Path = Files.createTempDirectory("npn-fs-contract")
    override val fs: LibraryFileSystem = JvmLibraryFileSystem(root, PathExternalIo)

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
    }
}

class InMemoryLibraryFileSystemContractTest : LibraryFileSystemContract() {
    override val fs: LibraryFileSystem = InMemoryLibraryFileSystem(now = { EPOCH })

    private companion object {
        const val EPOCH = 1_700_000_000_000L
    }
}
