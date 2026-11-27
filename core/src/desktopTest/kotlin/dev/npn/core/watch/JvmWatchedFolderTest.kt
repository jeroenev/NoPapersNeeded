package dev.npn.core.watch

import dev.npn.core.fs.sha256Bytes
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * A watched source goes only while it holds the bytes imported, and only once the library is proved
 * to hold them, asked last: after the source's own check, right before the delete.
 */
class JvmWatchedFolderTest {
    private val dir: Path = Files.createTempDirectory("npn-watched-folder-test")
    private val folder = JvmWatchedFolder(dir)

    @AfterTest
    fun teardown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun theLibraryIsAskedLastAndOnlyForASourceStillHoldingItsBytes() =
        runTest {
            val imported = "the scan".encodeToByteArray()
            Files.write(dir.resolve("scan.pdf"), imported)
            val listed = folder.listPdfs().single()
            var asked = 0

            // Written over since it was listed: its own check fails, and the library is never asked.
            val other = "the next scan, written over it".encodeToByteArray()
            Files.write(dir.resolve("scan.pdf"), other)
            Files.setLastModifiedTime(dir.resolve("scan.pdf"), FileTime.fromMillis(listed.mtimeMillis + 60_000))
            folder.delete(listed, sha256Bytes(imported)) { asked++ > -1 }.shouldBeFalse()
            asked shouldBe 0
            Files.exists(dir.resolve("scan.pdf")).shouldBeTrue()

            // Its own bytes, but the library does not hold them (any more): it stays.
            val again = folder.listPdfs().single()
            folder.delete(again, sha256Bytes(other)) { asked++ < 0 }.shouldBeFalse()
            asked shouldBe 1
            Files.exists(dir.resolve("scan.pdf")).shouldBeTrue()

            folder.delete(again, sha256Bytes(other)) { true }.shouldBeTrue()
            Files.exists(dir.resolve("scan.pdf")).shouldBeFalse()
        }
}
