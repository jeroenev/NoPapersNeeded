package dev.npn.core.fs

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * OCR sidecar and trash lookups run once or twice per OCR job and on every trash restore. They must
 * walk only their own directory under `.npn/`, never the whole library: on SAF every directory of a
 * walk is a binder query, and an index rebuild queues every document for OCR.
 */
class MetadataListingTest {
    private lateinit var root: Path
    private lateinit var fs: ListingCounter

    private class ListingCounter(
        delegate: LibraryFileSystem,
    ) : LibraryFileSystem by delegate {
        private val inner = delegate
        var wholeLibraryWalks = 0
        val walkedDirs = ArrayList<String>()

        /** Entries every walk returned, summed: the work a walk costs grows with this. */
        var entriesListed = 0

        override suspend fun listAll(): List<FileMeta> {
            wholeLibraryWalks++
            return inner.listAll().also { entriesListed += it.size }
        }

        override suspend fun listAllUnder(relPath: String): List<FileMeta> {
            walkedDirs += relPath
            return inner.listAllUnder(relPath).also { entriesListed += it.size }
        }
    }

    private val hash = "a".repeat(64)

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("npn-listing")
        repeat(LIBRARY_DIRS) { dir ->
            repeat(PDFS_PER_DIR) { file -> write("year-$dir/scan-$file.pdf") }
        }
        write("${NpnPaths.OCR_DIR}/$hash.dev-a.json")
        write("${NpnPaths.OCR_DIR}/$hash.dev-b.tesseract.json")
        write("${NpnPaths.OCR_DIR}/${"b".repeat(64)}.dev-a.json")
        write(NpnPaths.trashPath("old.pdf", hash))
        write("${NpnPaths.JOURNAL_DIR}/objects/x.json")
        fs = ListingCounter(JvmLibraryFileSystem(root, PathExternalIo))
    }

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    private fun write(relPath: String) {
        val path = root.resolve(relPath)
        Files.createDirectories(path.parent)
        Files.write(path, relPath.encodeToByteArray())
    }

    @Test
    fun sidecarLookupsWalkOnlyTheOcrDirectory() =
        runTest {
            val sidecars = OcrSidecarFiles(fs)

            sidecars.listForHash(hash) shouldContainExactlyInAnyOrder listOf("dev-a", "dev-b.tesseract")
            sidecars.list().size shouldBe 3

            fs.wholeLibraryWalks shouldBe 0
            fs.walkedDirs shouldContainExactly listOf(NpnPaths.OCR_DIR, NpnPaths.OCR_DIR)
            fs.entriesListed shouldBe 6
        }

    @Test
    fun trashLookupsWalkOnlyTheTrashDirectory() =
        runTest {
            TrashOps(fs).listTrash().map { it.relPath } shouldContainExactly listOf(NpnPaths.trashPath("old.pdf", hash))

            fs.wholeLibraryWalks shouldBe 0
            fs.walkedDirs shouldContainExactly listOf(NpnPaths.TRASH_DIR)
            fs.entriesListed shouldBe 1
        }

    private companion object {
        const val LIBRARY_DIRS = 10
        const val PDFS_PER_DIR = 20
    }
}
