package dev.npn.core.fs

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test

/** The keep store on a filesystem that cannot do everything a local disk can. */
class KeepStoreTest {
    private val root: Path = Files.createTempDirectory("npn-keep-store-test")
    private val disk = JvmLibraryFileSystem(root, PathExternalIo)
    private val bytes = "the document".encodeToByteArray()
    private val docId = sha256Bytes(bytes)

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    /**
     * A filesystem that refuses to set a file's modification time (an SMB mount the user does not
     * own, some FUSE mounts) still keeps a copy and puts it back, with the copy's own mtime: the time
     * is best effort, the bytes are not. Nothing is left behind in the store but the entry.
     */
    @Test
    fun aFilesystemThatRefusesModificationTimesStillKeepsAndPutsBack() =
        runTest {
            val fs =
                object : LibraryFileSystem by disk {
                    override suspend fun setModifiedTime(
                        relPath: String,
                        mtimeMillis: Long,
                    ) = throw IOException("Operation not permitted")
                }
            val store = KeepStore(fs, "test-installation")
            fs.writeBytesOnce("a.pdf", bytes)

            val entry = store.put("a.pdf", docId, episodeMillis = 1).shouldNotBeNull()
            fs.delete("a.pdf")
            store.materialize(docId, "a.pdf").shouldBeTrue()

            fs.sha256("a.pdf") shouldBe docId
            fs
                .listAllUnder(NpnPaths.KEEP_DIR)
                .filter { !it.isDirectory }
                .map { it.relPath }
                .minus(entry)
                .shouldBeEmpty()
        }
}
