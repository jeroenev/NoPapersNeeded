package dev.npn.core.watch

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test

/** Where a folder lies relative to the library, as the desktop compares two paths. */
class LibraryPlacementTest {
    private val root: Path = Files.createTempDirectory("npn-placement")
    private val library: Path = Files.createDirectories(root.resolve("Sync/Papers"))

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private suspend fun inLibrary(folder: Path) = liesInLibrary(folder.toString(), library.toString())

    @Test
    fun theLibraryAndEveryFolderBelowItAreInIt() =
        runTest {
            inLibrary(library) shouldBe true
            inLibrary(Files.createDirectories(library.resolve("scans"))) shouldBe true
            inLibrary(library.resolve("inbox/not-there-yet")) shouldBe true
            inLibrary(library.resolve("scans/../inbox")) shouldBe true
        }

    @Test
    fun aParentOrASiblingIsNot() =
        runTest {
            inLibrary(root.resolve("Sync")) shouldBe false
            inLibrary(Files.createDirectories(root.resolve("Sync/Papers2"))) shouldBe false
            inLibrary(root.resolve("Sync/Papers/../Scans")) shouldBe false
        }

    /** A link elsewhere that leads into the library is the folder it leads to. */
    @Test
    fun aFolderReachedThroughASymlinkIsWhereTheLinkLeads() =
        runTest {
            Files.createDirectories(library.resolve("scans"))
            val link = Files.createSymbolicLink(root.resolve("scanner-output"), library.resolve("scans"))
            inLibrary(link) shouldBe true

            val libraryLink = Files.createSymbolicLink(root.resolve("Papers-link"), library)
            liesInLibrary(library.resolve("scans").toString(), libraryLink.toString()) shouldBe true
            liesInLibrary(libraryLink.resolve("new").toString(), library.toString()) shouldBe true
        }
}
